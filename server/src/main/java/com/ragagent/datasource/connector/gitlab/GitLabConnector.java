package com.ragagent.datasource.connector.gitlab;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.StreamingConnector;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.common.text.Whitespace;

/**
 * GitLab 数据源连接器。
 *
 * <h2>无状态</h2>
 * <p>每个数据源自带 base_url 与 access_token（存在加密后的 credentials 里）。
 * <b>刻意不复用</b>注册表里的实例，
 * 否则两个数据源会共用第一次拿到的 token。
 * {@link #configured} 每次调用都造一个新 client，连接器本身不持有任何字段。</p>
 *
 * <h2>取消与超时</h2>
 * <p>取消靠线程中断，请求级超时落在 {@link GitLabClient} 的 30s 上。
 * 每次方法调用经 {@link #configured} 现场取一个配置好的客户端。</p>
 *
 * <h2>流式同步是生产路径</h2>
 * <p>{@link #fetchStream} 每读到一个受支持的文件就立刻 emit，大型项目的内容不会
 * 在内存里堆积；{@link #fetchAll} / {@link #fetchIncremental} 是基接口要求的
 * 兼容方法（service 层优先用
 * {@code instanceof StreamingConnector} 走流式）。</p>
 *
 * <h2>空结果返回 {@code null}</h2>
 * <p>{@link #fetchAll} / {@link #fetchIncremental} 在"一条都没有"时
 * <b>返回 {@code null}</b>（不是空列表）——{@code FetchedItem} 的 JSON 形态
 * 本身就是契约，调用方必须容忍空值。这一条与本项目其它模块
 * （如 Wiki 的 {@code ListIssues} 归一为 {@code []}）是<b>相反</b>的取舍。</p>
 */
public class GitLabConnector implements StreamingConnector {

    /** 受支持的文件扩展名（26 项）。 */
    static final Set<String> SUPPORTED_FILE_EXTENSIONS = Set.of(
            ".pdf", ".txt", ".docx", ".doc", ".epub",
            ".html", ".htm", ".mhtml", ".md", ".markdown", ".mdx",
            ".png", ".jpg", ".jpeg", ".gif",
            ".csv", ".xlsx", ".xls", ".pptx", ".ppt", ".json",
            ".mp3", ".wav", ".m4a", ".flac", ".ogg");

    /**
     * cursor 载荷的形状：{@code {"projects": …, "raw": "<再序列化一次的 JSON 字符串>"}}。
     *
     * <p>序列化用<b>按键排序</b>的 mapper，{@code raw} 字段是一个<b>被再序列化一次</b>
     * 的 JSON 字符串——它落
     * {@code data_sources.last_sync_cursor} 这个 jsonb 列，字节必须与既有数据一致。</p>
     */
    private static final ObjectMapper RAW_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 仅用于读回 cursor。 */
    private static final ObjectMapper READ_MAPPER = new ObjectMapper();

    private record Configured(GitLabClient client, String canonicalBase) {
    }

