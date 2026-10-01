package com.ragagent.datasource.connector.notion;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.datasource.Connector;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.FetchedItem;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;

/**
 * Notion 数据源连接器（对照 Go {@code notion.Connector}，connector.go 全文）。
 *
 * <h2>它同步什么</h2>
 * <ul>
 *   <li><b>页面</b> → 一条 Markdown 知识条目（{@code page_id} 作 external_id），
 *       附件（pdf/file/video/audio，**不含 image**）各成一条；</li>
 *   <li><b>数据库 / 数据源</b> → 整张表合成<b>一条</b> Markdown 表格条目
 *       （{@code database} 作 external_id），每个记录若有页面正文，
 *       追加成 {@code ## <标题> 内容} 小节；</li>
 *   <li><b>数据库记录被单独选中</b>时（{@code fetchPage} 的 record 分支）
 *       走 {@code buildRecordItem}：属性列表 + 记录自身的块内容。</li>
 * </ul>
 *
 * <h2>三个同步入口的差异（别合并）</h2>
 * <table border="1">
 *   <tr><th></th><th>首次同步（cursor 为空）</th><th>后续增量</th></tr>
 *   <tr><td>{@code fetchAll}</td>
 *       <td colspan="2">遍历 {@code resourceIds}，先试 {@code GetPage}，
 *           失败按数据库处理</td></tr>
 *   <tr><td>{@code fetchIncremental}</td>
 *       <td>直接复用 {@code fetchAll}，再用条目的 UpdatedAt 建 cursor
 *           （**不做**一次额外的 Search）</td>
 *       <td>Search 全量发现 → BFS 收敛到选中根之下 → 与 cursor 差分 →
 *           只抓变化者 → 再检测删除</td></tr>
 * </table>
 *
 * <h2>"没选中的祖先"不算删除（本模块最容易做错的一条）</h2>
 * <p>增量同步的"删除检测"有<b>三个</b>跳过条件，缺一不可：</p>
 * <ol>
 *   <li>新 cursor 里有这个 page → 跳过（它还在）；</li>
 *   <li>{@code fetchVisited} 里有 → 跳过。这个 map 装的是
 *       {@code discoverAllResources} 返回的"**可见但不在任何选中根之下**"的集合
 *       ——也就是用户在 picker 里<b>主动取消勾选</b>的那些。它们仍在源站存在，
 *       报成"已删除"会把用户在 WeKnora 侧的文档删掉；</li>
 *   <li>剩下的才真的消失了 → 发 {@code IsDeleted} 条目。</li>
 * </ol>
 * <p>而"用户从没见过的页面"（上次配置保存之后新建的）<b>不在</b>排除集里，
 * 所以选中的父页面仍然会自动带上它们——见 {@code computeExcludedSet} 的注释。</p>
 *
 * <h2>已知差异（逐条都在测试里钉住）</h2>
 * <ol>
 *   <li><b>遍历顺序确定</b>：Go 在 {@code fetchIncremental} 的变更循环、
 *       删除循环、{@code discoverAllResources} 的收集循环里都 range **map**
 *       （顺序随机）→ 产出的 {@code changedItems} 顺序每次不同。Java 用
 *       {@code LinkedHashSet}/{@code LinkedHashMap} 保持"发现顺序"，
 *       结果稳定。信息量相同、且对增量同步是改进（Go 侧本来就无法逐字节复现）。</li>
 *   <li><b>{@code %w} 的类型与文本不可兼得</b>：Go 的
 *       {@code fmt.Errorf("search notion pages: %w", err)} 既拼文本又保留哨兵类型
 *       （{@code errors.Is} 仍能找出 {@code ErrInvalidCredentials}）。
 *       Java 的 {@link ConnectorException} 没有"带前缀的同一子类"构造器，
 *       故外层是裸 {@code ConnectorException}、类型信息落在 {@code cause} 链上。
 *       <b>消息逐字一致</b>（如 {@code "search notion pages: invalid credentials: …"}），
 *       但调用方要判类型必须走 cause 链。</li>
 * </ol>
 */
public final class NotionConnector implements Connector {

