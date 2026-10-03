package com.ragagent.datasource.connector.ima;

import com.ragagent.common.web.JsonMappers;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.connector.ima.ImaApiTypes.FolderInfo;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetAddableKnowledgeBaseListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetKnowledgeListResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.GetMediaInfoResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.KnowledgeBaseInfo;
import com.ragagent.datasource.connector.ima.ImaApiTypes.KnowledgeInfo;
import com.ragagent.datasource.connector.ima.ImaApiTypes.SearchKnowledgeBaseResp;
import com.ragagent.datasource.connector.ima.ImaApiTypes.SearchedKnowledgeBaseInfo;
import com.ragagent.datasource.connector.ima.ImaApiTypes.WalkedFile;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 腾讯 IMA（ima.qq.com）数据源连接器。
 *
 * <h2>身份契约：逻辑键而不是 media_id</h2>
 * <p>每个条目由 {@link ImaFormats#logicalKey} 算出的稳定逻辑键标识，<b>不是</b>
 * {@code media_id}——IMA 在同名文件被就地替换时会重新分配 {@code media_id}，
 * 而我们希望那种替换表现为对同一条知识条目的**更新**，而不是"删一条 + 加一条"。</p>
 *
 * <p>每轮发出：</p>
 * <ul>
 *   <li>逻辑键上次没有 → add（新内容）；</li>
 *   <li>逻辑键在、{@code media_id} 变了 → update（重抓内容；ingest 层用
 *       已有的 external_id 命中"删除后重建"路径）；</li>
 *   <li>逻辑键与 media_id 都没变 → skip；</li>
 *   <li>上次在、这次不在 → {@code IsDeleted} 墓碑。</li>
 * </ul>
 *
 * <p><b>已知限制</b>：IMA 至今不暴露逐条目的 {@code updated_at}，
 * 所以"就地编辑但 {@code media_id} 不变"对我们是不可见的。需要整份内容刷新的
 * 用户应当周期性跑一次全量同步。</p>
 *
 * <h2>取消与超时</h2>
 * <p>取消靠线程中断，退避走
 * {@link Connector#sleep(long)}（被中断时抛 {@link ConnectorException}）。
 * 请求级超时落在两个 HTTP 客户端的构造参数上
 * （60s / 120s）。</p>
 *
 * <h2>确定性的墓碑顺序</h2>
 * <p>删除检测的墓碑按<b>逻辑键升序</b>
 * 发出，保证同一次同步的结果可复现。</p>
 */
public class ImaConnector implements Connector {

    private static final Logger log = LoggerFactory.getLogger(ImaConnector.class);

    /**
     * 解析 {@code knowledge_list} 里的松散条目。
     *
     * <p>必须容忍未知属性：IMA 会在
     * 这些对象上继续加字段，严格模式会让整条列表解析失败。</p>
     */
    private static final ObjectMapper MAPPER = JsonMappers.lenient()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ImaRetryPolicy retryPolicy;

    public ImaConnector() {
        this(ImaRetryPolicy.defaults());
    }

    /** 测试注入用：把重试退避压到 0（见 {@link ImaRetryPolicy#immediate()}）。 */
    ImaConnector(ImaRetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy == null ? ImaRetryPolicy.defaults() : retryPolicy;
    }

    @Override
    public String type() {
        return DataSourceConstants.CONNECTOR_TYPE_IMA;
    }

    /**
     * 调 {@code get_addable_knowledge_base_list} 验凭据
     * ——即使 token 名下零个 KB 也最容易成功的端点，且 {@code ListResources} 用的就是它。
     * token 本身无效时它返回 {@code 110030}（无权限），客户端已把该码映射成
     * {@link ConnectorException.InvalidCredentials}。
     *
     * <p>外层包装 {@code "ima connection failed: ..."}：<b>文本保持原样</b>。
     * 分类信息由<b>异常链</b>承载（{@code getCause()}），而不是包装后的类型——
     * 这是本模块唯一一处"类型判定要往 cause 上找"的地方。</p>
     */
    @Override
    public void validate(DataSourceConfig config) {
        ImaConfig cfg = ImaConfig.parse(config);
        try {
            newClient(cfg).getAddableKnowledgeBaseList("", ImaClient.DEFAULT_PAGE_SIZE);
        } catch (ConnectorException e) {
            throw new ConnectorException("ima connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * IMA 的知识库是<b>扁平</b>的顶层资源列表，
     * 惰性加载的选择器没有祖先可揭示，回空列表。
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        return new ArrayList<>();
    }

    /**
     * 返回该 token 可读的知识库扁平列表。
     *
     * <p>{@code parentId} 只用于满足"没有子项"的契约：非空即回空列表。</p>
     *
     * <p>主来源是 {@code get_addable_knowledge_base_list}；当它返回空列表时
     * （例如老租户只暴露了读权限）回落到空 query 的 {@code search_knowledge_base}，
     * 让用户仍能挑到东西。</p>
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        if (parentId != null && !parentId.isEmpty()) {
            return new ArrayList<>();
        }

        ImaConfig cfg = ImaConfig.parse(config);
        ImaClient cli = newClient(cfg);

        List<KbLite> bases = new ArrayList<>();
        String cursor = "";
        while (true) {
            GetAddableKnowledgeBaseListResp resp;
            try {
                resp = cli.getAddableKnowledgeBaseList(cursor, ImaClient.DEFAULT_PAGE_SIZE);
            } catch (ConnectorException e) {
                throw new ConnectorException("get_addable_knowledge_base_list: " + e.getMessage(), e);
            }
            if (resp.getAddableKnowledgeBaseList() != null) {
                for (ImaApiTypes.AddableKnowledgeBaseInfo b : resp.getAddableKnowledgeBaseList()) {
                    bases.add(new KbLite(b.getId(), b.getName(), ""));
                }
            }
            if (resp.isEnd() || resp.getNextCursor().isEmpty()) {
                break;
            }
            cursor = resp.getNextCursor();
        }
        log.info("[IMA] get_addable_knowledge_base_list returned {} knowledge bases", bases.size());

        if (bases.isEmpty()) {
            cursor = "";
            while (true) {
                SearchKnowledgeBaseResp resp;
                try {
                    resp = cli.searchKnowledgeBase("", cursor, ImaClient.SEARCH_PAGE_SIZE);
                } catch (ConnectorException e) {
                    throw new ConnectorException("search_knowledge_base fallback: " + e.getMessage(), e);
                }
                if (resp.getInfoList() != null) {
                    for (SearchedKnowledgeBaseInfo b : resp.getInfoList()) {
                        // 字段一一对应，CoverURL 一并带过来。
                        bases.add(new KbLite(b.getId(), b.getName(), b.getCoverUrl()));
                    }
                }
                if (resp.isEnd() || resp.getNextCursor().isEmpty()) {
                    break;
                }
                cursor = resp.getNextCursor();
            }
            log.info("[IMA] search_knowledge_base fallback returned {} knowledge bases", bases.size());
        }

        List<String> ids = new ArrayList<>(bases.size());
        for (KbLite b : bases) {
            ids.add(b.id());
        }
        Map<String, KnowledgeBaseInfo> details = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += 20) {
            int end = Math.min(i + 20, ids.size());
            Map<String, KnowledgeBaseInfo> batch;
            try {
                batch = cli.getKnowledgeBase(ids.subList(i, end));
            } catch (ConnectorException e) {
                log.warn("[IMA] get_knowledge_base batch failed (skipping enrichment): {}",
                        e.getMessage());
                continue;
            }
            details.putAll(batch);
        }

        List<Resource> out = new ArrayList<>(bases.size());
        for (KbLite b : bases) {
            String desc = "";
            String coverUrl = b.coverUrl();
            KnowledgeBaseInfo d = details.get(b.id());
            if (d != null) {
                desc = d.getDescription();
                if (coverUrl.isEmpty()) {
                    coverUrl = d.getCoverUrl();
                }
            }
            Resource r = new Resource();
            r.setExternalId(b.id());
            r.setName(b.name());
            r.setType("knowledge_base");
            r.setDescription(desc);
            r.setUrl(cfg.baseURL());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("cover_url", coverUrl);
            r.setMetadata(metadata);
            out.add(r);
        }
        out.sort(Comparator.comparing(Resource::getExternalId));
        log.info("[IMA] ListResources returning {} knowledge bases to UI", out.size());
        return out;
    }

    /** 知识库的最小信息（id + 名字 + 封面）。 */
    private record KbLite(String id, String name, String coverUrl) {
    }

    /** 全量同步指定知识库（cursor 不参与）。 */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        return walk(config, resourceIds, null, false).items();
    }

    /**
     * 按 cursor 抓取新增 / 替换 / 删除的条目。
     *
     * <p>没有配置任何知识库 id 时直接报错
     * （{@code "no resource IDs (knowledge base IDs) configured"}），<b>不</b>回落到
     * "全部"——这与 RSS 的 walk 是相反处置，别统一。</p>
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        List<String> resourceIds = config == null ? null : config.getResourceIds();
        if (resourceIds == null || resourceIds.isEmpty()) {
            throw new ConnectorException("no resource IDs (knowledge base IDs) configured");
        }

        ImaCursor prev = null;
        if (cursor != null && cursor.getConnectorCursor() != null) {
            prev = ImaCursor.fromConnectorCursor(cursor.getConnectorCursor());
        }

        WalkResult result = walk(config, resourceIds, prev, true);

        SyncCursor newCursor = new SyncCursor();
        newCursor.setLastSyncTime(result.cursor().getLastSyncTime());
        newCursor.setConnectorCursor(result.cursor().toConnectorCursor());
        return new FetchIncrementalResult(result.items(), newCursor);
    }

    /** 一次 walk 的结果：条目 + 新游标。 */
    private record WalkResult(List<FetchedItem> items, ImaCursor cursor) {
    }

    /**
     * {@code FetchAll} / {@code FetchIncremental} 的共享实现。
     * {@code incremental} 为 false 时 {@code prev} 被忽略、返回的 cursor 无意义。
     */
    private WalkResult walk(DataSourceConfig config, List<String> resourceIds,
                            ImaCursor prev, boolean incremental) {
        ImaConfig cfg = ImaConfig.parse(config);
        ImaClient cli = newClient(cfg);

        ImaCursor newCursor = new ImaCursor();
        newCursor.setLastSyncTime(OffsetDateTime.now());
        newCursor.setKbLogical(new LinkedHashMap<>());

        List<FetchedItem> out = new ArrayList<>();
        List<String> ids = resourceIds == null ? List.of() : resourceIds;

        for (String kbId : ids) {
            List<WalkedFile> files;
            Map<String, String> folderPath;
            try {
                folderPath = new LinkedHashMap<>();
                files = listAllKbFiles(cli, kbId, folderPath);
            } catch (ConnectorException e) {
                throw new ConnectorException("list KB " + kbId + ": " + e.getMessage(), e);
            }

            // present 装本轮 IMA 列出的全部逻辑键（用于删除检测）；
            // syncedLogical 只装本轮**真正解析成功**的键，它是落进 cursor 的那份，
            // 于是临时失败下一轮会被重试，而不是被记成"未变"。
            Set<String> present = new LinkedHashSet<>();
            Map<String, String> syncedLogical = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>(files.size());
            for (WalkedFile f : files) {
                String key = ImaFormats.logicalKey(kbId, f.getParentFolderId(), f.getTitle());
                keys.add(key);
                present.add(key);
            }
            newCursor.getKbLogical().put(kbId, syncedLogical);

            int replaced = 0;
            int failed = 0;
            for (int i = 0; i < files.size(); i++) {
                WalkedFile f = files.get(i);
                String key = keys.get(i);

                // 增量跳过 / 替换检测。
                if (incremental && prev != null && prev.getKbLogical() != null) {
                    Map<String, String> prevSet = prev.getKbLogical().get(kbId);
                    if (prevSet != null) {
                        String prevMediaId = prevSet.get(key);
                        if (prevMediaId != null) {
                            if (prevMediaId.equals(f.getMediaId())) {
                                // 未变：把条目带进新 cursor，下一轮同样不必再抓。
                                syncedLogical.put(key, f.getMediaId());
                                continue;
                            }
                            replaced++; // media_id 变了 → 替换，继续往下重抓
                        }
                    }
                }

                FetchOne fetched = fetchOneMedia(cli, kbId, key, f, folderPath);
                FetchedItem item = fetched.item();
                FetchOutcome outcome = fetched.outcome();
                switch (outcome) {
                    case OK -> {
                        syncedLogical.put(key, f.getMediaId());
                        out.add(item);
                    }
                    case SKIPPED ->
                        // 确定性跳过（不支持的类型、没有 URL）：记下来，
                        // 免得未来每次同步都白付一次 get_media_info。
                        syncedLogical.put(key, f.getMediaId());
                    case FAILED ->
                        // 临时失败：不进 cursor（下一轮重试）。
                        // present 里仍然有它，所以不会被误判成删除。
                        failed++;
                }
            }

            // 删除检测（仅增量）——依据是逻辑键从列表里消失，所以同名替换
            // （media_id 变了但逻辑键还在）与下载失败都不会误报删除。
            int deleted = 0;
            if (incremental && prev != null && prev.getKbLogical() != null) {
                Map<String, String> prevSet = prev.getKbLogical().get(kbId);
                if (prevSet != null) {
                    List<String> prevKeys = new ArrayList<>(prevSet.keySet());
                    prevKeys.sort(Comparator.naturalOrder());
                    for (String prevKey : prevKeys) {
                        if (!present.contains(prevKey)) {
                            FetchedItem tombstone = new FetchedItem();
                            tombstone.setExternalId(prevKey);
                            tombstone.setDeleted(true);
                            tombstone.setSourceResourceId(kbId);
                            out.add(tombstone);
                            deleted++;
                        }
                    }
                }
            }

            log.info("[IMA] KB {}: total={} replaced={} failed={} deleted={}",
                    kbId, files.size(), replaced, failed, deleted);
        }

        if (!incremental) {
            return new WalkResult(out, null);
        }
        return new WalkResult(out, newCursor);
    }

    // ── 知识库枚举 ────────────────────────────────────────────────────────

    /**
     * 把一个知识库递归摊平成条目列表，
     * 同时用现场构建的文件夹树解析出每个条目的文件夹路径。
     * {@code folderPath} 是出参（folder_id → 可读路径），只为元数据服务。
     */
    private List<WalkedFile> listAllKbFiles(ImaClient cli, String kbId, Map<String, String> folderPath) {
        List<WalkedFile> out = new ArrayList<>();

        // 文件夹上的 DFS/BFS。从根（空 folder_id）开始。
        record Todo(String folderId, String path) {
        }
        List<Todo> stack = new ArrayList<>();
        stack.add(new Todo("", ""));

        while (!stack.isEmpty()) {
            Todo cur = stack.remove(stack.size() - 1);

            String cursor = "";
            while (true) {
                GetKnowledgeListResp resp = cli.getKnowledgeList(
                        kbId, cur.folderId(), cursor, ImaClient.DEFAULT_PAGE_SIZE);
                if (resp.getKnowledgeList() != null) {
                    for (JsonNode raw : resp.getKnowledgeList()) {
                        // 逐项探测：带非空 folder_id 的是文件夹，否则是知识条目
                        //（文件 / 笔记 / ...）。
                        String probeFolderId = text(raw, "folder_id");
                        String probeMediaId = text(raw, "media_id");

                        if (!probeFolderId.isEmpty() && probeMediaId.isEmpty()) {
                            FolderInfo fi;
                            try {
                                fi = MAPPER.treeToValue(raw, FolderInfo.class);
                            } catch (RuntimeException | java.io.IOException e) {
                                continue;
                            }
                            String child = cur.path().isEmpty()
                                    ? fi.getName()
                                    : cur.path() + "/" + fi.getName();
                            folderPath.put(fi.getFolderId(), child);
                            stack.add(new Todo(fi.getFolderId(), child));
                            continue;
                        }
                        if (probeMediaId.isEmpty()) {
                            continue; // 形状不认识，防御性跳过
                        }
                        KnowledgeInfo ki;
                        try {
                            ki = MAPPER.treeToValue(raw, KnowledgeInfo.class);
                        } catch (RuntimeException | java.io.IOException e) {
                            continue;
                        }
                        WalkedFile walked = new WalkedFile();
                        walked.setMediaId(ki.getMediaId());
                        walked.setTitle(ki.getTitle());
                        walked.setParentFolderId(ki.getParentFolderId());
                        walked.setMediaType(ki.getMediaType());
                        walked.setFolderPath(cur.path());
                        out.add(walked);
                    }
                }
                if (resp.isEnd() || resp.getNextCursor().isEmpty()) {
                    break;
                }
                cursor = resp.getNextCursor();
            }
        }
        return out;
    }

    /** 从松散 JSON 里取文本字段：形状不对时回空串。 */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return "";
        }
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() ? "" : v.asText();
    }

    // ── 单条目抓取 ────────────────────────────────────────────────────────

    /**
     * {@code fetchOneMedia} 的三种结局，
     * 因为调用方在构建 cursor 时必须区别对待——只有**临时失败**必须留在
     * cursor 之外，以便下一轮重试。
     */
    enum FetchOutcome {
        /** 条目已解析出来，应当 ingest。 */
        OK,
        /** IMA 给不出这个条目的内容、且永远给不出（不支持的类型、没有 URL）。确定性，可以记进 cursor。 */
        SKIPPED,
        /** 临时错误（API 调用或下载）。必须重试。 */
        FAILED
    }

    /** 单条目抓取结果：条目 + 结局。 */
    private record FetchOne(FetchedItem item, FetchOutcome outcome) {
    }

    /**
     * 对单个条目调 {@code get_media_info}，
     * 有可能时下载正文。
     *
     * <p>{@code externalId} 是调用方算好的稳定逻辑键——跨同步不变，即使 IMA
     * 因同名替换换了 {@code media_id}；原始 {@code media_id} 仍留在 metadata 里
     * 供排障。</p>
     */
    private FetchOne fetchOneMedia(ImaClient cli, String kbId, String externalId,
                                   WalkedFile f, Map<String, String> folderPath) {
        GetMediaInfoResp info;
        try {
            info = cli.getMediaInfo(f.getMediaId());
        } catch (ConnectorException e) {
            log.warn("[IMA] get_media_info({}) failed, will retry next sync: {}",
                    f.getMediaId(), e.getMessage());
            return new FetchOne(null, FetchOutcome.FAILED);
        }

        if (info.getMediaType() == ImaFormats.MEDIA_TYPE_NOTE) {
            return fetchNote(cli, kbId, externalId, f, folderPath, info);
        }

        if (ImaFormats.isSkippableMediaType(info.getMediaType())) {
            log.info("[IMA] skip media {} (title=\"{}\" media_type={}): unsupported by the IMA OpenAPI",
                    f.getMediaId(), f.getTitle(), info.getMediaType());
            return new FetchOne(null, FetchOutcome.SKIPPED);
        }

        if (info.getUrlInfo().getUrl().isEmpty()) {
            log.warn("[IMA] media {} (title=\"{}\") has no url_info.url, skipping",
                    f.getMediaId(), f.getTitle());
            return new FetchOne(null, FetchOutcome.SKIPPED);
        }

        String ext = ImaFormats.extensionForMediaType(info.getMediaType());

        // 没有固定扩展名的媒体（网页、公众号文章……）通常以**裸 URL** 交给 ingest 层，
        // 让 WeKnora 自己去抓。这只有对公网可达的链接才成立：IMA 附带鉴权头时
        // URL 指向 IMA 自己的存储，WeKnora 的抓取（带不了那些头）会被拒。
        // 那就这里下载——头在我们手上——并从响应反推扩展名。
        if (ext.isEmpty()) {
            if (info.getUrlInfo().getHeaders() == null || info.getUrlInfo().getHeaders().isEmpty()) {
                FetchedItem item = new FetchedItem();
                item.setExternalId(externalId);
                item.setTitle(f.getTitle());
                item.setUrl(info.getUrlInfo().getUrl());
                item.setSourceResourceId(kbId);
                item.setUpdatedAt(OffsetDateTime.now());
                item.setMetadata(baseMetadata(externalId, f, folderPath, info, kbId));
                return new FetchOne(item, FetchOutcome.OK);
            }
            ext = "html";
        }

        ImaClient.DownloadResult download;
        try {
            download = cli.downloadUrl(info.getUrlInfo());
        } catch (ConnectorException e) {
            log.warn("[IMA] download media {} (title=\"{}\") failed, will retry next sync: {}",
                    f.getMediaId(), f.getTitle(), e.getMessage());
            return new FetchOne(null, FetchOutcome.FAILED);
        }

        byte[] body = download.body();
        String contentType = download.contentType();

        // IMA 把所有图片都标成 media_type=9，所以响应自己的 Content-Type
        // 只要指出真实格式，就优先于 media_type 的默认值。
        String detected = ImaFormats.extensionForContentType(contentType);
        if (!detected.isEmpty()) {
            ext = detected;
        }
        if (contentType == null || contentType.isEmpty()
                || contentType.startsWith("application/octet-stream")) {
            contentType = ImaFormats.mimeForExtension(ext);
        }

        String fileName = ImaFormats.sanitizeFileName(f.getTitle());
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith("." + ext)) {
            fileName = fileName + "." + ext;
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(externalId);
        item.setTitle(f.getTitle());
        item.setContent(body);
        item.setContentType(contentType);
        item.setFileName(fileName);
        item.setUrl(info.getUrlInfo().getUrl());
        item.setUpdatedAt(OffsetDateTime.now());
        item.setSourceResourceId(kbId);
        item.setMetadata(baseMetadata(externalId, f, folderPath, info, kbId));
        return new FetchOne(item, FetchOutcome.OK);
    }

    /**
     * 解析 IMA 笔记（{@code media_type=11}）。
     *
     * <p>正文以纯文本返回，但按 Markdown ingest——IMA 笔记是富文本写的，
     * 导出保留标题与列表标记，当 Markdown 解析能把结构留给分块，
     * 而没有标记的纯文本也不受影响。</p>
     */
    private FetchOne fetchNote(ImaClient cli, String kbId, String externalId,
                               WalkedFile f, Map<String, String> folderPath, GetMediaInfoResp info) {
        String noteId = info.getNotebookExtInfo().getNotebookId();
        if (noteId.isEmpty()) {
            log.warn("[IMA] note {} (title=\"{}\") has no notebook_id, skipping", f.getMediaId(), f.getTitle());
            return new FetchOne(null, FetchOutcome.SKIPPED);
        }

        String content;
        try {
            content = cli.getNoteContent(noteId);
        } catch (ConnectorException e) {
            log.warn("[IMA] get_doc_content(note {}, title=\"{}\") failed, will retry next sync: {}",
                    noteId, f.getTitle(), e.getMessage());
            return new FetchOne(null, FetchOutcome.FAILED);
        }
        if (ImaFormats.isGoBlank(content)) {
            log.info("[IMA] note {} (title=\"{}\") is empty, skipping", noteId, f.getTitle());
            return new FetchOne(null, FetchOutcome.SKIPPED);
        }

        String fileName = ImaFormats.sanitizeFileName(f.getTitle());
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            fileName += ".md";
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(externalId);
        item.setTitle(f.getTitle());
        item.setContent(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        item.setContentType("text/markdown");
        item.setFileName(fileName);
        item.setSourceResourceId(kbId);
        item.setUpdatedAt(OffsetDateTime.now());
        item.setMetadata(baseMetadata(externalId, f, folderPath, info, kbId));
        return new FetchOne(item, FetchOutcome.OK);
    }

    /**
     * 每个 ingest 条目都要带上的元数据。
     *
     * <p>{@code externalID}（调用方的逻辑键）也存进去，运维就能把稳定身份
     * 对回原始 {@code media_id}——排查"同名替换为什么表现为更新而不是新增"时很有用。</p>
     *
     * <p>键集合：恒有 {@code channel/media_id/ima_logical_key/
     * knowledge_base_id/folder_path/media_type} 六项；{@code parent_folder_id}
     * 仅在非空时加；{@code folder_path} 会被文件夹树解析出的路径**覆盖**；
     * {@code notebook_id} 仅在非空时加。</p>
     */
    private static Map<String, String> baseMetadata(String externalId, WalkedFile f,
                                                    Map<String, String> folderPath,
                                                    GetMediaInfoResp info, String kbId) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("channel", "ima");
        m.put("media_id", f.getMediaId());
        m.put("ima_logical_key", externalId);
        m.put("knowledge_base_id", kbId);
        m.put("folder_path", f.getFolderPath());
        m.put("media_type", Integer.toString(info.getMediaType()));
        if (!f.getParentFolderId().isEmpty()) {
            m.put("parent_folder_id", f.getParentFolderId());
        }
        String fp = folderPath.get(f.getParentFolderId());
        if (fp != null && !fp.isEmpty()) {
            m.put("folder_path", fp);
        }
        if (!info.getNotebookExtInfo().getNotebookId().isEmpty()) {
            m.put("notebook_id", info.getNotebookExtInfo().getNotebookId());
        }
        return m;
    }

    /** 连接器构造客户端的唯一入口，测试可覆盖以注入重试预算。 */
    ImaClient newClient(ImaConfig cfg) {
        return new ImaClient(cfg, retryPolicy);
    }
}
