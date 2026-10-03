package com.ragagent.datasource.connector.feishu.drive;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.StreamHandler;
import com.ragagent.datasource.StreamingConnector;
import com.ragagent.datasource.connector.feishu.core.DocxFetcher;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFile;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.DriveFileListFailure;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialDriveFileListException;
import com.ragagent.datasource.connector.feishu.core.FeishuClient;
import com.ragagent.datasource.connector.feishu.core.FeishuConfig;
import com.ragagent.datasource.connector.feishu.core.FeishuCursorCodec;
import com.ragagent.datasource.connector.feishu.core.FeishuErrors;
import com.ragagent.datasource.connector.feishu.core.FeishuRegion;
import com.ragagent.datasource.connector.feishu.core.FeishuSupport;
import com.ragagent.datasource.connector.feishu.core.SyncEngine;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * 飞书云盘（Drive）连接器。
 *
 * <p>与 wiki 连接器共用 {@link FeishuClient} / {@link FeishuConfig} / {@link FeishuRegion}
 * 与导出/下载逻辑；只有<b>资源枚举</b>与<b>抓取派发</b>不同。</p>
 *
 * <h2>资源 ID 编码</h2>
 * <pre>
 *   根文件夹   : "folderToken"
 *   子项       : "folderToken:fileToken"
 * </pre>
 * <p>根资源的 ExternalID 是<b>裸的 rootFolderToken</b>（不带 {@code ":token"} 后缀），
 * 这样它才与用户存在 {@code form.config.resource_ids = [folderToken]} 里的值一致——
 * 写成 {@code "token:token"} 会让编辑时的选中匹配失效。</p>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li>钩子 / 关联预加载 / 软删除 / 默认排序 / 唯一索引 / 自动时间戳：<b>全无</b>——
 *       本连接器不碰数据库。</li>
 * </ol>
 */
public class DriveConnector implements StreamingConnector {

    private static final Logger log = LoggerFactory.getLogger(DriveConnector.class);

    private final FeishuRegion region;

    /** @param region 部署区域（决定 connector type 与 URL host）。 */
    public DriveConnector(FeishuRegion region) {
        this.region = region;
    }

    public FeishuRegion region() {
        return region;
    }

    /** 连接器类型（feishu_drive / lark_drive）。 */
    @Override
    public String type() {
        return region.connectorType();
    }