    @Override
    public String type() {
        return DataSourceConstants.CONNECTOR_TYPE_GITLAB;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /**
     * 用数据源自己的凭据建一个客户端。
     *
     * @throws ConnectorException.InvalidConfig {@code config == null}（裸哨兵，无细节）
     */
    private static Configured configured(DataSourceConfig config) {
        if (config == null) {
            throw new ConnectorException.InvalidConfig();
        }
        Map<String, Object> creds = config.getCredentials();
        String baseUrl = asString(creds, "base_url");
        String token = asString(creds, "access_token");
        GitLabClient client = GitLabClient.newClient(baseUrl, token);
        return new Configured(client, client.baseUrl());
    }

    /** 非字符串（含缺失）当 {@code ""}。 */
    private static String asString(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value instanceof String s ? s : "";
    }

    // ------------------------------------------------------------------
    // Connector
    // ------------------------------------------------------------------

    /**
     * 先建客户端（凭据缺失优先于配置错误报出），
     * 再 ping，最后<b>只在用户已经填了 {@code settings.projects}</b> 时校验它。
     *
     * <p>那个"只在填了才校验"的条件是刻意的：保存数据源的第一步允许只填凭据。</p>
     */
    @Override
    public void validate(DataSourceConfig config) {
        Configured c = configured(config);
        c.client().ping();
        if (config != null) {
            Map<String, Object> settings = config.getSettings();
            if (settings != null && settings.containsKey("projects")) {
                GitLabConfig.parse(config);
            }
        }
    }

    /**
     * {@code parent == ""} 列出 token 可见的项目；
     * 否则列出该项目默认分支下的<b>直接子目录</b>（只回 {@code tree} 类型，
     * blob 不在选择器里出现）。
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        Configured c = configured(config);
        GitLabConfig.parse(config);
        if (parentId == null || parentId.isEmpty()) {
            List<GitLabClient.Project> projects = c.client().projects();
            List<Resource> out = new ArrayList<>(projects.size());
            for (GitLabClient.Project p : projects) {
                Resource r = new Resource();
                r.setExternalId(Long.toString(p.id()));
                r.setName(p.pathWithNamespace());
                r.setType("project");
                r.setUrl(p.webUrl());
                r.setHasChildren(true);
                out.add(r);
            }
            return out;
        }
        // 父资源 ID 的形状是 "<项目数字 ID>:<目录路径>"（见本方法下半段的生成逻辑）
        String[] split = splitResourceId(parentId);
        String id = split[0];
        String dir = split[1];
        GitLabClient.Project p = c.client().project(id);
        String ref = p.defaultBranch() == null ? "" : p.defaultBranch();
        List<GitLabClient.TreeEntry> entries = c.client().tree(id, ref, dir);
        List<Resource> out = new ArrayList<>(entries.size());
        for (GitLabClient.TreeEntry e : entries) {
            if ("tree".equals(e.type())) {
                Resource r = new Resource();
                r.setExternalId(id + ":" + e.path());
                r.setName(e.name());
                r.setType("directory");
                r.setParentId(parentId);
                r.setHasChildren(true);
                out.add(r);
            }
        }
        return out;
    }

    /** 整棵树一次给到，无需揭示祖先，回空列表。 */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        return new ArrayList<>();
    }