    private static final Logger log = LoggerFactory.getLogger(NotionConnector.class);

    /** 建客户端的接缝（测试注入无限流/零退避的客户端）。 */
    @FunctionalInterface
    public interface ClientFactory {
        NotionClient create(String token, String baseUrl);
    }

    private final ClientFactory clientFactory;

    /** 抓取协作者（构造期装配）。 */
    final NotionFetchOps fetchOps;

    /** 对照 Go {@code NewConnector}。 */
    public NotionConnector() {
        this(NotionClient::create);
    }

    public NotionConnector(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.fetchOps = new NotionFetchOps(this);
    }

    @Override
    public String type() {
        return DataSourceConstants.CONNECTOR_TYPE_NOTION;
    }

    /** 对照 Go {@code Validate}：解析配置 → 建客户端 → {@code Ping}。 */
    @Override
    public void validate(DataSourceConfig config) {
        NotionConfig notionConfig = NotionConfig.parse(config);
        NotionClient client = clientFactory.create(notionConfig.apiKey, extractBaseUrl(config));
        client.ping();
    }

    /**
     * 对照 Go {@code ResolveResourceAncestors}：Notion 什么都不用做——
     * {@code ListResources} 一次就返回带 parent 链接的整棵树，
     * 任何已存在的选择本来就在树里。
     */
    @Override
    public List<String> resolveResourceAncestors(DataSourceConfig config, List<String> resourceIds) {
        return new ArrayList<>();
    }

    /**
     * 对照 Go {@code ListResources}：一次 Search 返回全部页面/数据源，
     * 前端据此渲染树。非空 {@code parentId} 的惰性加载请求**没有**额外内容可回。
     */
    @Override
    public List<Resource> listResources(DataSourceConfig config, String parentId) {
        if (parentId != null && !parentId.isEmpty()) {
            return new ArrayList<>();
        }

        NotionConfig notionConfig = NotionConfig.parse(config);
        NotionClient client = clientFactory.create(notionConfig.apiKey, extractBaseUrl(config));

        List<NotionPage> pages;
        try {
            pages = client.searchPages();
        } catch (ConnectorException e) {
            throw new ConnectorException("search notion pages: " + e.getMessage(), e);
        }

        Set<String> allIds = new LinkedHashSet<>();
        for (NotionPage p : pages) {
            allIds.add(p.id());
        }

        // 预计算每个对象的有效父节点：data_source 要用 database_parent
        // （它的 `parent` 指向数据库容器，不是工作区位置）。
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (NotionPage p : pages) {
            if (p.inTrash) {
                continue;
            }
            parentOf.put(p.id(), resolveParentId(p, allIds));
        }

        Map<String, Integer> childrenCount = new LinkedHashMap<>();
        for (String pid : parentOf.values()) {
            if (!pid.isEmpty()) {
                childrenCount.merge(pid, 1, Integer::sum);
            }
        }

        List<Resource> resources = new ArrayList<>();
        for (NotionPage p : pages) {
            if (p.inTrash) {
                continue;
            }
            Resource resource = new Resource();
            resource.setExternalId(p.id());
            resource.setName(p.title == null ? "" : p.title);
            resource.setType(p.isDatabase()
                    ? NotionConstants.OBJECT_TYPE_DATABASE
                    : NotionConstants.OBJECT_TYPE_PAGE);
            resource.setUrl(p.url());
            resource.setParentId(parentOf.getOrDefault(p.id(), ""));
            resource.setHasChildren(childrenCount.getOrDefault(p.id(), 0) > 0);
            resources.add(resource);
        }
        return resources;
    }

