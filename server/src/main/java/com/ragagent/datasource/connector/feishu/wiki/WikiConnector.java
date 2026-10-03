package com.ragagent.datasource.connector.feishu.wiki;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.PartialWikiNodeListException;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNode;
import com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiNodeListFailure;
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
 * 飞书 wiki 连接器。
 *
 * <p>同一份代码同时服务飞书与 Lark：两朵云的 wiki/docx/drive API 面<b>完全一致</b>，
 * 由 {@link FeishuRegion} 选云。</p>
 *
 * <h2>惰性加载（Tencent/WeKnora#1672 的修复）</h2>
 * <p>{@link #listResources} 一次只加载<b>一层</b>：过去在这里就递归整棵树，
 * 大 wiki 会超时；递归现在只发生在同步时（{@code listWikiNodesRecursiveFrom}）。</p>
 *
 * <h2>资源 ID 编码</h2>
 * <pre>
 *   parentId == ""                    → 列出全部可访问的 wiki 空间
 *   parentId == "spaceID"             → 该空间的顶层节点
 *   parentId == "spaceID:nodeToken"   → 该节点的直接子节点
 * </pre>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li>钩子 / 关联预加载 / 软删除 / 默认排序 / 唯一索引 / 自动时间戳：<b>全无</b>——
 *       本连接器不碰数据库，只与飞书 API 和领域对象打交道。</li>
 * </ol>
 */
public class WikiConnector implements StreamingConnector {

    private static final Logger log = LoggerFactory.getLogger(WikiConnector.class);

    private final FeishuRegion region;

    /** @param region 部署区域（决定 connector type 与 URL host）。 */
    public WikiConnector(FeishuRegion region) {
        this.region = region;
    }

    public FeishuRegion region() {
        return region;
    }

    /** 连接器类型（feishu / lark）。 */
    @Override
    public String type() {
        return region.connectorType();
    }

    /** 真实连一次飞书验活。 */
    @Override
    public void validate(DataSourceConfig config) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        try {
            client.ping();
        } catch (RuntimeException e) {
            throw new ConnectorException("feishu connection failed: " + messageOf(e), e);
        }
    }

    /**
     * 给选择器列出可同步的资源，
     * <b>一次只加载一层</b>，避免提前遍历整棵 wiki。
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        String parent = parentId == null ? "" : parentId;

        if (parent.isEmpty()) {
            List<com.ragagent.datasource.connector.feishu.core.FeishuApiTypes.WikiSpace> spaces;
            try {
                spaces = client.listWikiSpaces();
            } catch (RuntimeException e) {
                throw new ConnectorException("list feishu wiki spaces: " + messageOf(e), e);
            }

            List<Resource> resources = new ArrayList<>(spaces.size());
            for (var space : spaces) {
                Resource r = new Resource();
                r.setExternalId(space.spaceId());
                r.setName(space.name());
                r.setType("wiki_space");
                r.setDescription(space.description());
                r.setUrl(region.wikiUrl(space.spaceId()));
                r.setHasChildren(true);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("visibility", space.visibility());
                meta.put("space_id", space.spaceId());
                r.setMetadata(meta);
                resources.add(r);
            }
            return resources;
        }

        // 惰性加载：只列给定空间/节点的直接子项。
        String[] parts = parseWikiResourceId(parent);
        String spaceId = parts[0];
        String nodeToken = parts[1];
        List<WikiNode> nodes;
        try {
            nodes = client.listWikiNodes(spaceId, nodeToken);
        } catch (RuntimeException e) {
            throw new ConnectorException(
                    "list feishu wiki nodes under " + parent + ": " + messageOf(e), e);
        }

        List<Resource> resources = new ArrayList<>(nodes.size());
        for (WikiNode node : nodes) {
            resources.add(wikiNodeToResource(spaceId, node));
        }
        return resources;
    }

    /**
     * 返回"为了让惰性选择器展开到某个
     * 已存在的选中项、必须去加载其直接子项"的全部祖先资源 ID。
     *
     * <p>对一个选中的 {@code "spaceID:nodeToken"}，就是它的空间加上树上每一级中间节点；
     * 上溯走单节点查询（{@code parent_node_token}），每个选中项 O(depth)，
     * 因此永远不会重新遍历整棵 wiki。</p>
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);

        Set<String> seen = new LinkedHashSet<>();
        List<String> ancestors = new ArrayList<>();

        for (String rid : resourceIds == null ? List.<String>of() : resourceIds) {
            String[] parts = parseWikiResourceId(rid);
            String spaceId = parts[0];
            String nodeToken = parts[1];
            if (spaceId.isEmpty() || nodeToken.isEmpty()) {
                // 空间级选中项在选择器里已经是顶层节点了，上面没有可展开的。
                continue;
            }
            // 要展开空间的直接子项，才能露出那个顶层节点。
            addAncestor(seen, ancestors, spaceId);

            // 从选中项往上走到顶，逐级加载中间父节点，让"到选中项的那条路径"可见。
            String current = nodeToken;
            while (!current.isEmpty()) {
                WikiNode node;
                try {
                    node = client.getWikiNode(spaceId, current);
                } catch (RuntimeException e) {
                    // 尽力而为：一条断掉的路径就保持折叠，其它选中项照常展开。
                    log.warn("[Feishu] resolve ancestors: get node {}:{}: {}", spaceId, current,
                            messageOf(e));
                    break;
                }
                if (node.getParentNodeId().isEmpty()) {
                    break;
                }
                addAncestor(seen, ancestors, makeWikiNodeResourceId(spaceId, node.getParentNodeId()));
                current = node.getParentNodeId();
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
     * 全量同步指定 wiki 空间下的全部文档。
     *
     * <p>是防御性回落路径——连接器实现了 {@link StreamingConnector}，
     * service 会优先调 {@link #fetchStream}。</p>
     */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        return SyncEngine.fetchAllEngine(client, config, resourceIds, new WikiOps(region));
    }

    /**
     * 对比节点编辑时间与上次记录做增量同步。
     *
     * <p>同样路由到共享引擎，所以 "#2136：失败不推进游标" 的语义在这里也成立。</p>
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        WikiOps ops = new WikiOps(region);
        if (config.getResourceIds() == null || config.getResourceIds().isEmpty()) {
            throw new ConnectorException(ops.emptyResourceIdsError());
        }
        return SyncEngine.fetchIncrementalEngine(client, config, cursor, ops);
    }

    /**
     * 可续跑、内存有界的同步。
     *
     * <p>把全量与增量统一成一条路径：{@code cursor == null} 时抓全部，有游标时跳过
     * 编辑时间未变的节点——正是这套机制让"遍历中途超时的同步"能从最后一个检查点续跑，
     * 而不是从头再来（Tencent/WeKnora#2136）。</p>
     */
    @Override
    public SyncCursor fetchStream(DataSourceConfig config, SyncCursor cursor, StreamHandler handler) {
        FeishuConfig feishuConfig = FeishuSupport.parseFeishuConfig(config, region);
        FeishuClient client = new FeishuClient(feishuConfig);
        WikiOps ops = new WikiOps(region);
        if (config.getResourceIds() == null || config.getResourceIds().isEmpty()) {
            throw new ConnectorException(ops.emptyResourceIdsError());
        }
        return SyncEngine.fetchStreamEngine(client, config, cursor, handler, ops);
    }

    // ──────────────────────────────────────────────────────────────────
    // wikiOps：把 wiki 连接器适配到共享同步引擎
    // ──────────────────────────────────────────────────────────────────

    /**
     * 携带 region（用于渲染 URL），并负责
     * 编解码 wiki 的游标线格式（{@code space_node_times}），
     * 让引擎保持格式无关。
     */
    static final class WikiOps implements SyncEngine.NodeOps<WikiNode> {

        private final FeishuRegion region;

        WikiOps(FeishuRegion region) {
            this.region = region;
        }

        @Override
        public SyncEngine.NodeOps.ListResult<WikiNode> list(FeishuClient client, String resourceId) {
            String[] parts = parseWikiResourceId(resourceId);
            try {
                List<WikiNode> nodes = client.listWikiNodesRecursiveFrom(parts[0], parts[1]);
                return SyncEngine.NodeOps.ListResult.ok(nodes);
            } catch (PartialWikiNodeListException partial) {
                // 部分列举：nodes 仍可用；失败的子树经 listFailureItems 暴露，同步继续。
                return SyncEngine.NodeOps.ListResult.partial(partial.getNodes(), partial);
            } catch (RuntimeException err) {
                return SyncEngine.NodeOps.ListResult.failed(err);
            }
        }

        @Override
        public String token(WikiNode n) {
            return n.getNodeToken();
        }

        @Override
        public String title(WikiNode n) {
            return n.getTitle();
        }

        @Override
        public String objType(WikiNode n) {
            return n.getObjType();
        }

        /**
         * 变更检测时间戳：{@code obj_edit_time}（文档<b>内容</b>），缺值时回落到
         * {@code node_edit_time}。它驱动游标比较，解析后也成为
         * {@code FetchedItem.UpdatedAt}（见 {@link #contentEditTime}），
         * 于是持久化的 {@code source_updated_at} 跟踪的是内容编辑而不是节点移动。
         */
        @Override
        public String editTime(WikiNode n) {
            if (!n.getObjEditTime().isEmpty()) {
                return n.getObjEditTime();
            }
            return n.getNodeEditTime();
        }

        @Override
        public List<FetchedItem> fetch(FeishuClient client, WikiNode n, String resourceId,
                                      boolean multimodal) {
            String[] parts = parseWikiResourceId(resourceId);
            return fetchNodeContent(client, n, parts[0], resourceId, multimodal, region);
        }

        @Override
        public List<FetchedItem> listFailureItems(String resourceId, RuntimeException partial) {
            String[] parts = parseWikiResourceId(resourceId);
            if (partial instanceof PartialWikiNodeListException pe) {
                return appendWikiNodeListFailureItems(new ArrayList<>(), parts[0], resourceId,
                        pe.getFailures());
            }
            return List.of();
        }

        @Override
        public String resourceNoun() {
            return "nodes";
        }

        @Override
        public String emptyResourceIdsError() {
            return "no resource IDs (wiki space IDs or wiki node IDs) configured";
        }

        @Override
        public String logTag() {
            return "[Feishu]";
        }

        /**
         * 从持久化的 cursor map 里取 {@code space_node_times}
         * （缺席时 {@code null}）。
         */
        @Override
        public Map<String, Map<String, String>> decodeCursorTimes(Map<String, Object> connectorCursor) {
            return FeishuCursorCodec.decodeSpaceNodeTimes(connectorCursor);
        }

        /** 编码 wiki 游标。 */
        @Override
        public SyncCursor encodeCursor(Map<String, Map<String, String>> times, OffsetDateTime lastSync) {
            return FeishuCursorCodec.encodeSpaceNodeTimes(times, lastSync);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 单节点抓取
    // ──────────────────────────────────────────────────────────────────

    /**
     * 抓一个 wiki 节点的内容并转成 FetchedItem 列表。
     *
     * <p>docx 节点会扇出成"主 Markdown 文档 + 可选附件子项"。按 obj_type 派发：</p>
     * <ul>
     *   <li>{@code docx} → blocks API（Markdown）+ 导出回落；可能带附件</li>
     *   <li>{@code doc}/{@code sheet}/{@code bitable} → 导出 API → 二进制文件</li>
     *   <li>{@code file} → 云盘下载 → 原始文件（PDF/Word/图片…）</li>
     *   <li>{@code mindnote}/{@code slides} → 跳过（没有 API）</li>
     * </ul>
     */
    static List<FetchedItem> fetchNodeContent(FeishuClient client, WikiNode node, String spaceId,
                                              String resourceId, boolean multimodalEnabled,
                                              FeishuRegion region) {
        if (!FeishuSupport.isSupportedDocType(node.getObjType())) {
            return List.of();
        }

        OffsetDateTime editTime = contentEditTime(node);
        OffsetDateTime createTime = contentCreateTime(node);
        Map<String, String> baseMeta = new LinkedHashMap<>();
        baseMeta.put("obj_token", node.getObjToken());
        baseMeta.put("obj_type", node.getObjType());
        baseMeta.put("node_token", node.getNodeToken());
        baseMeta.put("space_id", spaceId);
        baseMeta.put("creator", node.getCreator());
        baseMeta.put("owner", node.getOwner());
        baseMeta.put("channel", FeishuSupport.CHANNEL_FEISHU);

        switch (node.getObjType()) {
            case "docx": {
                DocxFetcher.DocxFetchInput in = new DocxFetcher.DocxFetchInput();
                in.docToken = node.getNodeToken();
                in.objToken = node.getObjToken();
                in.title = node.getTitle();
                in.url = region.wikiUrl(node.getNodeToken());
                in.resourceId = resourceId;
                in.editTime = editTime;
                in.createTime = createTime;
                in.baseMeta = baseMeta;
                in.multimodalEnabled = multimodalEnabled;
                return DocxFetcher.fetchDocxWithBlocks(client, in);
            }
            case "doc", "sheet", "bitable": {
                FetchedItem item = fetchViaExport(client, node, resourceId, editTime, baseMeta, region);
                item.setCreatedAt(createTime);
                return List.of(item);
            }
            case "file": {
                FetchedItem item = fetchDriveFile(client, node, resourceId, editTime, baseMeta, region);
                item.setCreatedAt(createTime);
                return List.of(item);
            }
            default:
                return List.of();
        }
    }

    /**
     * 经异步导出 API 导出 doc/sheet/bitable 节点，
     * 返回单个承载导出二进制的 FetchedItem。
     */
    private static FetchedItem fetchViaExport(FeishuClient client, WikiNode node, String resourceId,
                                              OffsetDateTime editTime, Map<String, String> baseMeta,
                                              FeishuRegion region) {
        FeishuClient.ExportDownload exported;
        try {
            exported = client.exportAndDownload(node.getObjToken(), node.getObjType());
        } catch (RuntimeException e) {
            throw new ConnectorException("export " + node.getTitle() + " (" + node.getObjType()
                    + "): " + messageOf(e), e);
        }

        // 保证文件名带正确扩展名
        String ext = FeishuConfig.EXPORT_FILE_EXT_TO_SUFFIX.get(
                FeishuConfig.OBJ_TYPE_TO_EXPORT_FILE_EXTENSION.get(node.getObjType()));
        String fileName = exported.fileName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = FeishuSupport.sanitizeFileName(node.getTitle()) + ext;
        } else if (!fileName.toLowerCase(Locale.ROOT).endsWith(ext)) {
            // 飞书常返回不带扩展名的文档标题——补上
            fileName = FeishuSupport.sanitizeFileName(fileName) + ext;
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(node.getNodeToken());
        item.setTitle(node.getTitle());
        item.setContent(exported.data());
        item.setContentType("application/octet-stream");
        item.setFileName(fileName);
        item.setUrl(region.wikiUrl(node.getNodeToken()));
        item.setUpdatedAt(editTime);
        item.setSourceResourceId(resourceId);
        item.setMetadata(baseMeta);
        return item;
    }

    /**
     * 从云盘下载用户上传的原始文件，
     * 返回单个承载原始字节的 FetchedItem。
     */
    private static FetchedItem fetchDriveFile(FeishuClient client, WikiNode node, String resourceId,
                                              OffsetDateTime editTime, Map<String, String> baseMeta,
                                              FeishuRegion region) {
        byte[] data;
        try {
            data = client.downloadDriveFile(node.getObjToken());
        } catch (RuntimeException e) {
            throw new ConnectorException("download file " + node.getTitle() + " ("
                    + node.getObjToken() + "): " + messageOf(e), e);
        }

        // 用节点标题当文件名；它通常保留了原始扩展名
        String fileName = node.getTitle();
        if (fileName.isEmpty()) {
            fileName = node.getObjToken();
        }

        FetchedItem item = new FetchedItem();
        item.setExternalId(node.getNodeToken());
        item.setTitle(node.getTitle());
        item.setContent(data);
        item.setContentType("application/octet-stream");
        item.setFileName(fileName);
        item.setUrl(region.wikiUrl(node.getNodeToken()));
        item.setUpdatedAt(editTime);
        item.setSourceResourceId(resourceId);
        item.setMetadata(baseMeta);
        return item;
    }

    /**
     * 文档最后一次<b>内容</b>编辑时间
     * （{@code obj_edit_time}），飞书省略时回落到节点属性编辑时间。
     *
     * <p>它才是 ingestion 持久化为 {@code source_updated_at} 的值；只用
     * {@code node_edit_time} 会在重命名或树上移动（内容没变）时就变动。</p>
     */
    static OffsetDateTime contentEditTime(WikiNode node) {
        OffsetDateTime t = FeishuSupport.parseFeishuTimestamp(node.getObjEditTime());
        if (t != null) {
            return t;
        }
        return FeishuSupport.orGoZero(FeishuSupport.parseFeishuTimestamp(node.getNodeEditTime()));
    }

    /** {@code obj_create_time}，回落 {@code node_create_time}。 */
    static OffsetDateTime contentCreateTime(WikiNode node) {
        OffsetDateTime t = FeishuSupport.parseFeishuTimestamp(node.getObjCreateTime());
        if (t != null) {
            return t;
        }
        return FeishuSupport.orGoZero(FeishuSupport.parseFeishuTimestamp(node.getNodeCreateTime()));
    }

    // ──────────────────────────────────────────────────────────────────
    // 辅助函数
    // ──────────────────────────────────────────────────────────────────

    /** 拼 {@code "spaceID:nodeToken"} 形式的资源 ID。 */
    static String makeWikiNodeResourceId(String spaceId, String nodeToken) {
        return spaceId + FeishuSupport.FEISHU_WIKI_NODE_RESOURCE_SEPARATOR + nodeToken;
    }

    /** 在<b>第一个</b>冒号处切分。 */
    static String[] parseWikiResourceId(String resourceId) {
        return FeishuSupport.cut(resourceId, FeishuSupport.FEISHU_WIKI_NODE_RESOURCE_SEPARATOR);
    }

    /** 把列表结果里的一个节点转成选择器 Resource。 */
    Resource wikiNodeToResource(String spaceId, WikiNode node) {
        String parentId;
        if (node.getParentNodeId().isEmpty()) {
            parentId = spaceId;
        } else {
            parentId = makeWikiNodeResourceId(spaceId, node.getParentNodeId());
        }

        String name = node.getTitle();
        if (name.isEmpty()) {
            name = node.getNodeToken();
        }

        OffsetDateTime modifiedAt = FeishuSupport.parseFeishuTimestamp(node.getObjEditTime());
        if (modifiedAt == null) {
            modifiedAt = FeishuSupport.parseFeishuTimestamp(node.getNodeEditTime());
        }

        Resource r = new Resource();
        r.setExternalId(makeWikiNodeResourceId(spaceId, node.getNodeToken()));
        r.setName(name);
        r.setType("wiki_node");
        r.setUrl(region.wikiUrl(node.getNodeToken()));
        r.setParentId(parentId);
        r.setHasChildren(node.isHasChild());
        r.setModifiedAt(FeishuSupport.orGoZero(modifiedAt));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("space_id", spaceId);
        meta.put("node_token", node.getNodeToken());
        meta.put("obj_token", node.getObjToken());
        meta.put("obj_type", node.getObjType());
        r.setMetadata(meta);
        return r;
    }

    /**
     * 把列举失败的子树转成<b>错误条目</b>，
     * 让同步日志能指出哪些子树没列成（而不是静默丢弃）。
     */
    static List<FetchedItem> appendWikiNodeListFailureItems(List<FetchedItem> items, String spaceId,
                                                            String resourceId,
                                                            List<WikiNodeListFailure> failures) {
        for (WikiNodeListFailure failure : failures) {
            WikiNode node = failure.node();
            String title = node.getTitle();
            if (title.isEmpty()) {
                title = node.getNodeToken();
            }
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("channel", FeishuSupport.CHANNEL_FEISHU);
            extra.put("node_token", node.getNodeToken());
            extra.put("space_id", spaceId);
            extra.put("failure_stage", "list_children");

            FetchedItem item = new FetchedItem();
            item.setExternalId(node.getNodeToken());
            item.setTitle(title);
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