    /**
     * 对每个选择取 ref（空则回落 {@code default_branch}），
     * 递归遍历配置的目录、逐个文件拉正文。
     *
     * <p><b>注意 id 的不对称</b>：{@code tree} 走的是<b>选择里的</b>
     * {@code project_id} 原文（可以是 {@code group/project}），而 {@code raw}
     * 走的是 API 返回的<b>数字 ID</b>。{@link #fetchStream} 则两者都用数字 ID
     * （它先调了 {@code project}）。这不是笔误，改任一处都会让请求打到别的 URL 上。</p>
     *
     * @param resourceIds 未使用
     * @return 一条都没有时返回 {@code null}
     */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        Configured c = configured(config);
        GitLabConfig cfg = GitLabConfig.parse(config);
        List<FetchedItem> out = null;
        for (GitLabConfig.ProjectSelection selection : cfg.projects()) {
            GitLabClient.Project p = c.client().project(selection.projectId());
            String ref = selection.ref();
            if (ref.isEmpty()) {
                ref = p.defaultBranch() == null ? "" : p.defaultBranch();
            }
            for (String file : files(c, selection.projectId(), ref, selection.paths())) {
                out = append(out, item(c, p, ref, file));
            }
        }
        return out;
    }

    /**
     * 增量同步。
     *
     * <p>每个项目单独比 commit：cursor 里没记录 → 全量枚举；
     * 记录的 SHA 与 head 不同 → 走 {@code compare} 拿增删改；
     * {@code compare} 失败或 {@code compare_timeout} → <b>回落整树枚举</b>
     * （不是报错——历史被改写后 compare 会失败，此时保守重扫比丢更新好）。</p>
     *
     * <p>返回的 cursor 形状是
     * {@code {"projects": {projectId: sha}, "raw": "<再序列化一次的 JSON 字符串>"}}。
     * 那个 {@code raw} 看着冗余，但它是既有线格式且会落 jsonb 列——
     * 别顺手去掉。</p>
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        Configured c = configured(config);
        GitLabConfig cfg = GitLabConfig.parse(config);
        Map<String, String> prev = decodeCursorProjects(cursor);
        List<FetchedItem> out = null;
        Map<String, String> next = new LinkedHashMap<>();
        for (GitLabConfig.ProjectSelection selection : cfg.projects()) {
            String selectionId = selection.projectId();
            GitLabClient.Project p = c.client().project(selectionId);
            String ref = selection.ref();
            if (ref.isEmpty()) {
                ref = p.defaultBranch() == null ? "" : p.defaultBranch();
            }
            String head = c.client().commitSha(selectionId, ref);
            String previous = prev.getOrDefault(selectionId, "");
            if (previous.isEmpty()) {
                for (String f : files(c, selectionId, ref, selection.paths())) {
                    out = append(out, item(c, p, ref, f));
                }
            } else if (!previous.equals(head)) {
                GitLabClient.Comparison diff = null;
                try {
                    diff = c.client().compare(selectionId, previous, head);
                } catch (ConnectorException err) {
                    // compare 失败与 compare_timeout 两种情况同一分支
                    diff = null;
                }
                if (diff == null || diff.compareTimeout()) {
                    List<String> listed;
                    try {
                        listed = files(c, selectionId, ref, selection.paths());
                    } catch (ConnectorException listErr) {
                        throw new ConnectorException("gitlab list files " + selectionId + ": "
                                + listErr.getMessage(), listErr);
                    }
                    for (String f : listed) {
                        out = append(out, item(c, p, ref, f));
                    }
                } else {
                    for (GitLabClient.Comparison.Diff d : diff.diffs()) {
                        if (d.deletedFile()) {
                            if (inScope(d.oldPath(), selection.paths()) && isSupportedFile(d.oldPath())) {
                                out = append(out, deleted(c, p, ref, d.oldPath()));
                            }
                            continue;
                        }
                        if (d.renamedFile() && inScope(d.oldPath(), selection.paths())
                                && isSupportedFile(d.oldPath())) {
                            out = append(out, deleted(c, p, ref, d.oldPath()));
                        }
                        if (inScope(d.newPath(), selection.paths()) && isSupportedFile(d.newPath())) {
                            out = append(out, item(c, p, ref, d.newPath()));
                        }
                    }
                }
            }
            next.put(selectionId, head);
        }
        return new FetchIncrementalResult(out, gitLabCursor(next));
    }

    /**
     * 生产同步路径。
     *
     * <p>与 {@link #fetchIncremental} 的两处不同：</p>
     * <ol>
     *   <li>{@code next} 先<b>拷贝 prev</b> 再按选择覆盖——
     *       于是"这次没被 sync 到的项目"的 SHA 不会被抹掉；</li>
     *   <li>每个 selection 结束（<b>包括一次都没同步的</b>）就 {@code checkpoint}
     *       一次，这样超时中断能从最后一个检查点续跑。</li>
     * </ol>
     */
    @Override
    public SyncCursor fetchStream(DataSourceConfig config, SyncCursor cursor, StreamHandler handler) {
        Configured c = configured(config);
        GitLabConfig cfg = GitLabConfig.parse(config);
        Map<String, String> prev = decodeCursorProjects(cursor);
        Map<String, String> next = new LinkedHashMap<>(prev);

        for (GitLabConfig.ProjectSelection selection : cfg.projects()) {
            String selectionId = selection.projectId();
            GitLabClient.Project project = c.client().project(selectionId);
            String ref = selection.ref();
            if (ref.isEmpty()) {
                ref = project.defaultBranch() == null ? "" : project.defaultBranch();
            }
            String head = c.client().commitSha(selectionId, ref);

            String previous = prev.get(selectionId);
            if (previous == null) {
                previous = "";
            }
            if (previous.isEmpty()) {
                streamFiles(c, project, ref, selection.paths(), handler);
            } else if (!previous.equals(head)) {
                streamChanges(c, project, ref, previous, head, selection.paths(), handler);
            }

            next.put(selectionId, head);
            handler.checkpoint(gitLabCursor(next));
        }
        return gitLabCursor(next);
    }

    // ------------------------------------------------------------------
    // 流式内部
    // ------------------------------------------------------------------

    /**
     * 按 compare 的 diff 逐条 emit。
     *
     * <p>删除/重命名的判定：{@code deleted_file} → 若 old_path 在范围内且类型受支持，
     * emit 一条删除项后 {@code continue}；{@code renamed_file} → 先按<b>老路径</b> emit 删除项
     * （同一条件），<b>然后不 return</b>，继续按 {@code new_path} 走新增分支
     * （于是重命名 = 删 + 增两条）。</p>
     *
     * <p>compare 不可用（历史被改写 / 被 GitLab 截断）时<b>回落整树枚举</b>，
     * 而不是报错——语义见 {@link #fetchIncremental}。</p>
     */
    private void streamChanges(Configured c, GitLabClient.Project project, String ref,
                               String from, String to, List<String> roots, StreamHandler handler) {
        GitLabClient.Comparison diff = null;
        try {
            diff = c.client().compare(Long.toString(project.id()), from, to);
        } catch (ConnectorException err) {
            diff = null;
        }
        if (diff == null || diff.compareTimeout()) {
            streamFiles(c, project, ref, roots, handler);
            return;
        }
        for (GitLabClient.Comparison.Diff change : diff.diffs()) {
            if (change.deletedFile()) {
                if (inScope(change.oldPath(), roots) && isSupportedFile(change.oldPath())) {
                    handler.emit(deleted(c, project, ref, change.oldPath()));
                }
                continue;
            }
            if (change.renamedFile() && inScope(change.oldPath(), roots)
                    && isSupportedFile(change.oldPath())) {
                handler.emit(deleted(c, project, ref, change.oldPath()));
            }
            if (inScope(change.newPath(), roots) && isSupportedFile(change.newPath())) {
                handler.emit(item(c, project, ref, change.newPath()));
            }
        }
    }

    /** 遍历目录，每个受支持的文件读正文后 emit。 */
    private void streamFiles(Configured c, GitLabClient.Project project, String ref,
                             List<String> roots, StreamHandler handler) {
        walkFiles(c, Long.toString(project.id()), ref, roots,
                file -> handler.emit(item(c, project, ref, file)));
    }

    private List<String> files(Configured c, String id, String ref, List<String> roots) {
        List<String> out = new ArrayList<>();
        walkFiles(c, id, ref, roots, out::add);
        return out;
    }

    /**
     * 递归遍历选中的目录。
     *
     * <p>刻意<b>不</b>收集路径——流式同步的内存只与遍历深度和当前文件体量有关。
     * {@code roots} 为空时等价于根目录（{@code [""]}）。</p>
     */
    private void walkFiles(Configured c, String id, String ref, List<String> roots,
                           FileVisitor visit) {
        List<String> effective = (roots == null || roots.isEmpty()) ? List.of("") : roots;
        for (String root : effective) {
            walk(c, id, ref, root, visit);
        }
    }

    private void walk(Configured c, String id, String ref, String dir, FileVisitor visit) {
        for (GitLabClient.TreeEntry e : c.client().tree(id, ref, dir)) {
            if ("tree".equals(e.type())) {
                walk(c, id, ref, e.path(), visit);
            } else if ("blob".equals(e.type()) && isSupportedFile(e.path())) {
                visit.visit(e.path());
            }
        }
    }

    @FunctionalInterface
    private interface FileVisitor {
        void visit(String file);
    }

    // ------------------------------------------------------------------
    // item 构造
    // ------------------------------------------------------------------

    /**
     * 构造一个文件条目。
     *
     * <p><b>{@code UpdatedAt} 刻意不设</b>：文件最后提交时间没取，
     * 取 fetch 时间就是伪造的源时间——它保持零值
     * {@code "0001-01-01T00:00:00Z"}。</p>
     */
    private FetchedItem item(Configured c, GitLabClient.Project p, String ref, String file) {
        byte[] body = c.client().raw(Long.toString(p.id()), ref, file);
        String projectId = Long.toString(p.id());
        FetchedItem item = new FetchedItem();
        item.setExternalId("gitlab:" + c.canonicalBase() + ":" + p.id() + ":" + ref + ":" + file);
        item.setTitle(p.pathWithNamespace() + "/" + file);
        item.setFileName(knowledgeRelativePath(p.name(), ref, file));
        item.setContent(body);
        item.setContentType("text/plain");
        item.setSourceResourceId(projectId);
        // UpdatedAt is intentionally left unset: the file's last commit time is not
        // fetched here, and a fetch timestamp would be a fabricated source time.
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", DataSourceConstants.CONNECTOR_TYPE_GITLAB);
        metadata.put("source_type", "gitlab");
        metadata.put("gitlab_project_id", projectId);
        metadata.put("gitlab_ref", ref);
        metadata.put("gitlab_path", file);
        metadata.put("gitlab_url", p.webUrl() + "/-/blob/" + ref + "/" + file);
        item.setMetadata(metadata);
        return item;
    }

    /**
     * 删除项的 {@code external_id} 与
     * 原条目<b>完全一致</b>——service 层靠它定位要删的那一行。
     * 其余字段保持零值（{@code content} 为 {@code null}）。
     */
    private FetchedItem deleted(Configured c, GitLabClient.Project p, String ref, String file) {
        FetchedItem item = new FetchedItem();
        item.setExternalId("gitlab:" + c.canonicalBase() + ":" + p.id() + ":" + ref + ":" + file);
        item.setDeleted(true);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("channel", DataSourceConstants.CONNECTOR_TYPE_GITLAB);
        metadata.put("gitlab_path", file);
        item.setMetadata(metadata);
        return item;
    }

    // ------------------------------------------------------------------
    // 纯函数
    // ------------------------------------------------------------------

    /** 取扩展名（小写）后查支持表。 */
    static boolean isSupportedFile(String file) {
        return SUPPORTED_FILE_EXTENSIONS.contains(goPathExt(file).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 取文件扩展名：从末尾往前找<b>最后一个 {@code '.'}</b>，
     * 遇到 {@code '/'} 就停；都没命中就回空串。
     *
     * <p>注意它<b>不是</b>"最后一个点之后"，两点要区分：{@code ".hidden"} 会回
     * {@code ".hidden"}（不是 {@code ""}），而 {@code "a."} 会回 {@code "."}。</p>
     */
    static String goPathExt(String path) {
        for (int i = path.length() - 1; i >= 0 && path.charAt(i) != '/'; i--) {
            if (path.charAt(i) == '.') {
                return path.substring(i);
            }
        }
        return "";
    }

    /**
     * 把仓库文件映射成 KB 的目录约定
     * {@code <项目名>-<分支>/<仓库内相对路径>}（分支里的 {@code /} 换成 {@code -}）。
     *
     * <p>最后一步经 {@link GitLabPath#join}（会做 clean），所以 {@code ref} 或
     * {@code projectName} 为空时根名会退化成 {@code "docs-"} / {@code "-main"}，
     * 而 {@code "/a.md"} 这种绝对形态会被 clean 掉前导斜杠挂到根名下面。</p>
     */
    static String knowledgeRelativePath(String projectName, String ref, String file) {
        String root = Whitespace.trimSpace(projectName) + "-"
                + Whitespace.trimSpace(ref).replace("/", "-");
        return GitLabPath.join(root, file);
    }

    /**
     * {@code roots} 为空即全项目；
     * 否则要求"完全相等或以 {@code root + "/"} 开头"。
     *
     * <p>末尾那个 {@code "/"} 是关键：{@code "ab/c" } 不被根 {@code "a"} 覆盖。</p>
     */
    static boolean inScope(String file, List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return true;
        }
        for (String r : roots) {
            if (file.equals(r) || file.startsWith(r + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按<b>第一个</b> {@code ':'} 切成两段。
     * 没有冒号时第二段是空串（不是 null）。
     */
    static String[] splitResourceId(String value) {
        int idx = value.indexOf(':');
        if (idx < 0) {
            return new String[]{value, ""};
        }
        return new String[]{value.substring(0, idx), value.substring(idx + 1)};
    }

    // ------------------------------------------------------------------
    // cursor
    // ------------------------------------------------------------------

    /**
     * 把 {@code projects} 映射包成
     * {@code {"projects": …, "raw": "<json>"}}。
     *
     * <p>{@code raw} 是<b>再序列化一次</b>的 {@code {"projects":{…}}} 字符串，
     * 会落 jsonb 列，别"顺手去掉"。</p>
     */
    static SyncCursor gitLabCursor(Map<String, String> projects) {
        String raw;
        try {
            raw = RAW_MAPPER.writeValueAsString(Map.of("projects", projects));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 值全是 String，这里不可达
            raw = "";
        }
        Map<String, Object> connectorCursor = new LinkedHashMap<>();
        connectorCursor.put("projects", projects);
        connectorCursor.put("raw", raw);
        SyncCursor cursor = new SyncCursor();
        cursor.setLastSyncTime(OffsetDateTime.now(ZoneOffset.UTC));
        cursor.setConnectorCursor(connectorCursor);
        return cursor;
    }

    /**
     * 从 cursor 里取回 {@code projects}，取不到（缺失 / 类型不对）时回空 map。
     *
     * <p>遇到非字符串值时<b>保留已解出的部分</b>、放弃剩余键（{@code break}）：
     * {@code {"projects":{"a":1,"b":"x"}}} 会得到
     * {@code {}}，而 {@code {"projects":{"a":"x","b":1}}} 会得到 {@code {a:x}}。
     * 这是与既有游标数据保持一致的读取语义。</p>
     */
    static Map<String, String> decodeCursorProjects(SyncCursor cursor) {
        Map<String, String> out = new LinkedHashMap<>();
        if (cursor == null || cursor.getConnectorCursor() == null) {
            return out;
        }
        JsonNode root = READ_MAPPER.valueToTree(cursor.getConnectorCursor());
        JsonNode projects = root.get("projects");
        if (projects == null || !projects.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = projects.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual()) {
                break;
            }
            out.put(entry.getKey(), entry.getValue().textValue());
        }
        return out;
    }

    /** {@code null} 时在第一次追加时才分配列表（保持"一条都没有 = null"的契约）。 */
    private static List<FetchedItem> append(List<FetchedItem> out, FetchedItem item) {
        if (out == null) {
            out = new ArrayList<>();
        }
        out.add(item);
        return out;
    }
}