    /** 对照 Go {@code FetchAll}。 */
    @Override
    public List<FetchedItem> fetchAll(DataSourceConfig config, List<String> resourceIds) {
        NotionConfig notionConfig = NotionConfig.parse(config);
        NotionClient client = clientFactory.create(notionConfig.apiKey, extractBaseUrl(config));
        Map<String, Boolean> visited = excludedSetFromListResources(config, resourceIds);

        List<FetchedItem> allItems = new ArrayList<>();
        if (resourceIds == null) {
            return allItems;
        }
        for (String resourceId : resourceIds) {
            NotionPage page = null;
            try {
                page = client.getPage(resourceId);
            } catch (ConnectorException e) {
                page = null;
            }
            if (page != null) {
                allItems.addAll(fetchOps.fetchPage(client, page, visited));
                continue;
            }
            // 不是页面 → 当成 database / data_source 处理。
            // fetchDatabase 同时接受 data_source ID（Search 给的）与
            // database 容器 ID（child_database 块给的）。
            List<FetchedItem> items = fetchOps.fetchDatabase(client, resourceId, visited);
            if (items.isEmpty()) {
                log.warn("[Notion] failed to fetch resource {} as page or database", resourceId);
            }
            allItems.addAll(items);
        }
        return allItems;
    }

    /**
     * 对照 Go {@code FetchIncremental}。首次同步（cursor 为空）直接走
     * {@code FetchAll}——不需要发现阶段，省一次 Search。
     */
    @Override
    public FetchIncrementalResult fetchIncremental(DataSourceConfig config, SyncCursor cursor) {
        NotionConfig notionConfig = NotionConfig.parse(config);

        List<String> resourceIds = config == null ? null : config.getResourceIds();
        if (resourceIds == null || resourceIds.isEmpty()) {
            throw new ConnectorException("no resource IDs configured");
        }

        NotionClient client = clientFactory.create(notionConfig.apiKey, extractBaseUrl(config));

        // 解析上一次的游标（对应 Go 的 json.Marshal → json.Unmarshal 往返；
        // 失败时 Go 留下零值 → 视作首次同步）
        NotionCursor prevCursor = new NotionCursor();
        if (cursor != null && cursor.getConnectorCursor() != null) {
            try {
                byte[] bytes = NotionJson.MAPPER.writeValueAsBytes(cursor.getConnectorCursor());
                NotionCursor parsed = NotionJson.MAPPER.readValue(bytes, NotionCursor.class);
                if (parsed != null) {
                    prevCursor = parsed;
                }
            } catch (Exception e) {
                prevCursor = new NotionCursor();
            }
        }
        Map<String, OffsetDateTime> prevEditTimes = prevCursor.pageEditTimes();

        boolean isFirstSync = prevEditTimes.isEmpty();
        if (isFirstSync) {
            log.info("[Notion] first sync, using FetchAll for {} resources", resourceIds.size());
            List<FetchedItem> items = fetchAll(config, resourceIds);

            // 用抓到的条目的 UpdatedAt 建 cursor。记录级编辑时间逐条跟踪
            // （object_type == "page"）；数据库容器 ID 也显式登记，
            // 好让增量同步能判断"这个库到底变没变"，避免每轮都全量查记录。
            Map<String, OffsetDateTime> newEditTimes = new LinkedHashMap<>();
            for (FetchedItem item : items) {
                Map<String, String> metadata = item.getMetadata();
                if (metadata != null
                        && NotionConstants.OBJECT_TYPE_PAGE.equals(metadata.get("object_type"))) {
                    newEditTimes.put(item.getExternalId(), item.getUpdatedAt());
                }
            }
            // 保证选中的 resourceIds 都出现在 cursor 里（页面已经由条目带上，
            // 数据库需要显式补一条）
            for (String rid : resourceIds) {
                if (!newEditTimes.containsKey(rid)) {
                    newEditTimes.put(rid, NotionValues.now());
                }
            }
            return new FetchIncrementalResult(items, buildCursor(newEditTimes));
        }

        // 后续同步：发现全部页面 → 与 cursor 差分 → 只抓变化者
        log.info("[Notion] incremental sync, discovering pages");
        DiscoverResult discovered = discoverAllResources(client, resourceIds);
        List<NotionPage> pages = discovered.included;
        Map<String, Boolean> fetchVisited = discovered.excluded;
        log.info("[Notion] discovered {} pages", pages.size());

        Map<String, OffsetDateTime> newEditTimes = new LinkedHashMap<>();
        Map<String, NotionPage> pageById = new LinkedHashMap<>();
        for (NotionPage page : pages) {
            newEditTimes.put(page.id(), page.lastEditedTime);
            pageById.put(page.id(), page);
        }

        List<FetchedItem> changedItems = new ArrayList<>();
        int changedCount = 0;

        // ⚠️ Go 在这里 range 一个 map **并同时往它里面写**（合并记录级编辑时间）
        // ——Go 允许这么干（新键是否被本轮遍历到未定义），Java 会抛
        // ConcurrentModificationException，所以先取一份条目快照。
        // 净效果相同：新并入的都是"记录"的 ID，而 pageById 里没有它们，
        // 命中了也只会走 `page == null → continue`。
        for (Map.Entry<String, OffsetDateTime> entry : new ArrayList<>(newEditTimes.entrySet())) {
            String pageId = entry.getKey();
            OffsetDateTime newTime = entry.getValue();
            boolean existed = prevEditTimes.containsKey(pageId);
            OffsetDateTime prevTime = prevEditTimes.get(pageId);
            if (existed && equalInstants(newTime, prevTime)) {
                continue;
            }
            if (Boolean.TRUE.equals(fetchVisited.get(pageId))) {
                continue;
            }
            changedCount++;
            NotionPage page = pageById.get(pageId);
            if (page == null) {
                continue;
            }
            log.debug("[Notion] changed: {} ({}, {})", page.title, page.id(), page.object());
            if (page.isDatabase()) {
                // 数据库的增量：查记录、与 cursor 差分，只对真正变化的记录抓块内容
                NotionFetchOps.DatabaseIncremental incremental = fetchOps.fetchDatabaseIncremental(
                        client, page.id(), prevEditTimes, fetchVisited);
                changedItems.addAll(incremental.items);
                newEditTimes.putAll(incremental.recordEditTimes);
            } else {
                changedItems.addAll(fetchOps.fetchPage(client, page, fetchVisited));
            }
        }

        // 删除检测：三个跳过条件见类注释
        for (String pageId : new ArrayList<>(prevEditTimes.keySet())) {
            if (newEditTimes.containsKey(pageId)) {
                continue;
            }
            if (Boolean.TRUE.equals(fetchVisited.get(pageId))) {
                continue;
            }
            FetchedItem deleted = new FetchedItem();
            deleted.setExternalId(pageId);
            deleted.setDeleted(true);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("channel", NotionConstants.CHANNEL_NOTION);
            deleted.setMetadata(metadata);
            changedItems.add(deleted);
        }

        log.info("[Notion] incremental: {} changed, {} total items",
                changedCount, changedItems.size());
        return new FetchIncrementalResult(changedItems, buildCursor(newEditTimes));
    }