    /**
     * 真实连一次飞书验活。
     *
     * <p><b>不</b>在这里校验 folder_token——那件事发生在 {@link #listResources}
     * 加载树根的时候（镜像 wiki 连接器）。</p>
     */
    @Override
    public void validate(DataSourceConfig config) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        try {
            client.ping();
        } catch (RuntimeException e) {
            throw new ConnectorException(region.label() + " connection failed: " + messageOf(e), e);
        }
    }

    /**
     * 给选择器列出云盘资源，一次只加载一层。
     *
     * <ul>
     *   <li>{@code parentId == ""} → 返回用户提供的根文件夹
     *       （取自 {@code config.resourceIds[0]}）作为唯一的根资源（HasChildren=true）。
     *       云盘没有"空间列表"API，所以根是用户给的；{@code folder_token == ""} 直接拒绝。</li>
     *   <li>{@code parentId == folderToken} → 返回该文件夹的直接子项；</li>
     *   <li>{@code parentId == "folderToken:subFolderToken"} → 返回那个子文件夹的直接子项。</li>
     * </ul>
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        String parent = parentId == null ? "" : parentId;

        if (parent.isEmpty()) {
            // 根加载：从 config.resourceIds 里读用户给的 folder_token
            String rootFolderToken = driveRootFolderToken(config);
            if (rootFolderToken.isEmpty()) {
                throw new ConnectorException(
                        "folder_token is required; specify a Drive folder token");
            }
            // 通过列根的直接子项来校验访问权限（顺便把第一层懒加载出来给选择器）。
            // 复用这个列举调用，而不是另外发一次 ping。
            try {
                client.listDriveFilesAllPages(rootFolderToken);
            } catch (RuntimeException e) {
                throw new ConnectorException("list feishu drive folder " + rootFolderToken
                        + ": " + messageOf(e), e);
            }
            // 经 folder meta API 解析根文件夹的人类可读名字。尽力而为：失败
            // （无权限/不存在）就回落到 folder_token，至少让选择器能渲染。
            String folderName = rootFolderToken;
            try {
                var meta = client.getDriveFolderMeta(rootFolderToken);
                if (meta.data() != null && meta.data().name() != null && !meta.data().name().isEmpty()) {
                    folderName = meta.data().name();
                }
            } catch (RuntimeException mErr) {
                log.warn("[FeishuDrive] resolve root folder name failed: {} (falling back to token)",
                        messageOf(mErr));
            }
            return List.of(driveFolderToResource(rootFolderToken, "", rootFolderToken, folderName));
        }

        // 惰性加载：只列给定文件夹的直接子项。
        String[] parts = parseDriveResourceId(parent);
        String rootFolderToken = parts[0];
        String folderToken = parts[1];
        if (folderToken.isEmpty()) {
            // parentID 就是裸的根文件夹 token → 列它的子项
            folderToken = rootFolderToken;
        }
        List<DriveFile> files;
        try {
            files = client.listDriveFilesAllPages(folderToken);
        } catch (RuntimeException e) {
            throw new ConnectorException(
                    "list feishu drive files under " + parent + ": " + messageOf(e), e);
        }

        List<Resource> resources = new ArrayList<>(files.size());
        for (DriveFile f : files) {
            resources.add(driveFileToResource(rootFolderToken, f));
        }
        return resources;
    }

    /**
     * 返回"为了让惰性选择器展开到某个选中项、
     * 必须加载其直接子项"的全部父文件夹资源 ID。
     *
     * <p>wiki 连接器靠单节点查询（{@code parent_node_token}）O(depth) 上溯；
     * 云盘<b>没有</b>单个文件查父的 API（已实测：metas/batch_query 不返回 parent），
     * 所以这里从根文件夹<b>自顶向下</b>走列表 API，并对同一个根下的
     * 所有选中项<b>共享</b>这次遍历。尽力而为：断掉的路径就保持折叠。</p>
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);

        Set<String> seen = new LinkedHashSet<>();
        List<String> ancestors = new ArrayList<>();

        // 按根文件夹分组，让一次共享遍历覆盖它们全部。
        // LinkedHashMap 固定为插入序，本方法的返回值可复现。
        Map<String, List<String>> rootSelections = new LinkedHashMap<>();
        for (String rid : resourceIds == null ? List.<String>of() : resourceIds) {
            String[] parts = parseDriveResourceId(rid);
            String rootFolderToken = parts[0];
            String fileToken = parts[1];
            if (fileToken.isEmpty()) {
                // 根级选中项本来就是顶层节点，没有可展开的。
                continue;
            }
            addAncestor(seen, ancestors, rootFolderToken);
            rootSelections.computeIfAbsent(rootFolderToken, k -> new ArrayList<>()).add(fileToken);
        }

        // 对每个根做 BFS：每到一个文件夹就 ListDriveFiles，判断哪些选中项是它的直接子项
        // （记下它们的父链），哪些子文件夹可能还装着选中项（入队）。
        // 共享遍历意味着同一子树里的选中项能复用列举调用。
        for (Map.Entry<String, List<String>> entry : rootSelections.entrySet()) {
            String rootFolderToken = entry.getKey();
            Set<String> remaining = new LinkedHashSet<>(entry.getValue());
            // parentChain[fileToken] = 它所在父文件夹的 resourceID
            Map<String, String> parentChain = new LinkedHashMap<>();

            Deque<String> queue = new ArrayDeque<>();
            queue.add(rootFolderToken);
            while (!queue.isEmpty() && !remaining.isEmpty()) {
                String cur = queue.poll();

                List<DriveFile> files;
                try {
                    files = client.listDriveFilesAllPages(cur);
                } catch (RuntimeException e) {
                    log.warn("[FeishuDrive] resolve ancestors: list {}: {}", cur, messageOf(e));
                    break; // 尽力而为：停止这个根的遍历
                }
                for (DriveFile f : files) {
                    if (remaining.contains(f.getToken())) {
                        remaining.remove(f.getToken());
                        // 记录从根到这个文件父节点的链
                        for (String a : buildDriveAncestorChain(rootFolderToken, cur, parentChain)) {
                            addAncestor(seen, ancestors, a);
                        }
                    }
                    if ("folder".equals(f.getType())) {
                        parentChain.put(f.getToken(), makeDriveResourceId(rootFolderToken, cur));
                        queue.add(f.getToken());
                    }
                }
            }
        }

        return ancestors;
    }

    private static void addAncestor(Set<String> seen, List<String> ancestors, String id) {
        if (id != null && !id.isEmpty() && seen.add(id)) {
            ancestors.add(id);
        }
    }

    /**
     * 从 {@code cur} 沿 parentChain 走到根，
     * 返回（根, …, cur 的父）这份<b>根在前</b>的 resourceID 列表。
     */
    static List<String> buildDriveAncestorChain(String rootFolderToken, String cur,
                                                Map<String, String> parentChain) {
        List<String> chain = new ArrayList<>();
        String node = cur;
        while (node != null && !node.isEmpty() && !node.equals(rootFolderToken)) {
            String parent = parentChain.get(node);
            if (parent == null) {
                break;
            }
            chain.add(0, parent);
            node = parseDriveResourceId(parent)[1];
        }
        return chain;
    }

    /**
     * 全量同步选中的云盘文件夹下全部文档。
     * 防御性回落路径——service 会优先调 {@link #fetchStream}。
     */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        return SyncEngine.fetchAllEngine(client, config, resourceIds, new DriveOps(region));
    }

    /**
     * 对比文件 modified_time 与上次记录做增量同步。
     * 与 {@code FetchStream} 走同一个引擎，所以 #2136 的"失败不推进游标"同样成立。
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        DriveOps ops = new DriveOps(region);
        if (config.getResourceIds() == null || config.getResourceIds().isEmpty()) {
            throw new ConnectorException(ops.emptyResourceIdsError());
        }
        return SyncEngine.fetchIncrementalEngine(client, config, cursor, ops);
    }

    /**
     * 可续跑、内存有界的同步。
     * {@code cursor == null} 时全量，有游标时跳过 modified_time 未变的文件。
     */
    @Override
    public SyncCursor fetchStream(DataSourceConfig config, SyncCursor cursor, StreamHandler handler) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        DriveOps ops = new DriveOps(region);
        if (config.getResourceIds() == null || config.getResourceIds().isEmpty()) {
            throw new ConnectorException(ops.emptyResourceIdsError());
        }
        return SyncEngine.fetchStreamEngine(client, config, cursor, handler, ops);
    }

    // ──────────────────────────────────────────────────────────────────
    // driveOps：把云盘连接器适配到共享同步引擎
    // ──────────────────────────────────────────────────────────────────

    /**
     * 携带 region（用于 channel 与 URL），并负责编解码
     * 云盘的游标线格式（{@code file_times}）。
     */
    static final class DriveOps implements SyncEngine.NodeOps<DriveFile> {

        private final FeishuRegion region;

        DriveOps(FeishuRegion region) {
            this.region = region;
        }

        @Override
        public SyncEngine.NodeOps.ListResult<DriveFile> list(FeishuClient client, String resourceId) {
            try {
                List<DriveFile> files = listDriveFilesForResource(client, resourceId);
                return SyncEngine.NodeOps.ListResult.ok(files);
            } catch (PartialDriveFileListException partial) {
                return SyncEngine.NodeOps.ListResult.partial(partial.getFiles(), partial);
            } catch (RuntimeException err) {
                return SyncEngine.NodeOps.ListResult.failed(err);
            }
        }

        @Override
        public String token(DriveFile n) {
            return n.getToken();
        }

        @Override
        public String title(DriveFile n) {
            return n.getName();
        }

        @Override
        public String objType(DriveFile n) {
            return n.getType();
        }

        @Override
        public String editTime(DriveFile n) {
            return n.getModifiedTime();
        }

        @Override
        public List<FetchedItem> fetch(FeishuClient client, DriveFile n, String resourceId,
                                      boolean multimodal) {
            return fetchDriveFileContent(client, n, resourceId, multimodal, region);
        }

        @Override
        public List<FetchedItem> listFailureItems(String resourceId, RuntimeException partial) {
            if (partial instanceof PartialDriveFileListException pe) {
                return appendDriveFileListFailureItems(new ArrayList<>(), resourceId, channel(),
                        pe.getFailures());
            }
            return List.of();
        }

        /** LarkDrive → {@code lark_drive}，否则 {@code feishu_drive}。 */
        String channel() {
            return "lark_drive".equals(region.connectorType())
                    ? FeishuSupport.CHANNEL_LARK_DRIVE
                    : FeishuSupport.CHANNEL_FEISHU_DRIVE;
        }

        @Override
        public String resourceNoun() {
            return "files";
        }

        @Override
        public String emptyResourceIdsError() {
            return "no resource IDs (Drive folder tokens) configured";
        }

        @Override
        public String logTag() {
            return "[FeishuDrive]";
        }

        @Override
        public Map<String, Map<String, String>> decodeCursorTimes(Map<String, Object> connectorCursor) {
            return FeishuCursorCodec.decodeFileTimes(connectorCursor);
        }

        @Override
        public SyncCursor encodeCursor(Map<String, Map<String, String>> times, OffsetDateTime lastSync) {
            return FeishuCursorCodec.encodeFileTimes(times, lastSync);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 单文件抓取
    // ──────────────────────────────────────────────────────────────────

    /**
     * 抓一个云盘文件的内容并转成 FetchedItems。
     * 按 {@code file.Type} 派发。
     * 快捷方式已经被 {@code ListDriveFilesRecursiveFrom} 展开成目标，所以这里只会
     * 看到目标的类型。
     *
     * <ul>
     *   <li>{@code docx} → blocks API（Markdown）+ 导出回落；可能带附件/图片</li>
     *   <li>{@code doc}/{@code sheet}/{@code bitable} → 异步导出 → docx/xlsx</li>
     *   <li>{@code file} → 下载原始文件</li>
     *   <li>{@code mindnote}/{@code slides}/{@code board} → 跳过（没有 API），返回空列表</li>
     * </ul>
     */
    static List<FetchedItem> fetchDriveFileContent(FeishuClient client, DriveFile file,
                                                   String resourceId, boolean multimodalEnabled,
                                                   FeishuRegion region) {
        if (!FeishuSupport.isSupportedDocType(file.getType())) {
            return List.of();
        }

        OffsetDateTime editTime = FeishuSupport.orGoZero(
                FeishuSupport.parseFeishuTimestamp(file.getModifiedTime()));
        OffsetDateTime createTime = FeishuSupport.orGoZero(
                FeishuSupport.parseFeishuTimestamp(file.getCreatedTime()));
        // Channel 标记知识的"来源"标签。云盘用自己的渠道（feishu_drive / lark_drive），
        // 于是云盘文档显示"飞书云盘"/"Lark 云盘"，与 wiki 连接器的"飞书"区分开。
        String channel = "lark_drive".equals(region.connectorType())
                ? FeishuSupport.CHANNEL_LARK_DRIVE
                : FeishuSupport.CHANNEL_FEISHU_DRIVE;
        Map<String, String> baseMeta = new LinkedHashMap<>();
        baseMeta.put("obj_token", file.getToken());
        baseMeta.put("obj_type", file.getType());
        baseMeta.put("file_token", file.getToken());
        baseMeta.put("folder_token", file.getParentToken());
        baseMeta.put("channel", channel);

        switch (file.getType()) {
            case "docx": {
                DocxFetcher.DocxFetchInput in = new DocxFetcher.DocxFetchInput();
                in.docToken = file.getToken();
                in.objToken = file.getToken();
                in.title = file.getName();
                in.url = file.getUrl();
                in.resourceId = resourceId;
                in.editTime = editTime;
                in.createTime = createTime;
                in.baseMeta = baseMeta;
                in.multimodalEnabled = multimodalEnabled;
                return DocxFetcher.fetchDocxWithBlocks(client, in);
            }
            case "doc", "sheet", "bitable": {
                FeishuClient.ExportDownload exported;
                try {
                    exported = client.exportAndDownload(file.getToken(), file.getType());
                } catch (RuntimeException e) {
                    throw new ConnectorException("export " + file.getName() + " (" + file.getType()
                            + "): " + messageOf(e), e);
                }

                String ext = FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(
                        FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get(file.getType()));
                String fileName = exported.fileName();
                if (fileName == null || fileName.isEmpty()) {
                    fileName = FeishuSupport.sanitizeFileName(file.getName()) + ext;
                } else if (!fileName.toLowerCase(Locale.ROOT).endsWith(ext)) {
                    fileName = FeishuSupport.sanitizeFileName(fileName) + ext;
                }

                FetchedItem item = new FetchedItem();
                item.setExternalId(file.getToken());
                item.setTitle(file.getName());
                item.setContent(exported.data());
                item.setContentType("application/octet-stream");
                item.setFileName(fileName);
                item.setUrl(file.getUrl());
                item.setUpdatedAt(editTime);
                item.setCreatedAt(createTime);
                item.setSourceResourceId(resourceId);
                item.setMetadata(baseMeta);
                return List.of(item);
            }
            case "file": {
                byte[] data;
                try {
                    data = client.downloadDriveFile(file.getToken());
                } catch (RuntimeException e) {
                    throw new ConnectorException("download file " + file.getName() + " ("
                            + file.getToken() + "): " + messageOf(e), e);
                }

                String fileName = file.getName();
                if (fileName.isEmpty()) {
                    fileName = file.getToken();
                }

                FetchedItem item = new FetchedItem();
                item.setExternalId(file.getToken());
                item.setTitle(file.getName());
                item.setContent(data);
                item.setContentType("application/octet-stream");
                item.setFileName(fileName);
                item.setUrl(file.getUrl());
                item.setUpdatedAt(editTime);
                item.setCreatedAt(createTime);
                item.setSourceResourceId(resourceId);
                item.setMetadata(baseMeta);
                return List.of(item);
            }
            default:
                return List.of();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 辅助函数
    // ──────────────────────────────────────────────────────────────────

    /**
     * 根是 {@code "folderToken"}，
     * 子是 {@code "folderToken:fileToken"}（复用 wiki 的 {@code ':'} 分隔符）。
     */
    static String makeDriveResourceId(String rootFolderToken, String fileToken) {
        if (fileToken == null || fileToken.isEmpty()) {
            return rootFolderToken;
        }
        return rootFolderToken + FeishuSupport.FEISHU_WIKI_NODE_RESOURCE_SEPARATOR + fileToken;
    }

    /** 按 {@code ':'} 切成（rootFolderToken, fileToken）两段。 */
    static String[] parseDriveResourceId(String resourceId) {
        return FeishuSupport.cut(resourceId, FeishuSupport.FEISHU_WIKI_NODE_RESOURCE_SEPARATOR);
    }

    /**
     * 列出某个 resourceID 要同步的文件。
     *
     * <p>resourceID 要么是裸的根 folderToken（同步整棵子树），要么是
     * {@code "rootFolderToken:fileToken"}（只同步选中的那个文件或子文件夹）。</p>
     *
     * <p>单个<b>文件</b>的选中项不能把 fileToken 直接交给子树遍历 API——那个 API 期望文件夹，
     * 传文件 token 会返回
     * {@code 1061002}（params error）。改为走该文件<b>父文件夹</b>（也就是根）的子树并
     * 过滤出选中的 fileToken。这与 wiki 连接器解析单个选中节点的做法等价；
     * 云盘没有单文件 meta API，所以"过滤子树遍历"是等价手段。</p>
     *
     * <p>选中项本身是<b>子文件夹</b>时，直接走那个子文件夹的子树——
     * 子树遍历接受文件夹 token，不需要过滤。</p>
     */
    static List<DriveFile> listDriveFilesForResource(FeishuClient client, String resourceId) {
        String[] parts = parseDriveResourceId(resourceId);
        String rootFolderToken = parts[0];
        String fileToken = parts[1];
        if (fileToken.isEmpty()) {
            return client.listDriveFilesRecursiveFrom(rootFolderToken);
        }
        try {
            return client.listDriveFilesRecursiveFrom(fileToken);
        } catch (RuntimeException err) {
            if (!isDriveNotFolderError(err)) {
                throw err;
            }
            List<DriveFile> all;
            try {
                all = client.listDriveFilesRecursiveFrom(rootFolderToken);
            } catch (PartialDriveFileListException walkErr) {
                List<DriveFile> filtered = filterDriveFileByToken(walkErr.getFiles(), fileToken);
                if (filtered.isEmpty()) {
                    throw walkErr;
                }
                return filtered;
            }
            return filterDriveFileByToken(all, fileToken);
        }
    }

    /**
     * {@code err} 是不是在说"这个 token 不是文件夹"
     * （对文件 token 调列表 API 时返回的 1061002 params error）。
     */
    static boolean isDriveNotFolderError(RuntimeException err) {
        String s = (err == null || err.getMessage() == null) ? "" : err.getMessage().toLowerCase(Locale.ROOT);
        return s.contains("1061002") || s.contains("params error");
    }

    /** 只留下 token 匹配的条目。 */
    static List<DriveFile> filterDriveFileByToken(List<DriveFile> files, String token) {
        List<DriveFile> out = new ArrayList<>();
        for (DriveFile f : files == null ? List.<DriveFile>of() : files) {
            if (f.getToken().equals(token)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 从数据源配置里取用户给的根 folder_token
     * （{@code resourceIds[0]}）。
     */
    static String driveRootFolderToken(DataSourceConfig config) {
        if (config == null || config.getResourceIds() == null || config.getResourceIds().isEmpty()) {
            return "";
        }
        return parseDriveResourceId(config.getResourceIds().get(0))[0];
    }

    /**
     * 构造云盘文件夹的根 Resource。
     *
     * <p>根文件夹的名字由调用方经 folder meta API 解析（尽力而为，回落到 token）。
     * 子文件夹请用 {@link #driveFileToResource}——列表 API 会返回每个子文件夹的 Name。</p>
     *
     * <p>根的 ExternalID 是<b>裸的 rootFolderToken</b>（无 {@code ":fileToken"} 后缀），
     * 以便与用户存在 {@code form.config.resource_ids = [folderToken]} 里的值匹配。</p>
     *
     * <p>{@code parentToken} 参数当前未被使用，仅为保持调用方签名稳定而保留。</p>
     */
    Resource driveFolderToResource(String rootFolderToken, String parentToken,
                                   String folderToken, String name) {
        String resolvedName = (name == null || name.isEmpty()) ? folderToken : name;
        Resource r = new Resource();
        r.setExternalId(rootFolderToken);
        r.setName(resolvedName);
        r.setType("drive_folder");
        r.setUrl(region.driveFolderUrl(folderToken));
        r.setHasChildren(true);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("folder_token", folderToken);
        r.setMetadata(meta);
        return r;
    }

    /**
     * 把列表结果里的一个文件
     * 转成选择器 Resource。
     *
     * <p>ParentID 必须与父文件夹的 ExternalID 一致：根文件夹的 ExternalID 是裸的
     * rootFolderToken（见 {@link #driveFolderToResource}），而任何子文件夹的 ExternalID
     * 是 {@code "rootFolderToken:folderToken"}。根的直接子项的
     * {@code parentToken} 就是裸根 token，所以它们的 ParentID 是裸根；
     * 更深的层用编码形式。</p>
     */
    Resource driveFileToResource(String rootFolderToken, DriveFile file) {
        String name = file.getName();
        if (name.isEmpty()) {
            name = file.getToken();
        }

        OffsetDateTime modifiedAt = FeishuSupport.parseFeishuTimestamp(file.getModifiedTime());

        String parentId = makeDriveResourceId(rootFolderToken, file.getParentToken());
        if (file.getParentToken().equals(rootFolderToken) || file.getParentToken().isEmpty()) {
            // 根文件夹的直接子项：父就是根，其 ExternalID 是裸的 rootFolderToken。
            parentId = rootFolderToken;
        }

        Resource r = new Resource();
        r.setExternalId(makeDriveResourceId(rootFolderToken, file.getToken()));
        r.setName(name);
        r.setType(file.getType());
        r.setUrl(file.getUrl());
        r.setParentId(parentId);
        r.setHasChildren("folder".equals(file.getType()));
        r.setModifiedAt(FeishuSupport.orGoZero(modifiedAt));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("file_token", file.getToken());
        meta.put("obj_type", file.getType());
        meta.put("folder_token", file.getParentToken());
        r.setMetadata(meta);
        return r;
    }

    /**
     * 把云盘列举失败转成错误 FetchedItem，
     * 让同步日志能指出哪些子文件夹没列成。与 wiki 的同语义辅助相对应。
     */
    static List<FetchedItem> appendDriveFileListFailureItems(List<FetchedItem> items,
                                                             String resourceId, String channel,
                                                             List<DriveFileListFailure> failures) {
        for (DriveFileListFailure failure : failures) {
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("channel", channel);
            extra.put("folder_token", failure.folderToken());
            extra.put("failure_stage", "list_children");

            FetchedItem item = new FetchedItem();
            item.setExternalId(failure.folderToken());
            item.setTitle(failure.folderToken());
            item.setSourceResourceId(resourceId);
            item.setMetadata(FeishuErrors.feishuErrorItemMeta(failure.err(), extra));
            items.add(item);
        }
        return items;
    }

    private static String messageOf(RuntimeException e) {
        return e == null || e.getMessage() == null ? "" : e.getMessage();
    }
}
