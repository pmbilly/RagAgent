package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.jdbc.DatabaseDialects;
import com.ragagent.wiki.domain.SourceRefNeedle;
import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageConflictException;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiRevisionPruneRequest;
import com.ragagent.wiki.domain.WikiSourceRefs;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * wiki 页面 / 文件夹 / 修订 / 问题仓储——GORM 复刻层：查询面按原实现逐字对齐，
 * 数据访问轴改写时一并重塑（870 行略超 800 属当前切段的一次性投入）。
 *
 * <p>本类刻意做成「与原实现方法一一对应」的薄仓储：service 层可以机械对照。
 * 方法名 = 原方法名（首字母小写），参数顺序一致。</p>
 *
 * <p><b>三处方言分支的 Java 写法</b>：</p>
 * <ol>
 *   <li>方言判定 → 构造期从 DataSource 探测一次（{@link #isPostgres()}）。
 *       原实现每次调用都重新读取方言，同一进程内结果不变，故探测一次等价。</li>
 *   <li>排序的"有目录优先"表达式 → PG 的 {@code jsonb_array_length} 在 H2 由
 *       TestSchema 注册同名 ALIAS，因此同一条 SQL 两边都能跑（见
 *       {@link WikiPageMapper#categoryRankOrder}）。</li>
 *   <li>空入链判定 → 统一写成
 *       {@code (in_links IS NULL OR in_links = '[]')}，PG 把未定类型字面量解析成 jsonb，
 *       H2 按文本比较（TypeHandler 对空列表写的就是 {@code []}）。</li>
 * </ol>
 * <p>另有 category_path 相等比较、source_refs 包含、全文检索、规范化标题四处
 * 用 Mapper 里的 {@code <choose>} 按方言分叉。</p>
 *
 * <p><b>自动时间戳</b>：CreatedAt/UpdatedAt 是原 ORM 的约定字段，插入时
 * 自动填、更新时自动刷新。Java 侧由 {@link #touch} 在写入前补 null，
 * 语义等价（已赋值则不覆盖，与原 ORM 只填零值一致）。</p>
 */
@Repository
public class WikiPageRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 游标分页的 limit 上下界 */
    private static final int CURSOR_DEFAULT_LIMIT = 100;
    private static final int CURSOR_MAX_LIMIT = 500;

    /** 索引瘦投影的 limit 上下界 */
    private static final int LIGHT_DEFAULT_LIMIT = 50;
    private static final int LIGHT_MAX_LIMIT = 200;

    /** 首次生成索引导语时的 limit 上下界 */
    private static final int RECENT_DEFAULT_LIMIT = 200;
    private static final int RECENT_MAX_LIMIT = 1000;

    /** 规范化标题批量查询的分块与行数上限 */
    private static final int NORMALIZED_TITLE_CHUNK = 100;
    private static final int NORMALIZED_TITLE_ROW_CAP = 500;

    /** 默认分页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final WikiPageMapper pages;
    private final WikiPageRevisionMapper revisions;
    private final WikiFolderMapper folders;
    private final WikiPageIssueMapper issues;
    private final boolean postgres;

    public WikiPageRepository(WikiPageMapper pages,
                              WikiPageRevisionMapper revisions,
                              WikiFolderMapper folders,
                              WikiPageIssueMapper issues,
                              DataSource dataSource) {
        this.pages = pages;
        this.revisions = revisions;
        this.folders = folders;
        this.issues = issues;
        this.postgres = DatabaseDialects.isPostgres(dataSource);
    }

    /** 当前方言是否为 PostgreSQL（构造期探测一次）。 */
    public boolean isPostgres() {
        return postgres;
    }

    // ──────────────────────────── 结果载体 ────────────────────────────

    /** 列表查询结果：当前页数据 + 过滤后的总数。 */
    public record PageList(List<WikiPage> pages, long total) {}

    /** 索引瘦投影结果：条目 + 非归档总数。 */
    public record LightList(List<WikiIndexEntry> entries, long total) {}

    /** 游标分页结果：本页数据 + 下一页游标。 */
    public record CursorPage(List<WikiPage> pages, String nextCursor) {}

    /** 修订列表结果：本页快照 + 总数。 */
    public record RevisionList(List<WikiPageRevision> revisions, long total) {}

    // ──────────────────────────── 页面写入 ────────────────────────────

    /** 复刻原 ORM 的自动时间戳语义：只补 null，不覆盖已赋值 */
    private static void touch(WikiPage page) {
        OffsetDateTime now = OffsetDateTime.now();
        if (page.getCreatedAt() == null) {
            page.setCreatedAt(now);
        }
        if (page.getUpdatedAt() == null) {
            page.setUpdatedAt(now);
        }
    }

    /**
     * 插入新页面。
     *
     * <p>这里额外复刻原实现的列默认值回写：status 默认 'published'、version 默认 1，
     * 插入时会把<b>零值替换成默认值并回写内存</b>——也就是说原实现无法用该实体建出
     * status='' / version=0 的行，创建完成后内存对象里也是 'published' / 1。
     * Java 侧不做这步就会让创建响应里出现 {@code "version":0}，与原实现不一致。</p>
     *
     * <p>注意 {@code page_type}：原实现里该列<b>没有</b>默认值，插入时零值列从
     * INSERT 里省略、由 SQL 的 {@code DEFAULT 'summary'} 兜底，而内存对象<b>仍是 ""</b>
     * ——两边内存值一致（都是 ""），故 Java 不需要特殊处理。</p>
     */
    public void create(WikiPage page) {
        if (page.getVersion() <= 0) {
            page.setVersion(1);
        }
        if (page.getStatus() == null || page.getStatus().isEmpty()) {
            page.setStatus(WikiConstants.STATUS_PUBLISHED);
        }
        touch(page);
        pages.insert(page);
    }

    /** 全量更新（带乐观锁的版本化写入，见 {@link #updateWikiPageRow}）。 */
    public void update(WikiPage page) {
        updateWikiPageRow(page);
    }

    /**
     * 在同一事务里"快照被取代的版本 + 应用页面更新"。
     *
     * <p>放进同一事务正是历史可信的前提：更新失败时不会再留下一份"仍是当前版本"的快照，
     * 否则历史里会出现两次、且无法回滚。插入已存在的 (page_id, version) 对是静默 no-op
     * （并发写者先快照的副本内容相同，放着不管才是对的）。</p>
     */
    @Transactional
    public void updateWithRevision(WikiPage page, WikiPageRevision rev) {
        if (rev != null) {
            revisions.insertSnapshotDoNothing(rev, postgres);
        }
        updateWikiPageRow(page);
    }

    /**
     * 带乐观锁的版本化写入。
     *
     * <p>失败时把 {@code page.version} 还原，保证调用方<b>绝不会</b>观察到
     * "写没落库却涨了版本"。</p>
     */
    private void updateWikiPageRow(WikiPage page) {
        int expectedVersion = page.getVersion();
        page.setVersion(expectedVersion + 1);
        touch(page);

        int rows;
        try {
            rows = pages.updateWithVersion(page, expectedVersion);
        } catch (RuntimeException e) {
            page.setVersion(expectedVersion);
            throw e;
        }
        if (rows == 0) {
            page.setVersion(expectedVersion);
            // 可能是页面不存在，也可能是版本冲突——查一下区分
            long count = pages.countLiveById(page.getId());
            if (count == 0) {
                throw new WikiPageNotFoundException();
            }
            throw new WikiPageConflictException();
        }
    }

    /**
     * 不递增 version 的重写正文/出链。
     * 用于机器侧的链接标记（交叉链接注入、死链清理）。
     */
    public void updateAutoLinkedContent(WikiPage page) {
        if (page.getUpdatedAt() == null) {
            page.setUpdatedAt(OffsetDateTime.now());
        }
        if (pages.updateAutoLinkedContent(page) == 0) {
            throw new WikiPageNotFoundException();
        }
    }

    /** 不递增 version 的记账字段刷新 */
    public void updateMeta(WikiPage page) {
        if (page.getUpdatedAt() == null) {
            page.setUpdatedAt(OffsetDateTime.now());
        }
        if (pages.updateMeta(page) == 0) {
            throw new WikiPageNotFoundException();
        }
    }

    // ──────────────────────────── 页面读取 ────────────────────────────

    public WikiPage getByID(String id) {
        WikiPage page = pages.selectLiveById(id);
        if (page == null) {
            throw new WikiPageNotFoundException();
        }
        return page;
    }

    public WikiPage getBySlug(String kbId, String slug) {
        WikiPage page = pages.selectLiveBySlug(kbId, slug);
        if (page == null) {
            throw new WikiPageNotFoundException();
        }
        return page;
    }

    /**
     * 过滤 + 分页的页面列表查询。
     *
     * <p>目录过滤下推到 SQL，让数据库负责计数与分页，而不是把整类型的页面都读进内存。
     * {@code depth} 是缓存列（= category_path 长度）；{@code category_path} 的存储文本
     * 是路径段的紧凑 JSON，因此比较对象用同一种编码。</p>
     */
    public PageList list(WikiPageListRequest req) {
        List<String> pageTypes = WikiCategoryPaths.splitPageTypes(req.getPageType());

        String categoryPathEncoded = null;
        List<String> wantPath = WikiCategoryPaths.trimFolderSegments(req.getCategoryPath());
        if (!wantPath.isEmpty()) {
            try {
                categoryPathEncoded = JSON.writeValueAsString(wantPath);
            } catch (Exception e) {
                throw new IllegalStateException("marshal category path filter failed", e);
            }
        }

        // ILIKE 的实参就是 "%"+Query+"%"（原实现未转义通配符，此处照抄）
        String queryLike = "%" + req.getQuery() + "%";

        long total = pages.countList(req, pageTypes, categoryPathEncoded, queryLike, postgres);

        int page = Math.max(req.getPage(), 1);
        int pageSize = req.getPageSize() < 1 ? DEFAULT_PAGE_SIZE : req.getPageSize();
        int offset = (page - 1) * pageSize;

        String sortColumn = localizeSortColumn(req.getSortBy());
        String sortDirection = "asc".equals(req.getSortOrder()) ? "ASC" : "DESC";
        boolean wikiPathSort = "wiki_path".equals(sortColumn);

        List<WikiPage> rows = pages.list(req, pageTypes, categoryPathEncoded, queryLike, postgres,
                sortColumn, sortDirection, wikiPathSort, pageSize, offset);
        return new PageList(rows, total);
    }

    /**
     * 把调用方给的 sort_by 映射成<b>白名单列名</b>（列名由 ORM 引号化、方向二选一）。
     * Java 侧同样只在白名单里取值，用户输入永远不进 ORDER BY 文本。
     */
    static String localizeSortColumn(String sortBy) {
        if (sortBy == null) {
            return "updated_at";
        }
        switch (sortBy) {
            case "title":
            case "created_at":
            case "updated_at":
            case "page_type":
            case "wiki_path":
            case "sort_order":
            case "depth":
                return sortBy;
            default:
                return "updated_at";
        }
    }

    public List<WikiPage> listByType(String kbId, String pageType) {
        return pages.listByType(kbId, pageType);
    }

    /**
     * 只投影渲染索引目录项所需的列
     * （slug/title/summary/…），按目录优先 + title ASC 分页，<b>排除归档</b>，
     * 返回该类型的非归档总数好让调用方渲染 "showing N of M"。
     */
    public LightList listByTypeLight(String kbId, String pageType, int limit, int offset) {
        int lim = limit;
        if (lim <= 0) {
            lim = LIGHT_DEFAULT_LIMIT;
        }
        if (lim > LIGHT_MAX_LIMIT) {
            lim = LIGHT_MAX_LIMIT;
        }
        int off = Math.max(offset, 0);

        long total = pages.countByTypeLight(kbId, pageType, WikiConstants.STATUS_ARCHIVED);
        if (total == 0) {
            return new LightList(List.of(), 0);
        }
        List<WikiIndexEntry> entries =
                pages.listByTypeLight(kbId, pageType, WikiConstants.STATUS_ARCHIVED, lim, off);
        return new LightList(entries, total);
    }

    /** 最近更新的 N 条瘦投影 */
    public List<WikiIndexEntry> listByTypeRecent(String kbId, String pageType, int limit) {
        int lim = limit;
        if (lim <= 0) {
            lim = RECENT_DEFAULT_LIMIT;
        }
        if (lim > RECENT_MAX_LIMIT) {
            lim = RECENT_MAX_LIMIT;
        }
        return pages.listByTypeRecent(kbId, pageType, WikiConstants.STATUS_ARCHIVED, lim);
    }

    // ──────────────────────── source_refs 溯源 ────────────────────────

    /**
     * 找出引用了指定 source knowledge id 的页面。
     * 两种历史形态（"knowledgeID" 与 "knowledgeID|title"）都覆盖。
     */
    public List<WikiPage> listBySourceRef(String kbId, String sourceKnowledgeID) {
        SourceRefNeedle n = WikiSourceRefs.of(sourceKnowledgeID);
        return pages.listBySourceRef(kbId, postgres, n.getNeedle(), n.getExactLike(), n.getPrefixLike());
    }

    /**
     * 同一谓词，只取 slug 列，
     * 用于 ingest 的 "before" 快照路径。
     */
    public List<String> listSlugsBySourceRef(String kbId, String sourceKnowledgeID) {
        SourceRefNeedle n = WikiSourceRefs.of(sourceKnowledgeID);
        return pages.listSlugsBySourceRef(kbId, postgres, n.getNeedle(), n.getExactLike(),
                n.getPrefixLike());
    }

    /**
     * 一次 IN 查询拿瘦投影。
     *
     * <p>空输入返回空 map。库里不存在的 slug 会被静默丢弃——调用方把"缺失"当作
     * "没有这个页面"，与旧版 ListAll 的缺键语义相同。</p>
     */
    public Map<String, WikiPageLite> listBySlugs(String kbId, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<WikiPageLite> rows = pages.listLiteBySlugs(kbId, slugs);
        Map<String, WikiPageLite> out = new LinkedHashMap<>(rows.size());
        for (WikiPageLite row : rows) {
            out.put(row.getSlug(), row);
        }
        return out;
    }

    /**
     * 按"撰写该摘要的 knowledge id"
     * 返回摘要页正文。先按 page_type 收窄（只有 summary 页的正文适合做 retract 框定），
     * 再在 source_refs 里匹配裸 id 或 {@code "id|title"} 旧形态。
     */
    public Map<String, String> listSummariesByKnowledgeIDs(String kbId, List<String> kids) {
        if (kids == null || kids.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Set<String> kidSet = new LinkedHashSet<>();
        List<SourceRefNeedle> needles = new ArrayList<>(kids.size());
        for (String kid : kids) {
            if (kid == null || kid.isEmpty()) {
                continue;
            }
            if (kidSet.add(kid)) {
                needles.add(WikiSourceRefs.of(kid));
            }
        }
        if (needles.isEmpty()) {
            return new LinkedHashMap<>();
        }

        List<WikiPageMapper.SummaryRow> rows = pages.listSummariesByKnowledgeIDs(
                kbId, WikiConstants.PAGE_TYPE_SUMMARY, WikiConstants.STATUS_ARCHIVED, postgres, needles);

        // 一个摘要可能携带多个来源（此前发生过合并/重摄取），因此按 kid 建索引；
        // 同一 kid 出现多次时先到先得。
        Map<String, String> out = new LinkedHashMap<>();
        for (WikiPageMapper.SummaryRow row : rows) {
            for (String ref : row.getSourceRefs()) {
                String refKid = WikiCategoryPaths.sourceKnowledgeID(ref);
                if (!kidSet.contains(refKid)) {
                    continue;
                }
                out.putIfAbsent(refKid, row.getContent());
            }
        }
        return out;
    }

    /** 只有"非归档的活跃 slug"才是 true */
    public Map<String, Boolean> existsSlugs(String kbId, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Boolean> out = new LinkedHashMap<>(slugs.size());
        for (String s : slugs) {
            out.put(s, false);
        }
        List<String> live = pages.selectLiveSlugs(kbId, WikiConstants.STATUS_ARCHIVED, slugs);
        for (String s : live) {
            out.put(s, true);
        }
        return out;
    }

    public List<String> listAllSlugs(String kbId) {
        return pages.selectAllLiveSlugs(kbId, WikiConstants.STATUS_ARCHIVED);
    }

    /**
     * 按 (knowledge_base_id, id) 升序走，
     * <b>排除归档页</b>，游标是上一页最后一行的 id 字符串，"" 从头开始；
     * 返回的 nextCursor 为空表示已到流末尾。limit 夹在 [1, 500]。
     */
    public CursorPage listPagesCursor(String kbId, String cursor, int limit) {
        int lim = limit;
        if (lim <= 0) {
            lim = CURSOR_DEFAULT_LIMIT;
        }
        if (lim > CURSOR_MAX_LIMIT) {
            lim = CURSOR_MAX_LIMIT;
        }
        List<WikiPage> rows = pages.listPagesCursor(kbId, WikiConstants.STATUS_ARCHIVED,
                cursor == null ? "" : cursor, lim);
        String nextCursor = rows.size() == lim ? rows.get(rows.size() - 1).getId() : "";
        return new CursorPage(rows, nextCursor);
    }

    // ──────────────────────────── 相似度匹配 ────────────────────────────

    /**
     * PG {@code pg_trgm} 三元组相似度检索。
     *
     * <p>types 为空时默认 entity+concept；limit 夹在 [1, 50]。相似度低于 0.1 的
     * 标题由服务端的 {@code %} 运算符（尊重 {@code pg_trgm.similarity_threshold}）丢弃。</p>
     *
     * <p><b>已知差异</b>：原实现<b>没有</b>方言分支——在没有
     * {@code similarity()} 与 {@code %} 运算符的库上会直接报 SQL 错误。
     * Java 为了不在测试库上炸，非 PG 方言下返回空列表（调用方看到"没有候选"，
     * 与 pg_trgm 未命中时的表现一致）。生产走 PG，行为一致。</p>
     */
    public List<WikiPageLite> findSimilarPages(String kbId, String query, List<String> pageTypes,
                                               int limit) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        if (!postgres) {
            return List.of();
        }
        int lim = limit;
        if (lim <= 0) {
            lim = 20;
        }
        if (lim > 50) {
            lim = 50;
        }
        List<String> types = (pageTypes == null || pageTypes.isEmpty())
                ? List.of(WikiConstants.PAGE_TYPE_ENTITY, WikiConstants.PAGE_TYPE_CONCEPT)
                : pageTypes;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return pages.findSimilarPages(kbId, q, WikiConstants.STATUS_ARCHIVED, types, lim);
    }

    public List<WikiPageLite> findPagesByNormalizedTitle(String kbId, String pageType, String identity) {
        return findPagesByNormalizedTitles(kbId, pageType, List.of(identity));
    }

    /**
     * 按"去空白 + 小写"后的标题
     * 批量匹配非归档、同类型页面。
     *
     * <p>identities 应当是<b>已经去过空白并小写</b>的（调用方负责）；空项被忽略。
     * 分块 100 条一批查询，每块的行数上限 = {@code wikiNormalizedTitleLookupLimit}。</p>
     */
    public List<WikiPageLite> findPagesByNormalizedTitles(String kbId, String pageType,
                                                          List<String> identities) {
        List<String> uniq = uniqNormalizedTitleIdentities(identities);
        if (kbId == null || kbId.isEmpty() || pageType == null || pageType.isEmpty() || uniq.isEmpty()) {
            return List.of();
        }
        String normExpr = normalizedTitleExpr();
        List<WikiPageLite> out = new ArrayList<>(uniq.size());
        for (int start = 0; start < uniq.size(); start += NORMALIZED_TITLE_CHUNK) {
            int end = Math.min(start + NORMALIZED_TITLE_CHUNK, uniq.size());
            List<String> chunk = uniq.subList(start, end);
            out.addAll(pages.findPagesByNormalizedTitles(kbId, pageType,
                    WikiConstants.STATUS_ARCHIVED, normExpr, chunk,
                    normalizedTitleLookupLimit(chunk.size())));
        }
        return out;
    }

    /**
     * 方言化的"去空白"表达式。
     *
     * <p>PG 用 POSIX 字符类 {@code [[:space:]]}；H2（Java 正则）用 {@code \s}——
     * H2 的 REGEXP_REPLACE 走 JVM 正则，不认 POSIX 括号类。原实现的 SQLite 分支
     * （逐个 replace 掉常见分隔符）没有可对应的方言，故不保留。</p>
     */
    private String normalizedTitleExpr() {
        if (postgres) {
            return "regexp_replace(lower(title), '[[:space:]]+', '', 'g')";
        }
        return "REGEXP_REPLACE(lower(title), '\\s+', '')";
    }

    /** 行数上限 = n*8，夹在 [50, {@link #NORMALIZED_TITLE_ROW_CAP}]。 */
    static int normalizedTitleLookupLimit(int n) {
        if (n <= 0) {
            return 0;
        }
        int limit = n * 8;
        if (limit < 50) {
            limit = 50;
        }
        if (limit > NORMALIZED_TITLE_ROW_CAP) {
            limit = NORMALIZED_TITLE_ROW_CAP;
        }
        return limit;
    }

    /** trim + 去重（保序） */
    static List<String> uniqNormalizedTitleIdentities(List<String> identities) {
        if (identities == null || identities.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String identity : identities) {
            if (identity == null) {
                continue;
            }
            String trimmed = identity.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        return new ArrayList<>(seen);
    }

    // ──────────────────────────── 目录路径 ────────────────────────────

    /**
     * 已有 wiki 文件夹的物化路径
     * （拆成段），按 path 排序、截断到 maxPaths。folder 树是唯一真相来源，
     * 因此不再扫页面行。
     */
    public List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths) {
        int cap = maxPaths <= 0 ? 150 : maxPaths;
        List<String> paths = folders.listDistinctPaths(kbId, cap);
        List<List<String>> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            List<String> seg = WikiCategoryPaths.cleanCategoryPath(
                    List.of(p.split("/", -1)));
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    // ──────────────────────── 文件夹树（wiki_folders） ────────────────────────

    public void createFolder(WikiFolder folder) {
        OffsetDateTime now = OffsetDateTime.now();
        if (folder.getCreatedAt() == null) {
            folder.setCreatedAt(now);
        }
        if (folder.getUpdatedAt() == null) {
            folder.setUpdatedAt(now);
        }
        folders.insert(folder);
    }

    public WikiFolder getFolderByID(String kbId, String id) {
        WikiFolder folder = folders.selectFolderById(kbId, id);
        if (folder == null) {
            throw new WikiFolderNotFoundException();
        }
        return folder;
    }

    /**
     * 找不到时抛 {@link WikiFolderNotFoundException}（find-or-create 依赖这个判别）。
     */
    public WikiFolder getChildFolderByName(String kbId, String parentID, String name) {
        WikiFolder folder = folders.selectChildByName(kbId, parentID, name);
        if (folder == null) {
            throw new WikiFolderNotFoundException();
        }
        return folder;
    }

    /** sort_order ASC, name ASC */
    public List<WikiFolder> listChildFolders(String kbId, String parentID) {
        return folders.listChildFolders(kbId, parentID);
    }

    /** depth ASC, path ASC */
    public List<WikiFolder> listAllFolders(String kbId) {
        return folders.listAllFolders(kbId);
    }

    /** 0 行 → not found */
    public void updateFolder(WikiFolder folder) {
        if (folder.getUpdatedAt() == null) {
            folder.setUpdatedAt(OffsetDateTime.now());
        }
        if (folders.updateFolder(folder) == 0) {
            throw new WikiFolderNotFoundException();
        }
    }

    /**
     * 空判与软删在同一条 SQL 里；
     * 0 行时再判"是压根不存在还是非空"，分别给不同的 sentinel 错误。
     */
    public void deleteFolder(String kbId, String id) {
        int rows = folders.softDeleteIfEmpty(kbId, id, OffsetDateTime.now());
        if (rows == 0) {
            if (folders.countLiveById(kbId, id) == 0) {
                throw new WikiFolderNotFoundException();
            }
            throw new WikiFolderNotEmptyException();
        }
    }

    /** 直接挂在文件夹下的活跃页面数（排除归档） */
    public long countPagesInFolder(String kbId, String folderID) {
        return pages.countPagesInFolder(kbId, folderID, WikiConstants.STATUS_ARCHIVED);
    }

    /**
     * 按 folder_id 分组的活跃页面数。
     * 根目录页面用空串键。pageTypes 非空时只统计这些类型。
     */
    public Map<String, Long> countPagesByFolder(String kbId, List<String> pageTypes) {
        List<WikiPageMapper.FolderCount> rows = pages.countPagesByFolder(
                kbId, WikiConstants.STATUS_ARCHIVED,
                pageTypes == null ? List.of() : pageTypes);
        Map<String, Long> out = new LinkedHashMap<>(rows.size());
        for (WikiPageMapper.FolderCount row : rows) {
            out.put(row.getFolderId(), row.getCount());
        }
        return out;
    }

    /** 子树移动/重命名时重算缓存路径 */
    public List<WikiPage> listPagesByFolderIDs(String kbId, List<String> folderIDs) {
        if (folderIDs == null || folderIDs.isEmpty()) {
            return List.of();
        }
        return pages.listPagesByFolderIds(kbId, folderIDs);
    }

    // ──────────────────────────── 全量 / 删除 ────────────────────────────

    /** 非归档，page_type ASC, title ASC */
    public List<WikiPage> listAll(String kbId) {
        return pages.listAll(kbId, WikiConstants.STATUS_ARCHIVED);
    }

    /**
     * 跨知识库取最近更新的
     * 用户可见页面（排除 index 与归档，只要 published 且 title 非空）。
     */
    public List<WikiPage> listRecentForSuggestions(long tenantId, List<String> kbIDs, int limit) {
        if (kbIDs == null || kbIDs.isEmpty() || limit <= 0) {
            return List.of();
        }
        return pages.listRecentForSuggestions(tenantId, kbIDs, WikiConstants.PAGE_TYPE_INDEX,
                WikiConstants.STATUS_PUBLISHED, limit);
    }

    /** 按 (kb, slug) 软删，0 行 → not found */
    public void delete(String kbId, String slug) {
        if (pages.softDeleteBySlug(kbId, slug, OffsetDateTime.now()) == 0) {
            throw new WikiPageNotFoundException();
        }
    }

    public void deleteByID(String id) {
        if (pages.softDeleteById(id, OffsetDateTime.now()) == 0) {
            throw new WikiPageNotFoundException();
        }
    }

    // ──────────────────────────── 检索 / 统计 ────────────────────────────

    /**
     * 按命中位置排序的全文检索。
     * limit 夹在 [1, 50]，默认 10。
     */
    public List<WikiPage> search(String kbId, String query, int limit) {
        int lim = limit;
        if (lim <= 0) {
            lim = 10;
        }
        if (lim > 50) {
            lim = 50;
        }
        return pages.search(kbId, query, WikiConstants.STATUS_ARCHIVED, postgres, lim);
    }

    /** 非归档，按类型计数 */
    public Map<String, Long> countByType(String kbId) {
        List<WikiPageMapper.TypeCount> rows =
                pages.countByType(kbId, WikiConstants.STATUS_ARCHIVED);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (WikiPageMapper.TypeCount row : rows) {
            counts.put(row.getPageType(), row.getCount());
        }
        return counts;
    }

    /**
     * 没有入链的页面数，<b>排除归档</b>，
     * 且排除 index 页（它天然是根页面）。
     */
    public long countOrphans(String kbId) {
        return pages.countOrphans(kbId, WikiConstants.STATUS_ARCHIVED,
                WikiConstants.PAGE_TYPE_INDEX);
    }

    // ──────────────────────────── 修订历史 ────────────────────────────

    /** 最新在前，content 省略，附总数 */
    public RevisionList listRevisions(String kbId, String pageID, int limit, int offset) {
        long total = revisions.countRevisions(kbId, pageID);
        List<WikiPageRevision> rows = revisions.listRevisions(kbId, pageID, limit, offset);
        return new RevisionList(rows == null ? List.of() : rows, total);
    }

    /** 带 content 的单条快照 */
    public WikiPageRevision getRevision(String kbId, String pageID, int version) {
        WikiPageRevision rev = revisions.selectRevision(kbId, pageID, version);
        if (rev == null) {
            throw new WikiPageNotFoundException();
        }
        return rev;
    }

    /**
     * 软上限只碰来源可剪枝的快照，
     * 硬上限无视作者。pageID 为空是 no-op。
     */
    public void pruneRevisions(WikiRevisionPruneRequest req) {
        if (req.pageID() == null || req.pageID().isEmpty()) {
            return;
        }
        if (req.keepFromVersion() > 0
                && req.prunableSources() != null && !req.prunableSources().isEmpty()) {
            revisions.pruneBySources(req.pageID(), req.keepFromVersion(), req.prunableSources());
        }
        if (req.hardKeepFromVersion() > 0) {
            revisions.pruneBelowVersion(req.pageID(), req.hardKeepFromVersion());
        }
    }

    /**
     * 硬删整页历史。
     * pageID 为空是 no-op（否则会变成全表删除——原实现同款护栏 + 测试钉住）。
     */
    public void deleteRevisionsByPage(String pageID) {
        if (pageID == null || pageID.isEmpty()) {
            return;
        }
        revisions.deleteByPageId(pageID);
    }

    // ──────────────────────────── 页面问题 ────────────────────────────

    public void createIssue(WikiPageIssue issue) {
        OffsetDateTime now = OffsetDateTime.now();
        if (issue.getCreatedAt() == null) {
            issue.setCreatedAt(now);
        }
        if (issue.getUpdatedAt() == null) {
            issue.setUpdatedAt(now);
        }
        issues.insert(issue);
    }

    /** 可按 slug / status 过滤，created_at DESC */
    public List<WikiPageIssue> listIssues(String kbId, String slug, String status) {
        return issues.listIssues(kbId, slug == null ? "" : slug, status == null ? "" : status);
    }

    /** 单列更新，不判存在性 */
    public void updateIssueStatus(String issueID, String status) {
        issues.updateIssueStatus(issueID, status);
    }

    /** 文件夹名冲突判定辅助（原实现由唯一索引 + service 层负责；此处保留最小入口） */
    public boolean folderNameExists(String kbId, String parentID, String name) {
        return folders.selectChildByName(kbId, parentID, name) != null;
    }

    /** 供 service 在创建前显式判定冲突时抛错（与服务层的同名检查语义一致） */
    public void assertNoFolderConflict(String kbId, String parentID, String name) {
        if (folderNameExists(kbId, parentID, name)) {
            throw new WikiFolderConflictException();
        }
    }
}