    /** 对照 Go {@code buildCursor}。 */
    static SyncCursor buildCursor(Map<String, OffsetDateTime> editTimes) {
        Map<String, Object> pageEditTimes = new LinkedHashMap<>();
        // Go 的 json.Marshal 对 map 恒按**字节序**排键；这里显式排序，让
        // cursor 的 jsonb 形状与 Go 逐字节一致（probe 的 cursor.json 已钉住）。
        List<String> keys = new ArrayList<>(editTimes.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            OffsetDateTime time = editTimes.get(key);
            pageEditTimes.put(key, time == null ? "" : NotionValues.rfc3339Nano(time));
        }

        Map<String, Object> cursorMap = new LinkedHashMap<>();
        cursorMap.put("page_edit_times", pageEditTimes);

        SyncCursor syncCursor = new SyncCursor();
        syncCursor.setLastSyncTime(NotionValues.now());
        syncCursor.setConnectorCursor(cursorMap);
        return syncCursor;
    }

    /** Go 的 {@code time.Time.Equal}：比瞬时，不比字面量/时区。 */
    static boolean equalInstants(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.toInstant().equals(b.toInstant());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：发现与排除集
    // ──────────────────────────────────────────────────────────────────────

    /** 对照 Go 的 {@code (included, excluded)} 双返回值。 */
    private static final class DiscoverResult {
        final List<NotionPage> included;
        final Map<String, Boolean> excluded;

        DiscoverResult(List<NotionPage> included, Map<String, Boolean> excluded) {
            this.included = included;
            this.excluded = excluded;
        }
    }

    /**
     * 对照 Go {@code discoverAllResources}：Search 全量 → 按父子链从选中的根 BFS，
     * 返回"选中子树内的页面"与"可见但不在任何选中根之下的页面（排除集）"。
     */
    private DiscoverResult discoverAllResources(NotionClient client, List<String> resourceIds) {
        List<NotionPage> allPages;
        try {
            allPages = client.searchPages();
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to search pages for discovery: {}", e.getMessage());
            return new DiscoverResult(new ArrayList<>(), new LinkedHashMap<>());
        }

        Set<String> allIds = new LinkedHashSet<>();
        Map<String, NotionPage> pageById = new LinkedHashMap<>();
        for (NotionPage p : allPages) {
            allIds.add(p.id());
            pageById.put(p.id(), p);
        }

        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        for (NotionPage p : allPages) {
            if (p.inTrash) {
                continue;
            }
            String parentId = resolveParentId(p, allIds);
            if (!parentId.isEmpty()) {
                childrenOf.computeIfAbsent(parentId, k -> new ArrayList<>()).add(p.id());
            }
        }

        // 从每个选中的根 BFS 收集全部后代
        Set<String> includedSet = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (resourceIds != null) {
            for (String id : resourceIds) {
                if (pageById.containsKey(id)) {
                    includedSet.add(id);
                    queue.add(id);
                }
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String childId : childrenOf.getOrDefault(current, List.of())) {
                if (includedSet.add(childId)) {
                    queue.add(childId);
                }
            }
        }

        List<NotionPage> included = new ArrayList<>(includedSet.size());
        for (String id : includedSet) {
            included.add(pageById.get(id));
        }
        Map<String, Boolean> excluded = new LinkedHashMap<>();
        for (String id : allIds) {
            if (!includedSet.contains(id)) {
                excluded.put(id, true);
            }
        }
        return new DiscoverResult(included, excluded);
    }

    /**
     * 对照 Go {@code computeExcludedSet}：用户**显式取消勾选**的 ID 集合
     * ——在 picker 里可见、但既没被选中、也不是某个选中节点的后代。
     * 用来给 {@code visited} 播种，让递归的 child_page/child_database 跳过它们。
     *
     * <p>"用户从没见过的页面"（上次保存配置之后新建的）<b>不算</b>排除，
     * 所以选中的父页面仍然会自动带上它们。</p>
     *
     * <p><b>与 Go 的一处防御性差异</b>：Go 的循环
     * {@code for cur := id; cur != ""; cur = parentOf[cur]} 在 parent 关系成环
     * （且环上没有被选中节点）时会<b>死循环</b>。Java 侧加了 seen 集合，
     * 成环时终止。真实数据里父子关系不可能成环，这只防脏数据把同步线程挂死。</p>
     */
    static Map<String, Boolean> computeExcludedSet(List<String> visibleIds,
                                                   Map<String, String> parentOf,
                                                   List<String> selectedIds) {
        Set<String> selected = new HashSet<>();
        if (selectedIds != null) {
            selected.addAll(selectedIds);
        }
        Map<String, Boolean> excluded = new LinkedHashMap<>();
        if (visibleIds == null) {
            return excluded;
        }
        for (String id : visibleIds) {
            boolean hasSelectedAncestor = false;
            Set<String> seen = new HashSet<>();
            String cur = id;
            while (cur != null && !cur.isEmpty() && seen.add(cur)) {
                if (selected.contains(cur)) {
                    hasSelectedAncestor = true;
                    break;
                }
                String next = parentOf == null ? null : parentOf.get(cur);
                cur = next == null ? "" : next;
            }
            if (!hasSelectedAncestor) {
                excluded.put(id, true);
            }
        }
        return excluded;
    }

    /**
     * 对照 Go {@code excludedSetFromListResources}：走 {@code ListResources}
     * 拿 picker 层级再算排除集（{@code FetchAll} 走这条路，因为那条路径上
     * 没有别的地方已经拿页面列表了）。
     */
    private Map<String, Boolean> excludedSetFromListResources(DataSourceConfig config,
                                                              List<String> selectedIds) {
        List<Resource> visible;
        try {
            visible = listResources(config, "");
        } catch (ConnectorException e) {
            log.warn("[Notion] failed to list visible resources for exclusion: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
        List<String> ids = new ArrayList<>(visible.size());
        Map<String, String> parentOf = new LinkedHashMap<>();
        for (Resource resource : visible) {
            ids.add(resource.getExternalId());
            parentOf.put(resource.getExternalId(), resource.getParentId());
        }
        return computeExcludedSet(ids, parentOf, selectedIds);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：文件上传解析
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code resolveFileUploads}：走一遍块树，把 {@code file_upload}
     * 型的文件重新取一次块、换成带**临时下载地址**的形态
     * （Notion 的 S3 签名地址，1 小时过期）。<b>就地改写</b>
     * {@code blocks[i].RawContent}。
     */
    static void resolveFileUploads(NotionClient client, List<NotionBlock> blocks) {
        if (blocks == null) {
            return;
        }
        for (NotionBlock block : blocks) {
            if (NotionMarkdown.isFileBlock(block.type()) && block.rawContent != null) {
                NotionFile file = NotionMarkdown.parseFile(block.rawContent);
                if (!file.fileUploadId().isEmpty()) {
                    NotionBlock resolved;
                    try {
                        resolved = client.resolveBlock(block.id());
                    } catch (ConnectorException e) {
                        log.warn("[Notion] failed to resolve file_upload in block {}: {}",
                                block.id(), e.getMessage());
                        continue;
                    }
                    block.rawContent = resolved.rawContent;
                }
            }
            if (block.children != null && !block.children.isEmpty()) {
                resolveFileUploads(client, block.children);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部：父子与默认值
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code resolveParentID}：判断对象在工作区层级里的位置。
     *
     * <p>两个分支都是"父 ID 必须在 {@code allIDs} 里才算数"——Search 没返回的
     * 祖先（例如集成没权限的页面）会让对象**变成根**，而不是挂在一个不存在的
     * 父亲下面。</p>
     *
     * <p>data_source 走 {@code database_parent}：它指向承载这个数据库的**页面**；
     * 而 data_source 的常规 {@code parent} 指向数据库容器本身，是错的来源。
     * （probe：同一个对象用 parent 会得到 {@code "db"}，用 database_parent 得到
     * {@code "p1"}。）</p>
     */
    static String resolveParentId(NotionPage page, Set<String> allIds) {
        if (page.isDatabase() && page.databaseParent != null) {
            String pid = page.databaseParent.parentId();
            if (!pid.isEmpty() && allIds.contains(pid)) {
                return pid;
            }
            return "";
        }
        if (NotionConstants.PARENT_TYPE_WORKSPACE.equals(page.parent().type())) {
            return "";
        }
        String pid = page.parent().parentId();
        if (!pid.isEmpty() && allIds.contains(pid)) {
            return pid;
        }
        return "";
    }

    /**
     * 对照 Go {@code getDatabaseOrDataSourceInfo}：先试
     * {@code GET /v1/data_sources/{id}}（Search 返回的是 data_source ID），
     * 失败再回落 {@code GET /v1/databases/{id}}（child_database 块给的是
     * database ID）。
     */
    NotionDatabaseInfo getDatabaseOrDataSourceInfo(NotionClient client, String id) {
        try {
            NotionPage ds = client.getDataSourceInfo(id);
            return new NotionDatabaseInfo(ds, id);
        } catch (ConnectorException e) {
            return client.getDatabaseInfo(id);
        }
    }

    /**
     * 对照 Go {@code extractBaseURL}：{@code settings.base_url} 是非空字符串就用它，
     * 否则回 {@code DefaultBaseURL}。
     */
    static String extractBaseUrl(DataSourceConfig config) {
        if (config != null && config.getSettings() != null) {
            Object url = config.getSettings().get("base_url");
            if (url instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return NotionConstants.DEFAULT_BASE_URL;
    }
}
