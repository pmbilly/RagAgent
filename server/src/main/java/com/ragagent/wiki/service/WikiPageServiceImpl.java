package com.ragagent.wiki.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderConflictException;
import com.ragagent.wiki.domain.WikiFolderNode;
import com.ragagent.wiki.domain.WikiFolderNotFoundException;
import com.ragagent.wiki.domain.WikiFolderNotEmptyException;
import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageLite;
import com.ragagent.wiki.domain.WikiPageListRequest;
import com.ragagent.wiki.domain.WikiPageListResponse;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.domain.WikiRevisionPruneRequest;
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.mapper.WikiPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * wiki 页面服务实现（对照 Go internal/application/service/wiki_page.go 全文，1913 行）。
 *
 * <p>方法名 = Go 方法名（首字母小写）。Go 的 {@code (value, error)} 在 Java 拆成
 * 「返回值 + 抛 {@link WikiException} 子类」；Go 的 {@code errors.Is(err, sentinel)}
 * 判别改成异常类型判别（sentinel 见 {@code com.ragagent.wiki.domain} 下的异常类）。</p>
 *
 * <h3>保真要点（逐条对照）</h3>
 * <ol>
 *   <li><b>版本号策略</b>（Go L121-197）：只有 title/content/summary/page_type/status/aliases
 *       真的变了才走 {@code updateWithRevision} 并递增 version；否则走 {@code updateMeta}。</li>
 *   <li><b>链接维护</b>（Go L1028-1240）：{@code parseOutLinks} 是纯字符串算法；
 *       {@code updateInLinks}/{@code removeInLinks} 逐目标页做读-改-写，目标不存在时静默跳过。</li>
 *   <li><b>图谱子集</b>：抽到 {@link WikiGraphCalculator}（Go 的包级纯函数 {@code computeGraphSubset}）。</li>
 *   <li><b>文件夹子树重算</b>（Go L1629-1744）：改名/移动后按 path 前缀重算整棵子树的
 *       path/depth，再重算子树下每个页面的缓存路径。</li>
 *   <li><b>chunk 同步 / 待处理任务计数 / Redis 活跃标志</b>：Go 用 nil 仓储/客户端表示
 *       「不接线」，Java 用可插拔端口 + 缺席时的同款降级（见各端口接口注释）。</li>
 * </ol>
 *
 * <h3>上下文</h3>
 * <p>Go 的 {@code types.WikiEditSourceFromContext(ctx)} / {@code UserIDFromContext(ctx)}
 * 在 Java 由 {@link WikiEditContext} + {@link com.ragagent.common.context.TenantContext}
 * 承担，因此 service 签名与 Go 保持一致（不带额外的来源参数）。</p>
 */
@Service
public class WikiPageServiceImpl implements WikiPageService {

    private static final Logger log = LoggerFactory.getLogger(WikiPageServiceImpl.class);

    /** 对照 Go {@code wikiLinkRegex}（L23）：{@code \[\[([^\]]+)\]\]} */
    static final Pattern WIKI_LINK_REGEX = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    /**
     * 对照 Go {@code wikiInlineChunkCitationRegex}（L29）：
     * {@code [ \t]*\[c\d{3,}(?:\s*[,;]\s*c\d{3,})*\]}。
     *
     * <p>这些是 ingest 提示词在分类支撑 chunk 时产生的<b>内部短句柄</b>；稳定的来源
     * 关系存在 {@code WikiPage.ChunkRefs} 里，句柄对读者没有意义，绝不能泄漏进
     * 生成的 Markdown。</p>
     */
    static final Pattern WIKI_INLINE_CHUNK_CITATION_REGEX =
            Pattern.compile("[ \\t]*\\[c\\d{3,}(?:\\s*[,;]\\s*c\\d{3,})*\\]");

    /** 对照 Go {@code wikiIndexContentPageTypes}（L443-449）：构成用户可见目录的页面类型 */
    static final List<String> WIKI_INDEX_CONTENT_PAGE_TYPES = List.of(
            WikiConstants.PAGE_TYPE_SUMMARY,
            WikiConstants.PAGE_TYPE_ENTITY,
            WikiConstants.PAGE_TYPE_CONCEPT,
            WikiConstants.PAGE_TYPE_SYNTHESIS,
            WikiConstants.PAGE_TYPE_COMPARISON);

    /** 对照 Go {@code wikiIndexGetIndex}} 里默认索引页的字面内容（L431-432） */
    static final String DEFAULT_INDEX_CONTENT =
            "# Wiki Index\n\nThis is the index page. It will be automatically updated as pages are added.\n";

    private final WikiPageRepository repo;
    private final KnowledgeBaseMapper kbMapper;
    private final ObjectProvider<WikiChunkCleaner> chunkCleaner;
    private final ObjectProvider<WikiCrossLinker> crossLinker;
    private final ObjectProvider<WikiPendingOpsCounter> pendingOps;
    private final ObjectProvider<WikiActiveFlag> activeFlag;

    public WikiPageServiceImpl(WikiPageRepository repo,
                               KnowledgeBaseMapper kbMapper,
                               ObjectProvider<WikiChunkCleaner> chunkCleaner,
                               ObjectProvider<WikiCrossLinker> crossLinker,
                               ObjectProvider<WikiPendingOpsCounter> pendingOps,
                               ObjectProvider<WikiActiveFlag> activeFlag) {
        this.repo = repo;
        this.kbMapper = kbMapper;
        this.chunkCleaner = chunkCleaner;
        this.crossLinker = crossLinker;
        this.pendingOps = pendingOps;
        this.activeFlag = activeFlag;
    }

    // ════════════════════════════ 页面写入 ════════════════════════════

    /** 对照 Go {@code CreatePage}（L70-109） */
    @Override
    public WikiPage createPage(WikiPage page) {
        if (page.getId() == null || page.getId().isEmpty()) {
            page.setId(UUID.randomUUID().toString());
        }
        if (page.getSlug().isEmpty()) {
            throw new WikiException("wiki page slug is required");
        }
        if (page.getKnowledgeBaseId().isEmpty()) {
            throw new WikiException("knowledge_base_id is required");
        }
        if (page.getStatus().isEmpty()) {
            page.setStatus(WikiConstants.STATUS_PUBLISHED);
        }
        if (page.getVersion() == 0) {
            page.setVersion(1);
        }
        page.setLastEditSource(WikiEditContext.currentEditSource());
        page.setLastEditorId(WikiEditContext.currentEditorId());
        stripWikiPageInlineChunkCitations(page);

        // 解析正文里的出链
        page.setOutLinks(parseOutLinks(page.getContent()));
        applyFolderToPage(page);
        normalizeWikiHierarchy(page);

        OffsetDateTime now = OffsetDateTime.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);

        repo.create(page);

        // 维护目标页的入链
        updateInLinks(page.getKnowledgeBaseId(), page.getSlug(), page.getOutLinks());
        return page;
    }

    /**
     * 对照 Go {@code UpdatePage}（L121-197）。
     *
     * <p>版本号只跟踪<b>用户可见</b>的内容修订，不是每一次行重写：只有
     * title / content / summary / page_type / status / aliases 至少一项真的变了才递增。
     * 纯记账写入（同内容重摄取时刷新 source_refs、同目录重建索引页、没替换任何东西的
     * 交叉链接注入……）仍会落库，但走 {@code UpdateMeta}、version 不动，这样消费方
     * 才能把「版本号变了」当作真实的编辑信号。</p>
     */
    @Override
    public WikiPage updatePage(WikiPage page) {
        WikiPage existing = repo.getBySlug(page.getKnowledgeBaseId(), page.getSlug());
        stripWikiPageInlineChunkCitations(page);

        List<String> oldOutLinks = existing.getOutLinks();

        // 在改动之前快照用户可见字段，好判断这是真的内容变更还是纯记账
        boolean contentChanged = !existing.getTitle().equals(page.getTitle())
                || !existing.getContent().equals(page.getContent())
                || !existing.getSummary().equals(page.getSummary())
                || !existing.getPageType().equals(page.getPageType())
                || !existing.getStatus().equals(page.getStatus())
                || !existing.getAliases().equals(page.getAliases());

        // 被取代版本的<b>未改动</b>副本：真的内容变更时它就是快照行
        WikiPage prev = copyPage(existing);

        existing.setTitle(page.getTitle());
        existing.setContent(page.getContent());
        existing.setSummary(page.getSummary());
        existing.setPageType(page.getPageType());
        existing.setAliases(new ArrayList<>(page.getAliases()));
        existing.setSourceRefs(page.getSourceRefs());
        existing.setChunkRefs(page.getChunkRefs());
        existing.setPageMetadata(page.getPageMetadata());
        existing.setParentSlug(page.getParentSlug());
        existing.setFolderId(page.getFolderId());
        existing.setSortOrder(page.getSortOrder());
        existing.setStatus(page.getStatus());
        existing.setUpdatedAt(OffsetDateTime.now());

        // CategoryPath 只是 FolderID 的派生缓存——从文件夹链重算，
        // 而不是相信调用方送来的值（Go L157-161）
        applyFolderToPage(existing);

        // 出链是正文的纯导数，所以只随正文变。无条件重解析以与库中正文保持一致。
        existing.setOutLinks(parseOutLinks(existing.getContent()));
        normalizeWikiHierarchy(existing);

        if (contentChanged) {
            // 新版本由驱动这次写入的人署名
            existing.setLastEditSource(WikiEditContext.currentEditSource());
            existing.setLastEditorId(WikiEditContext.currentEditorId());

            // 快照被取代的版本 + 原子写入新版本：每个历史版本的正文都被保住，
            // 且更新失败时不会留下半份快照（Go L174-179）
            repo.updateWithRevision(existing, revisionFromPage(prev));
            // 限制单页历史；尽力而为——剪枝失败只意味着多占一点存储，直到下次内容变更
            pruneRevisions(existing.getId(), existing.getVersion());
        } else {
            // 没有用户可见的变化——持久化记账字段但保留 version，
            // 让下游消费方能依赖它（Go L183-189）
            repo.updateMeta(existing);
        }

        // 入链：先摘旧的再加新的。内容没变时 oldOutLinks == existing.OutLinks，
        // 这两次调用实际都是 no-op（Go L191-194）
        removeInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), oldOutLinks);
        updateInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), existing.getOutLinks());

        return existing;
    }

    /** 对照 Go {@code UpdatePageMeta}（L200-204） */
    @Override
    public void updatePageMeta(WikiPage page) {
        normalizeWikiHierarchy(page);
        page.setUpdatedAt(OffsetDateTime.now());
        repo.updateMeta(page);
    }

    /**
     * 对照 Go {@code UpdateAutoLinkedContent}（L211-231）：持久化<b>机器侧</b>链接修饰
     * （交叉链接注入 / 死链清理）产生的正文，不递增 version。出链从新正文重解析、
     * 目标页的入链刷新，从而导航一致——只有面向用户的修订计数被保留。
     */
    @Override
    public void updateAutoLinkedContent(WikiPage page) {
        WikiPage existing = repo.getBySlug(page.getKnowledgeBaseId(), page.getSlug());
        List<String> oldOutLinks = existing.getOutLinks();

        existing.setContent(stripWikiInlineChunkCitations(page.getContent()));
        existing.setOutLinks(parseOutLinks(existing.getContent()));
        existing.setUpdatedAt(OffsetDateTime.now());

        repo.updateAutoLinkedContent(existing);

        removeInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), oldOutLinks);
        updateInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), existing.getOutLinks());
    }

    /**
     * 对照 Go {@code revisionFromPage}（L237-256）：为给定页面状态构造不可变快照行。
     *
     * <p>快照上的 {@code editSource} 是<b>那个版本</b>的作者——该版本尚为当前版本时
     * 页面溯源列的值——而不是取代它的这次写入的作者。</p>
     */
    static WikiPageRevision revisionFromPage(WikiPage p) {
        WikiPageRevision rev = new WikiPageRevision();
        rev.setId(UUID.randomUUID().toString());
        rev.setTenantId(p.getTenantId());
        rev.setKnowledgeBaseId(p.getKnowledgeBaseId());
        rev.setPageId(p.getId());
        rev.setSlug(p.getSlug());
        rev.setVersion(p.getVersion());
        rev.setTitle(p.getTitle());
        rev.setPageType(p.getPageType());
        rev.setStatus(p.getStatus());
        rev.setContent(p.getContent());
        rev.setSummary(p.getSummary());
        rev.setAliases(new ArrayList<>(p.getAliases()));
        rev.setEditSource(WikiConstants.normalizeEditSource(p.getLastEditSource()));
        rev.setEditorId(p.getLastEditorId());
        rev.setEditedAt(p.getUpdatedAt());
        rev.setCreatedAt(OffsetDateTime.now());
        return rev;
    }

    /**
     * 对照 Go {@code pruneRevisions}（L262-275）：页面推进到 currentVersion 之后限制其
     * 快照历史。机器作者的快照一旦滑出近期窗口就丢；人工/agent/回滚的快照活到硬上限
     * ——这样热页上的管道churn 挤不掉用户真正在意的编辑。
     */
    private void pruneRevisions(String pageId, int currentVersion) {
        WikiRevisionPruneRequest req = new WikiRevisionPruneRequest(
                pageId,
                currentVersion - WikiConstants.MAX_REVISIONS_PER_PAGE,
                WikiConstants.PRUNABLE_EDIT_SOURCES,
                currentVersion - WikiConstants.MAX_REVISIONS_HARD_CAP);
        if (req.keepFromVersion() <= 0 && req.hardKeepFromVersion() <= 0) {
            return;
        }
        try {
            repo.pruneRevisions(req);
        } catch (RuntimeException e) {
            log.warn("prune wiki page revisions for {} failed: {}", pageId, e.toString());
        }
    }

    // ════════════════════════════ 页面读取 ════════════════════════════

    /** 对照 Go {@code GetPageBySlug}（L345-352） */
    @Override
    public WikiPage getPageBySlug(String kbId, String slug) {
        WikiPage page = repo.getBySlug(kbId, slug);
        stripWikiPageInlineChunkCitations(page);
        return page;
    }

    /** 对照 Go {@code GetPageByID}（L355-362） */
    @Override
    public WikiPage getPageByID(String id) {
        WikiPage page = repo.getByID(id);
        stripWikiPageInlineChunkCitations(page);
        return page;
    }

    /** 对照 Go {@code ListPages}（L365-395） */
    @Override
    public WikiPageListResponse listPages(WikiPageListRequest req) {
        WikiPageRepository.PageList result = repo.list(req);
        List<WikiPage> pages = result.pages();
        for (WikiPage page : pages) {
            stripWikiPageInlineChunkCitations(page);
            normalizeWikiHierarchy(page);
        }

        int pageSize = req.getPageSize();
        if (pageSize < 1) {
            pageSize = 20;
        }
        int page = req.getPage();
        if (page < 1) {
            page = 1;
        }
        int totalPages = (int) (result.total() / pageSize);
        if ((int) (result.total() % pageSize) > 0) {
            totalPages++;
        }

        WikiPageListResponse resp = new WikiPageListResponse();
        resp.setPages(pages);
        resp.setTotal(result.total());
        resp.setPage(page);
        resp.setPageSize(pageSize);
        resp.setTotalPages(totalPages);
        return resp;
    }

    /** 对照 Go {@code DeletePage}（L398-423） */
    @Override
    public void deletePage(String kbId, String slug) {
        WikiPage page = repo.getBySlug(kbId, slug);

        // 摘掉本页指向的那些目标页上的入链引用
        removeInLinks(kbId, slug, page.getOutLinks());

        repo.delete(kbId, slug);

        // 快照历史一并丢掉：页面已从所有读路径消失，它们的快照是不可达的行，
        // 却占着完整正文。尽力而为——页面已经删了，回滚不了。
        try {
            repo.deleteRevisionsByPage(page.getId());
        } catch (RuntimeException e) {
            log.warn("delete wiki page revisions for {} failed: {}", page.getId(), e.toString());
        }

        deleteChunkForPage(page);
    }

    /** 对照 Go {@code GetIndex}（L426-437） */
    @Override
    public WikiPage getIndex(String kbId) {
        try {
            return repo.getBySlug(kbId, "index");
        } catch (WikiPageNotFoundException e) {
            // 建默认索引页
            return createDefaultPage(kbId, "index", "Index", WikiConstants.PAGE_TYPE_INDEX,
                    DEFAULT_INDEX_CONTENT);
        }
    }

    /**
     * 对照 Go {@code GetIndexView}（L466-545）：结构性索引响应——intro（来自索引行）
     * + 每个 page_type 一个分页窗口。
     *
     * <p>目录不再被物化成多兆的 markdown 字符串；每个类型独立用
     * {@code ListByTypeLight} 分页，读放大是 O(page_size) 而不是 O(KB 总页数)。</p>
     */
    @Override
    public WikiIndex.Response getIndexView(String kbId, List<String> pageTypes, int limit,
                                           String cursor) {
        WikiPage indexPage;
        try {
            indexPage = getIndex(kbId);
        } catch (RuntimeException e) {
            throw new WikiException("load index page: " + e.getMessage(), e);
        }

        int lim = limit;
        if (lim <= 0) {
            lim = 50;
        }
        if (lim > 200) {
            lim = 200;
        }
        int offset = 0;
        if (cursor != null && !cursor.isEmpty()) {
            int v;
            try {
                v = Integer.parseInt(cursor);
            } catch (NumberFormatException e) {
                throw new WikiException("invalid cursor \"" + cursor + "\"");
            }
            if (v < 0) {
                throw new WikiException("invalid cursor \"" + cursor + "\"");
            }
            offset = v;
        }

        // 调用方不传过滤时默认取全部已知内容类型。请求时传入的未知类型<b>原样透传</b>，
        // 这样将来新版 Go 声明的页面类型一被 LLM 创建就能出现在索引里，无需改 handler。
        List<String> selected = pageTypes;
        if (selected == null || selected.isEmpty()) {
            selected = new ArrayList<>(WIKI_INDEX_CONTENT_PAGE_TYPES);
        }

        List<WikiIndex.Group> groups = new ArrayList<>(selected.size());
        for (String pt : selected) {
            WikiPageRepository.LightList listed;
            try {
                listed = repo.listByTypeLight(kbId, pt, lim, offset);
            } catch (RuntimeException e) {
                throw new WikiException("list " + pt + " pages: " + e.getMessage(), e);
            }
            List<WikiIndexEntry> entries = listed.entries();
            for (WikiIndexEntry entry : entries) {
                normalizeWikiIndexEntryHierarchy(entry, pt);
            }
            String next = "";
            // 只有返回了完整一页<b>且</b> offset+limit 之后还有行时才给 cursor。
            // 短页、或恰好把余量吃完的一页，都应表达「流结束」。
            if (entries.size() == lim && (long) (offset + entries.size()) < listed.total()) {
                next = Integer.toString(offset + lim);
            }
            WikiIndex.Group group = new WikiIndex.Group();
            group.setType(pt);
            group.setTotal(listed.total());
            group.setItems(entries);
            group.setNextCursor(next);
            groups.add(group);
        }

        // intro 原先存在 indexPage.Summary 上而 indexPage.Content 里是 intro + 目录 markdown。
        // 目录从 wiki_pages 里搬走之后，content 列只剩 intro。为那些改版后还没重新
        // 摄取的 KB 回落到 Summary，保证响应永不为空（Go L531-538）。
        String intro = indexPage.getContent();
        if (intro.trim().isEmpty()) {
            intro = indexPage.getSummary();
        }

        WikiIndex.Response resp = new WikiIndex.Response();
        resp.setIntro(intro);
        resp.setVersion(indexPage.getVersion());
        resp.setGroups(groups);
        return resp;
    }

    /** 对照 Go {@code GetGraph}（L576-586） */
    @Override
    public WikiGraph.Data getGraph(WikiGraph.Request req) {
        if (req == null) {
            throw new WikiException("wiki graph request is required");
        }
        return WikiGraphCalculator.compute(repo.listAll(req.knowledgeBaseId()), req);
    }

    /** 对照 Go {@code GetStats}（L813-881） */
    @Override
    public WikiStats getStats(String kbId) {
        Map<String, Long> counts = repo.countByType(kbId);
        long total = 0;
        for (Long c : counts.values()) {
            total += c;
        }

        long orphans = repo.countOrphans(kbId);

        // 统计总链接数
        List<WikiPage> pages = repo.listAll(kbId);
        long totalLinks = 0;
        for (WikiPage p : pages) {
            totalLinks += p.getOutLinks().size();
        }

        // 最近更新（前 10 条）
        WikiPageListRequest listReq = new WikiPageListRequest();
        listReq.setKnowledgeBaseId(kbId);
        listReq.setPage(1);
        listReq.setPageSize(10);
        listReq.setSortBy("updated_at");
        listReq.setSortOrder("desc");
        List<WikiPage> recentPages = repo.list(listReq).pages();

        long pendingTasks = 0;
        boolean isActive = false;
        WikiPendingOpsCounter pending = pendingOps.getIfAvailable();
        if (pending != null) {
            // 待处理的 wiki 摄取任务在 task_pending_ops 里，键为
            // (task_type="wiki:ingest", scope="knowledge_base", scope_id=kbID)。
            // Go 忽略这里的错误（pendingTasks 保持 0）。
            try {
                pendingTasks = pending.pendingCount(WikiPendingOpsCounter.TASK_TYPE_WIKI_INGEST,
                        WikiPendingOpsCounter.SCOPE_KNOWLEDGE_BASE, kbId);
            } catch (RuntimeException e) {
                log.warn("wiki stats: pending count for KB {} failed: {}", kbId, e.toString());
            }
        }
        WikiActiveFlag flag = activeFlag.getIfAvailable();
        if (flag != null) {
            // "批次进行中"标志仍是 Redis 独有的短命信号（带 TTL 续期的进程锁）；
            // 它不承载持久状态，不值得迁移。
            try {
                isActive = flag.isActive(kbId);
            } catch (RuntimeException e) {
                log.warn("wiki stats: active flag for KB {} failed: {}", kbId, e.toString());
            }
        }

        long pendingIssues = 0;
        try {
            pendingIssues = listIssues(kbId, "", "pending").size();
        } catch (RuntimeException e) {
            log.warn("wiki stats: list pending issues for KB {} failed: {}", kbId, e.toString());
        }

        WikiStats stats = new WikiStats();
        stats.setTotalPages(total);
        stats.setPagesByType(counts);
        stats.setTotalLinks(totalLinks);
        stats.setOrphanCount(orphans);
        stats.setRecentUpdates(recentPages);
        stats.setPendingTasks(pendingTasks);
        stats.setPendingIssues(pendingIssues);
        stats.setActive(isActive);
        return stats;
    }

    /** 对照 Go {@code RebuildLinks}（L884-920） */
    @Override
    public void rebuildLinks(String kbId) {
        List<WikiPage> pages = repo.listAll(kbId);

        Map<String, WikiPage> pageMap = new LinkedHashMap<>();
        for (WikiPage p : pages) {
            pageMap.put(p.getSlug(), p);
        }

        // 先清空所有入链
        for (WikiPage p : pages) {
            p.setInLinks(new ArrayList<>());
        }

        // 重解析出链并重建入链
        for (WikiPage p : pages) {
            p.setOutLinks(parseOutLinks(p.getContent()));
            for (String target : p.getOutLinks()) {
                WikiPage tp = pageMap.get(target);
                if (tp != null) {
                    tp.getInLinks().add(p.getSlug());
                }
            }
        }

        // 全量保存（链接重建是纯元数据，不动版本号）
        for (WikiPage p : pages) {
            p.setUpdatedAt(OffsetDateTime.now());
            try {
                repo.updateMeta(p);
            } catch (RuntimeException e) {
                log.warn("wiki: failed to update links for page {}: {}", p.getSlug(), e.toString());
            }
        }
    }

    /** 对照 Go {@code InjectCrossLinks}（L1852-1892） */
    @Override
    public void injectCrossLinks(String kbId, List<String> affectedSlugs) {
        List<WikiPage> allPages;
        try {
            allPages = listAllPages(kbId);
        } catch (RuntimeException e) {
            return;
        }
        if (allPages.size() < 2) {
            return;
        }

        List<WikiCrossLinker.LinkRef> refs = WikiCrossLinker.collectRefs(allPages);
        if (refs.isEmpty()) {
            return;
        }

        Set<String> affectedSet = new HashSet<>();
        if (affectedSlugs != null) {
            affectedSet.addAll(affectedSlugs);
        }

        WikiCrossLinker linker = crossLinker.getIfAvailable(WikiCrossLinker.Noop::new);
        int updated = 0;
        for (WikiPage p : allPages) {
            if (!affectedSet.contains(p.getSlug())) {
                continue;
            }
            if (WikiConstants.PAGE_TYPE_INDEX.equals(p.getPageType())) {
                continue;
            }

            WikiCrossLinker.LinkifyResult r = linker.linkify(p.getContent(), refs, p.getSlug());
            if (!r.changed()) {
                continue;
            }
            p.setContent(r.content());
            try {
                updateAutoLinkedContent(p);
            } catch (RuntimeException e) {
                log.warn("wiki: cross-link injection failed for {}: {}", p.getSlug(), e.toString());
                continue;
            }
            updated++;
        }

        if (updated > 0) {
            log.info("wiki: injected cross-links in {} pages", updated);
        }
    }

    /**
     * 对照 Go {@code RebuildIndexPage}（L1909-1913）。
     *
     * <p>Go 侧正文<b>故意是 no-op</b>——目录不再持久化进 wiki_pages.content，
     * 改由 GetIndexView 从 ListByTypeLight 轻投影按需拼装，因此单个页面写入
     * 不必再做 O(N) 字符串拼接、重写多兆 TEXT 列。保留方法名只为让既有 agent
     * 工具调用点（wiki_write_page / wiki_rename_page）编译不变。</p>
     *
     * <p>索引行上仍留着的 intro 由 ingest 管道在批次完成时单独维护
     * （wikiIngestService.rebuildIndexPage），那里才真正有 LLM + 变更描述上下文。</p>
     */
    @Override
    public void rebuildIndexPage(String kbId) {
        // intentionally no-op（对照 Go L1909-1913）
    }

    // ════════════════════════════ 批量读取 ════════════════════════════

    /** 对照 Go {@code ListAllPages}（L923-925） */
    @Override
    public List<WikiPage> listAllPages(String kbId) {
        return repo.listAll(kbId);
    }

    /** 对照 Go {@code ListByType}（L930-932） */
    @Override
    public List<WikiPage> listByType(String kbId, String pageType) {
        return repo.listByType(kbId, pageType);
    }

    /** 对照 Go {@code ListPagesBySourceRef}（L937-939） */
    @Override
    public List<WikiPage> listPagesBySourceRef(String kbId, String knowledgeID) {
        return repo.listBySourceRef(kbId, knowledgeID);
    }

    /** 对照 Go {@code ListSlugsBySourceRef}（L945-947） */
    @Override
    public List<String> listSlugsBySourceRef(String kbId, String knowledgeID) {
        return repo.listSlugsBySourceRef(kbId, knowledgeID);
    }

    /** 对照 Go {@code ListBySlugs}（L954-956） */
    @Override
    public Map<String, WikiPageLite> listBySlugs(String kbId, List<String> slugs) {
        return repo.listBySlugs(kbId, slugs);
    }

    /** 对照 Go {@code ListSummariesByKnowledgeIDs}（L961-963） */
    @Override
    public Map<String, String> listSummariesByKnowledgeIDs(String kbId, List<String> kids) {
        return repo.listSummariesByKnowledgeIDs(kbId, kids);
    }

    /** 对照 Go {@code ExistsSlugs}（L968-970） */
    @Override
    public Map<String, Boolean> existsSlugs(String kbId, List<String> slugs) {
        return repo.existsSlugs(kbId, slugs);
    }

    /** 对照 Go {@code ListAllSlugs}（L975-977） */
    @Override
    public List<String> listAllSlugs(String kbId) {
        return repo.listAllSlugs(kbId);
    }

    /** 对照 Go {@code ListPagesCursor}（L980-982） */
    @Override
    public CursorPage listPagesCursor(String kbId, String cursor, int limit) {
        WikiPageRepository.CursorPage page = repo.listPagesCursor(kbId, cursor, limit);
        return new CursorPage(page.pages(), page.nextCursor());
    }

    /** 对照 Go {@code ListByTypeRecent}（L986-988） */
    @Override
    public List<WikiIndexEntry> listByTypeRecent(String kbId, String pageType, int limit) {
        return repo.listByTypeRecent(kbId, pageType, limit);
    }

    /** 对照 Go {@code FindSimilarPages}（L992-994） */
    @Override
    public List<WikiPageLite> findSimilarPages(String kbId, String query, List<String> pageTypes,
                                               int limit) {
        return repo.findSimilarPages(kbId, query, pageTypes, limit);
    }

    /** 对照 Go {@code FindPagesByNormalizedTitle}（L998-1000） */
    @Override
    public List<WikiPageLite> findPagesByNormalizedTitle(String kbId, String pageType,
                                                         String identity) {
        return repo.findPagesByNormalizedTitle(kbId, pageType, identity);
    }

    /** 对照 Go {@code FindPagesByNormalizedTitles}（L1004-1006） */
    @Override
    public List<WikiPageLite> findPagesByNormalizedTitles(String kbId, String pageType,
                                                          List<String> identities) {
        return repo.findPagesByNormalizedTitles(kbId, pageType, identities);
    }

    /** 对照 Go {@code ListDistinctCategoryPaths}（L1010-1012） */
    @Override
    public List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths) {
        return repo.listDistinctCategoryPaths(kbId, maxPaths);
    }

    /** 对照 Go {@code CountByType}（L1016-1018） */
    @Override
    public Map<String, Long> countByType(String kbId) {
        return repo.countByType(kbId);
    }

    /** 对照 Go {@code SearchPages}（L1021-1023） */
    @Override
    public List<WikiPage> searchPages(String kbId, String query, int limit) {
        return repo.search(kbId, query, limit);
    }

    // ════════════════════════ 链接解析 / 修复 ════════════════════════

    /**
     * 对照 Go {@code parseOutLinks}（L1028-1048）：从 markdown 正文提取
     * {@code [[wiki-link]]} 的 slug。
     *
     * <p>去重（保首次出现序）；支持 {@code [[slug|显示名]]} 只取竖线前那段；
     * slug 经 {@link #normalizeSlug} 归一。</p>
     */
    static List<String> parseOutLinks(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        Matcher m = WIKI_LINK_REGEX.matcher(content);
        Set<String> seen = new LinkedHashSet<>();
        List<String> links = new ArrayList<>();
        while (m.find()) {
            String slug = m.group(1).trim();
            // 处理 [[slug|display name]]——slug 是竖线前那段
            int pipe = slug.indexOf('|');
            if (pipe >= 0) {
                slug = slug.substring(0, pipe).trim();
            }
            slug = normalizeSlug(slug);
            if (!slug.isEmpty() && seen.add(slug)) {
                links.add(slug);
            }
        }
        return links;
    }

    /**
     * 对照 Go {@code normalizeSlug}（L1051-1055）：小写 + 去首尾空白 + 空格换成连字符。
     *
     * <p>Go 的 {@code strings.ToLower} 是 Unicode 简单折叠；Java 用
     * {@code Locale.ROOT} 避免土耳其语 i 之类的区域陷阱（与仓库其它地方一致）。</p>
     */
    static String normalizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        return slug.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "-");
    }

    /**
     * 对照 Go {@code slugNamespace}（L1059-1064）：取 slug 第一个 {@code '/'} 之前的前缀，
     * 例如 {@code "summary/abc" -> "summary"}；没有 {@code '/'} 的 slug 映射为 {@code ""}。
     */
    static String slugNamespace(String slug) {
        if (slug == null) {
            return "";
        }
        int i = slug.indexOf('/');
        return i >= 0 ? slug.substring(0, i) : "";
    }

    /**
     * 对照 Go {@code RepairContentLinks}（L1121-1200）：把 {@code content} 里指向
     * <b>不存在的页面</b>、但几乎肯定是真实页面被弄花形式的 {@code [[slug]]} /
     * {@code [[slug|display]]} 引用重写掉。
     *
     * <p>典型场景：LLM 重打了一遍摘要页的 UUID slug 并插入/漏掉一个十六进制位
     * （{@code summary/…06fb5d5b5b5e → summary/…06fb14d5b14b14e}），产生一个 404
     * 且永远不可能被精确查回的死链。</p>
     *
     * <p>与 {@code stripDeadWikiLinks}（ingest 清理通道）不同，本方法是
     * <b>只重写</b>的：只有当存在高置信的活跃候选时才纠正死链，否则原样保留。
     * 它<b>绝不</b>把链接剥成纯文本，所以对任何写入路径都安全——包括目标确实
     * 还不存在的写入（那些就原样留着，等到目标出现为止）。</p>
     *
     * <p>每条死链的候选池被限定在<b>同一命名空间前缀</b>的活跃 slug（死的
     * {@code summary/<uuid>} 只会在活跃的 {@code summary/*} 里解析）。限定命名空间
     * 让 bigram 相似度这根杠杆保持安全：同命名空间内互不相同的高熵 UUID 不会碰撞，
     * 而错一位的变形与它真正的来源仍稳稳高于阈值。</p>
     */
    @Override
    public RepairResult repairContentLinks(String kbId, String selfSlug, String content) {
        if (content == null || content.trim().isEmpty()) {
            return new RepairResult(content, false);
        }
        List<String> outLinks = parseOutLinks(content);
        if (outLinks.isEmpty()) {
            return new RepairResult(content, false);
        }

        Map<String, Boolean> existMap = repo.existsSlugs(kbId, outLinks);
        Set<String> deadPrefixes = new LinkedHashSet<>();
        for (String l : outLinks) {
            if (l.equals(selfSlug) || Boolean.TRUE.equals(existMap.get(l))) {
                continue;
            }
            deadPrefixes.add(slugNamespace(l));
        }
        if (deadPrefixes.isEmpty()) {
            return new RepairResult(content, false);
        }

        List<String> allSlugs = repo.listAllSlugs(kbId);
        Map<String, Set<String>> liveByPrefix = new LinkedHashMap<>();
        List<String> candidateSlugs = new ArrayList<>();
        for (String sl : allSlugs) {
            String ns = slugNamespace(sl);
            if (!deadPrefixes.contains(ns)) {
                continue;
            }
            liveByPrefix.computeIfAbsent(ns, k -> new LinkedHashSet<>()).add(sl);
            candidateSlugs.add(sl);
        }
        if (candidateSlugs.isEmpty()) {
            return new RepairResult(content, false);
        }

        // 为候选页建立 title -> slug 反查，好让 display 文本这根杠杆
        // （最安全、最精确的一根）能生效。范围限定在相关命名空间，所以在
        // 大 KB 上依然廉价。
        Map<String, String> titleToSlug = new LinkedHashMap<>();
        try {
            Map<String, WikiPageLite> lites = repo.listBySlugs(kbId, candidateSlugs);
            for (WikiPageLite lp : lites.values()) {
                if (lp != null && !lp.getTitle().isEmpty()) {
                    titleToSlug.put(lp.getTitle(), lp.getSlug());
                }
            }
        } catch (RuntimeException ignored) {
            // 对照 Go：`if lites, lerr := ...; lerr == nil { ... }`——反查失败只是
            // 少一根杠杆，不阻断修复
        }

        Map<String, String> resolveCache = new LinkedHashMap<>();
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(content, (norm, display) -> {
            if (norm.equals(selfSlug) || Boolean.TRUE.equals(existMap.get(norm))) {
                return null;
            }
            String key = norm + " " + display;
            if (resolveCache.containsKey(key)) {
                String cached = resolveCache.get(key);
                return cached.isEmpty() ? null : cached;
            }
            String resolved = SlugFuzzy.resolveDeadSlug(norm, display,
                    liveByPrefix.getOrDefault(slugNamespace(norm), Set.of()), titleToSlug);
            if (resolved == null || resolved.equals(norm)) {
                resolveCache.put(key, "");
                return null;
            }
            resolveCache.put(key, resolved);
            return resolved;
        });
        return new RepairResult(r.content(), r.changed());
    }

    // ════════════════════════════ 内部工具 ════════════════════════════

    /** 对照 Go {@code stripWikiInlineChunkCitations}（L31-33） */
    static String stripWikiInlineChunkCitations(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return WIKI_INLINE_CHUNK_CITATION_REGEX.matcher(content).replaceAll("");
    }

    /** 对照 Go {@code stripWikiPageInlineChunkCitations}（L35-41） */
    static void stripWikiPageInlineChunkCitations(WikiPage page) {
        if (page == null) {
            return;
        }
        page.setContent(stripWikiInlineChunkCitations(page.getContent()));
        page.setSummary(stripWikiInlineChunkCitations(page.getSummary()));
    }

    /**
     * 对照 Go {@code updateInLinks}（L1203-1217）：把源 slug 加到目标页的 in_links。
     * 目标页不存在时静默跳过（还没建出来）。
     */
    private void updateInLinks(String kbId, String sourceSlug, List<String> targets) {
        for (String targetSlug : targets) {
            WikiPage targetPage;
            try {
                targetPage = repo.getBySlug(kbId, targetSlug);
            } catch (RuntimeException e) {
                continue; // target page may not exist yet
            }
            if (!targetPage.getInLinks().contains(sourceSlug)) {
                targetPage.getInLinks().add(sourceSlug);
                targetPage.setUpdatedAt(OffsetDateTime.now());
                try {
                    repo.updateMeta(targetPage);
                } catch (RuntimeException e) {
                    log.warn("wiki: failed to update in_links for {}: {}", targetSlug, e.toString());
                }
            }
        }
    }

    /** 对照 Go {@code removeInLinks}（L1220-1235） */
    private void removeInLinks(String kbId, String sourceSlug, List<String> targets) {
        for (String targetSlug : targets) {
            WikiPage targetPage;
            try {
                targetPage = repo.getBySlug(kbId, targetSlug);
            } catch (RuntimeException e) {
                continue;
            }
            List<String> newInLinks = removeString(targetPage.getInLinks(), sourceSlug);
            if (newInLinks.size() != targetPage.getInLinks().size()) {
                targetPage.setInLinks(newInLinks);
                targetPage.setUpdatedAt(OffsetDateTime.now());
                try {
                    repo.updateMeta(targetPage);
                } catch (RuntimeException e) {
                    log.warn("wiki: failed to update in_links for {}: {}", targetSlug, e.toString());
                }
            }
        }
    }

    /**
     * 对照 Go {@code deleteChunkForPage}（L1240-1248）：删掉页面同步出去的 chunk。
     * chunk 同步是可选接线——没装 chunk 仓储的 service 直接跳过，而不是让删除连带失败。
     */
    private void deleteChunkForPage(WikiPage page) {
        WikiChunkCleaner cleaner = chunkCleaner.getIfAvailable();
        if (cleaner == null) {
            return;
        }
        try {
            cleaner.deleteWikiPageChunk(page.getTenantId(),
                    WikiChunkCleaner.chunkIdFor(page.getId()));
        } catch (RuntimeException e) {
            log.warn("wiki: failed to delete chunk for page {}: {}", page.getSlug(), e.toString());
        }
    }

    /** 对照 Go {@code createDefaultPage}（L1251-1276） */
    private WikiPage createDefaultPage(String kbId, String slug, String title, String pageType,
                                       String content) {
        KnowledgeBase kb = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .last("LIMIT 1"));
        if (kb == null) {
            throw new WikiException("get knowledge base: knowledge base not found");
        }

        WikiPage page = new WikiPage();
        page.setId(UUID.randomUUID().toString());
        page.setTenantId(kb.getTenantId());
        page.setKnowledgeBaseId(kbId);
        page.setSlug(slug);
        page.setTitle(title);
        page.setPageType(pageType);
        page.setStatus(WikiConstants.STATUS_PUBLISHED);
        page.setContent(content);
        page.setSummary(title);
        page.setVersion(1);
        normalizeWikiHierarchy(page);

        repo.create(page);
        return page;
    }

    /** 对照 Go {@code normalizeWikiHierarchy}（L1278-1302） */
    static void normalizeWikiHierarchy(WikiPage page) {
        if (page == null) {
            return;
        }
        page.setParentSlug(page.getParentSlug().trim());

        // 归入文件夹的页面精确镜像文件夹树：它的 category_path 派生自已校验的文件夹路径，
        // 所以模型噪声清洗（会丢掉 "概念" 这类类型标签）不得改写它。只有没有文件夹的
        // 页面才带模型自造的分类标签。
        List<String> cleanPath;
        if (!page.getFolderId().trim().isEmpty()) {
            cleanPath = WikiCategoryPaths.trimFolderSegments(page.getCategoryPath());
        } else {
            cleanPath = WikiCategoryPaths.cleanCategoryPath(page.getCategoryPath());
        }
        page.setCategoryPath(cleanPath);
        page.setDepth(cleanPath.size());

        String display = page.getTitle().trim();
        if (display.isEmpty()) {
            display = page.getSlug().trim();
        }
        page.setWikiPath(buildWikiPath(page.getPageType(), cleanPath, display));
    }

    /** 对照 Go {@code normalizeWikiIndexEntryHierarchy}（L1304-1318） */
    static void normalizeWikiIndexEntryHierarchy(WikiIndexEntry entry, String pageType) {
        if (entry == null) {
            return;
        }
        List<String> cleanPath = WikiCategoryPaths.cleanCategoryPath(entry.getCategoryPath());
        entry.setCategoryPath(cleanPath);
        entry.setDepth(cleanPath.size());

        String display = entry.getTitle().trim();
        if (display.isEmpty()) {
            display = entry.getSlug().trim();
        }
        entry.setWikiPath(buildWikiPath(pageType, cleanPath, display));
    }

    /**
     * 对照 Go {@code buildWikiPath}（L1322-1332）：拼出规范化、可排序的
     * {@code "page_type/cat.../title"} 面包屑。空段跳过。
     */
    static String buildWikiPath(String pageType, List<String> categoryPath, String display) {
        List<String> parts = new ArrayList<>(categoryPath.size() + 2);
        String pt = pageType == null ? "" : pageType.trim();
        if (!pt.isEmpty()) {
            parts.add(pt);
        }
        parts.addAll(categoryPath);
        if (display != null && !display.isEmpty()) {
            parts.add(display);
        }
        return String.join("/", parts);
    }

    /** 对照 Go {@code containsString}（L1335-1342） */
    static boolean containsString(List<String> slice, String s) {
        return slice != null && slice.contains(s);
    }

    /** 对照 Go {@code removeString}（L1345-1353）：移除<b>所有</b>等于 s 的元素 */
    static List<String> removeString(List<String> slice, String s) {
        List<String> result = new ArrayList<>(slice == null ? 0 : slice.size());
        if (slice != null) {
            for (String v : slice) {
                if (!v.equals(s)) {
                    result.add(v);
                }
            }
        }
        return result;
    }

    /** 浅拷贝一份页面（对照 Go 的 {@code prev := *existing}），列表字段另起一份 */
    static WikiPage copyPage(WikiPage p) {
        WikiPage c = new WikiPage();
        c.setId(p.getId());
        c.setTenantId(p.getTenantId());
        c.setKnowledgeBaseId(p.getKnowledgeBaseId());
        c.setSlug(p.getSlug());
        c.setTitle(p.getTitle());
        c.setPageType(p.getPageType());
        c.setStatus(p.getStatus());
        c.setContent(p.getContent());
        c.setSummary(p.getSummary());
        c.setAliases(new ArrayList<>(p.getAliases()));
        c.setParentSlug(p.getParentSlug());
        c.setFolderId(p.getFolderId());
        c.setCategoryPath(new ArrayList<>(p.getCategoryPath()));
        c.setWikiPath(p.getWikiPath());
        c.setDepth(p.getDepth());
        c.setSortOrder(p.getSortOrder());
        c.setSourceRefs(new ArrayList<>(p.getSourceRefs()));
        c.setChunkRefs(new ArrayList<>(p.getChunkRefs()));
        c.setInLinks(new ArrayList<>(p.getInLinks()));
        c.setOutLinks(new ArrayList<>(p.getOutLinks()));
        c.setPageMetadata(p.getPageMetadata());
        c.setVersion(p.getVersion());
        c.setLastEditSource(p.getLastEditSource());
        c.setLastEditorId(p.getLastEditorId());
        c.setCreatedAt(p.getCreatedAt());
        c.setUpdatedAt(p.getUpdatedAt());
        c.setDeletedAt(p.getDeletedAt());
        return c;
    }

    // ════════════════════════════ 修订历史 ════════════════════════════

    /**
     * 对照 Go {@code ListRevisions}（L284-300）：某页面存下来的历史快照
     * （最新在前，<b>省略 content</b>）+ 快照总数 + 页面当前版本。
     *
     * <p>当前版本本身<b>没有</b>快照行——它活在 wiki_pages 里。</p>
     */
    @Override
    public WikiPageRevisionListResponse listRevisions(String kbId, String slug, int limit,
                                                      int offset) {
        WikiPage page = repo.getBySlug(kbId, slug);
        WikiPageRepository.RevisionList listed;
        try {
            listed = repo.listRevisions(kbId, page.getId(), limit, offset);
        } catch (RuntimeException e) {
            throw new WikiException("list wiki page revisions: " + e.getMessage(), e);
        }
        WikiPageRevisionListResponse resp = new WikiPageRevisionListResponse();
        resp.setRevisions(listed.revisions());
        resp.setTotal(listed.total());
        resp.setCurrentVersion(page.getVersion());
        return resp;
    }

    /** 对照 Go {@code GetRevision}（L303-311）：单条历史快照，含 content */
    @Override
    public WikiPageRevision getRevision(String kbId, String slug, int version) {
        WikiPage page = repo.getBySlug(kbId, slug);
        return repo.getRevision(kbId, page.getId(), version);
    }

    /**
     * 对照 Go {@code RevertPageToVersion}（L318-342）：把页面回滚到某份快照的正文内容，
     * 并以<b>一次普通编辑</b>的形式应用它——回滚前的状态会被快照、版本号前进、链接重解析。
     *
     * <p>位置（文件夹、排序权重）与溯源引用保持当前值——回滚是关于内容的，
     * 不是撤销目录移动。</p>
     */
    @Override
    public WikiPage revertPageToVersion(String kbId, String slug, int version) {
        WikiPage page = repo.getBySlug(kbId, slug);
        if (version == page.getVersion()) {
            throw new WikiRevertToCurrentVersionException();
        }
        WikiPageRevision rev = repo.getRevision(kbId, page.getId(), version);

        WikiPage target = copyPage(page);
        target.setTitle(rev.getTitle());
        target.setContent(rev.getContent());
        target.setSummary(rev.getSummary());
        target.setPageType(rev.getPageType());
        target.setStatus(rev.getStatus());
        target.setAliases(new ArrayList<>(rev.getAliases()));

        // 对照 Go：types.WithWikiEditSource(ctx, WikiEditSourceRevert) 包裹这次 UpdatePage
        return WikiEditContext.callWith(WikiConstants.EDIT_SOURCE_REVERT,
                () -> updatePage(target));
    }

    // ════════════════════════════ 页面问题 ════════════════════════════

    /** 对照 Go {@code CreateIssue}（L1356-1364） */
    @Override
    public WikiPageIssue createIssue(WikiPageIssue issue) {
        if (issue.getId() == null || issue.getId().isEmpty()) {
            issue.setId(UUID.randomUUID().toString());
        }
        repo.createIssue(issue);
        return issue;
    }

    /** 对照 Go {@code ListIssues}（L1367-1369） */
    @Override
    public List<WikiPageIssue> listIssues(String kbId, String slug, String status) {
        return repo.listIssues(kbId, slug, status);
    }

    /** 对照 Go {@code UpdateIssueStatus}（L1372-1374） */
    @Override
    public void updateIssueStatus(String issueID, String status) {
        repo.updateIssueStatus(issueID, status);
    }

    // ════════════════════════ 文件夹树（wiki_folders） ════════════════════════

    /** 对照 Go {@code GetFolder}（L1410-1412） */
    @Override
    public WikiFolder getFolder(String kbId, String id) {
        return repo.getFolderByID(kbId, id);
    }

    /**
     * 对照 Go {@code ListChildFolders}（L1421-1474）。
     *
     * <p>PageCount 是<b>递归</b>的（该文件夹的整棵子树），所以父节点反映其下所有内容。
     * 一个文件夹出现在结果里，当且仅当它的子树里有匹配 pageTypes 的页面；
     * 完全空的文件夹（子树里任何类型的页面都没有）只在请求多个类型时列出
     * ——即合并后的 knowledge 视图——这样单类型页签（如 summary）不会冒出空容器。</p>
     */
    @Override
    public List<WikiFolderNode> listChildFolders(String kbId, String parentID,
                                                 List<String> pageTypes) {
        List<WikiFolder> all = repo.listAllFolders(kbId);
        List<String> types = pageTypes == null ? List.of() : pageTypes;

        Map<String, Long> scopedDirect = repo.countPagesByFolder(kbId, types);
        Map<String, Long> allDirect = scopedDirect;
        if (!types.isEmpty()) {
            allDirect = repo.countPagesByFolder(kbId, null);
        }
        Map<String, Long> recScoped = recursiveFolderCounts(all, scopedDirect);
        Map<String, Long> recAll = recursiveFolderCounts(all, allDirect);
        boolean showEmptyFolders = types.size() > 1;

        // 一个文件夹属于本视图，当且仅当它（递归地）含有请求类型的页面，
        // 或者——只在合并视图里——它是一个任何类型页面都没有的完全空容器。
        java.util.function.Predicate<String> relevant = id -> {
            if (recScoped.getOrDefault(id, 0L) > 0) {
                return true;
            }
            if (showEmptyFolders) {
                return recAll.getOrDefault(id, 0L) == 0;
            }
            return false;
        };

        List<WikiFolderNode> out = new ArrayList<>();
        for (WikiFolder f : all) {
            if (!f.getParentId().equals(parentID) || !relevant.test(f.getId())) {
                continue;
            }
            boolean hasChildren = false;
            for (WikiFolder g : all) {
                if (g.getParentId().equals(f.getId()) && relevant.test(g.getId())) {
                    hasChildren = true;
                    break;
                }
            }
            out.add(new WikiFolderNode(f, recScoped.getOrDefault(f.getId(), 0L), hasChildren));
        }
        return out;
    }

    /**
     * 对照 Go {@code recursiveFolderCounts}（L1479-1492）：把每个文件夹 id 映射到
     * 它自己及全部后代的 {@code direct} 页面数之和，利用物化 path 让（导航规模的）
     * 文件夹集合一遍扫完。
     */
    static Map<String, Long> recursiveFolderCounts(List<WikiFolder> all,
                                                   Map<String, Long> direct) {
        Map<String, Long> res = new LinkedHashMap<>(all.size());
        for (WikiFolder f : all) {
            long sum = direct.getOrDefault(f.getId(), 0L);
            String prefix = f.getPath() + "/";
            for (WikiFolder g : all) {
                if (!g.getId().equals(f.getId()) && g.getPath().startsWith(prefix)) {
                    sum += direct.getOrDefault(g.getId(), 0L);
                }
            }
            res.put(f.getId(), sum);
        }
        return res;
    }

    /**
     * 对照 Go {@code validateFolderName}（L1496-1505）：trim 后拒绝空名或带目录
     * 分隔符的名字（文件夹名是单个树层级）。
     */
    static String validateFolderName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new WikiException("folder name is required");
        }
        for (char c : new char[]{'/', '｜', '|', '／'}) {
            if (trimmed.indexOf(c) >= 0) {
                throw new WikiException("folder name \"" + trimmed
                        + "\" must not contain a path separator");
            }
        }
        return trimmed;
    }

    /** 对照 Go {@code CreateFolder}（L1508-1553） */
    @Override
    public WikiFolder createFolder(String kbId, Long tenantID, String parentID, String name) {
        String folderName = validateFolderName(name);

        String parentPath = "";
        int depth = 1;
        if (!WikiConstants.FOLDER_ROOT_ID.equals(parentID)) {
            WikiFolder parent = repo.getFolderByID(kbId, parentID);
            parentPath = parent.getPath();
            depth = parent.getDepth() + 1;
        }

        if (repo.folderNameExists(kbId, parentID, folderName)) {
            throw new WikiFolderConflictException();
        }

        String path = folderName;
        if (!parentPath.isEmpty()) {
            path = parentPath + "/" + folderName;
        }
        OffsetDateTime now = OffsetDateTime.now();
        WikiFolder folder = new WikiFolder();
        folder.setId(UUID.randomUUID().toString());
        folder.setTenantId(tenantID);
        folder.setKnowledgeBaseId(kbId);
        folder.setParentId(parentID);
        folder.setName(folderName);
        folder.setPath(path);
        folder.setDepth(depth);
        folder.setCreatedAt(now);
        folder.setUpdatedAt(now);
        // 顺序保持与 Go 一致：先判冲突再写；唯一索引是最后一道防线
        repo.createFolder(folder);
        return folder;
    }

    /**
     * 对照 Go {@code FindOrCreateFolderPath}（L1558-1602）：把分类路径解析到叶子文件夹 id，
     * 顺路补齐缺失的中间文件夹。对 (kb, parent, name) 唯一约束是并发安全的——
     * 创建冲突时重新拉取。
     */
    @Override
    public FindOrCreateResult findOrCreateFolderPath(String kbId, Long tenantID, List<String> path) {
        List<String> clean = WikiCategoryPaths.cleanCategoryPath(path);
        if (clean.isEmpty()) {
            return new FindOrCreateResult(WikiConstants.FOLDER_ROOT_ID, null);
        }
        String parentID = WikiConstants.FOLDER_ROOT_ID;
        String parentPath = "";
        for (int depth = 0; depth < clean.size(); depth++) {
            String name = clean.get(depth);
            WikiFolder child;
            if (repo.folderNameExists(kbId, parentID, name)) {
                child = repo.getChildFolderByName(kbId, parentID, name);
            } else {
                String fp = name;
                if (!parentPath.isEmpty()) {
                    fp = parentPath + "/" + name;
                }
                OffsetDateTime now = OffsetDateTime.now();
                child = new WikiFolder();
                child.setId(UUID.randomUUID().toString());
                child.setTenantId(tenantID);
                child.setKnowledgeBaseId(kbId);
                child.setParentId(parentID);
                child.setName(name);
                child.setPath(fp);
                child.setDepth(depth + 1);
                child.setCreatedAt(now);
                child.setUpdatedAt(now);
                try {
                    repo.createFolder(child);
                } catch (RuntimeException cerr) {
                    // 创建竞争（或唯一约束冲突）：同名兄弟此刻必然已存在——
                    // 重新拉取，而不是让整个 plan 失败
                    try {
                        child = repo.getChildFolderByName(kbId, parentID, name);
                    } catch (RuntimeException e) {
                        throw new WikiException("create wiki folder \"" + fp + "\": "
                                + cerr.getMessage(), cerr);
                    }
                }
            }
            parentID = child.getId();
            parentPath = child.getPath();
        }
        return new FindOrCreateResult(parentID, clean);
    }

    /** 对照 Go {@code MovePage}（L1606-1623）：纯记账写入，不动版本号 */
    @Override
    public WikiPage movePage(String kbId, String slug, String folderID) {
        WikiPage page = repo.getBySlug(kbId, slug);
        page.setFolderId(folderID == null ? "" : folderID.trim());
        applyFolderToPage(page);
        page.setUpdatedAt(OffsetDateTime.now());
        normalizeWikiHierarchy(page);
        repo.updateMeta(page);
        return page;
    }

    /**
     * 对照 Go {@code RenameOrMoveFolder}（L1629-1720）：改名和/或换父节点，然后重算
     * 整棵子树的物化 path/depth 及子树下每个页面的缓存分类路径。防成环（把文件夹
     * 移进自己或自己的后代）与同级重名。
     */
    @Override
    public WikiFolder renameOrMoveFolder(String kbId, String id, String newName,
                                         String newParentID, boolean moveParent) {
        WikiFolder folder = repo.getFolderByID(kbId, id);

        String name = folder.getName();
        if (newName != null && !newName.trim().isEmpty()) {
            name = validateFolderName(newName);
        }

        String targetParent = folder.getParentId();
        if (moveParent) {
            targetParent = newParentID == null ? "" : newParentID;
        }

        String parentPath = "";
        int depthBase = 0;
        if (!WikiConstants.FOLDER_ROOT_ID.equals(targetParent)) {
            if (targetParent.equals(folder.getId())) {
                throw new WikiException("cannot move a folder into itself");
            }
            WikiFolder parent = repo.getFolderByID(kbId, targetParent);
            if (parent.getPath().equals(folder.getPath())
                    || parent.getPath().startsWith(folder.getPath() + "/")) {
                throw new WikiException("cannot move a folder into its own descendant");
            }
            parentPath = parent.getPath();
            depthBase = parent.getDepth();
        }

        if (repo.folderNameExists(kbId, targetParent, name)) {
            WikiFolder existing = repo.getChildFolderByName(kbId, targetParent, name);
            if (!existing.getId().equals(folder.getId())) {
                throw new WikiFolderConflictException();
            }
        }

        String oldPath = folder.getPath();
        String newPath = name;
        if (!parentPath.isEmpty()) {
            newPath = parentPath + "/" + name;
        }
        if (newPath.equals(oldPath) && targetParent.equals(folder.getParentId())) {
            return folder; // no-op
        }

        List<WikiFolder> all = repo.listAllFolders(kbId);
        OffsetDateTime now = OffsetDateTime.now();
        List<String> affected = new ArrayList<>();
        WikiFolder updated = null;
        for (WikiFolder f : all) {
            boolean isSelf = f.getId().equals(folder.getId());
            boolean isDescendant = f.getPath().startsWith(oldPath + "/");
            if (!isSelf && !isDescendant) {
                continue;
            }
            if (isSelf) {
                f.setParentId(targetParent);
                f.setName(name);
                f.setPath(newPath);
                f.setDepth(depthBase + 1);
            } else {
                f.setPath(newPath + f.getPath().substring(oldPath.length()));
                f.setDepth(WikiCategoryPaths.folderPathSegments(f.getPath()).size());
            }
            f.setUpdatedAt(now);
            repo.updateFolder(f);
            affected.add(f.getId());
            if (isSelf) {
                updated = f;
            }
        }

        recomputePagesForFolders(kbId, affected);
        return updated == null ? folder : updated;
    }

    /**
     * 对照 Go {@code recomputePagesForFolders}（L1725-1744）：刷新归在给定文件夹 id
     * 之下的每个页面的缓存 category_path/wiki_path/depth（用于文件夹子树被移动/改名之后）。
     * 纯记账写入，不动版本号。
     */
    private void recomputePagesForFolders(String kbId, List<String> folderIDs) {
        if (folderIDs.isEmpty()) {
            return;
        }
        List<WikiPage> pages = repo.listPagesByFolderIDs(kbId, folderIDs);
        for (WikiPage page : pages) {
            applyFolderToPage(page);
            page.setUpdatedAt(OffsetDateTime.now());
            normalizeWikiHierarchy(page);
            try {
                repo.updateMeta(page);
            } catch (RuntimeException e) {
                log.warn("wiki: recompute folder path for page {} failed: {}", page.getSlug(),
                        e.toString());
            }
        }
    }

    /**
     * 对照 Go {@code DeleteFolder}（L1748-1767）：只能删既没有页面也没有子文件夹的
     * 文件夹。UI 必须先把内容移走；这让删除保持非破坏性。
     */
    @Override
    public void deleteFolder(String kbId, String id) {
        repo.getFolderByID(kbId, id);
        List<WikiFolder> children = repo.listChildFolders(kbId, id);
        if (!children.isEmpty()) {
            throw new WikiFolderNotEmptyException();
        }
        List<WikiPage> pages = repo.listPagesByFolderIDs(kbId, List.of(id));
        if (!pages.isEmpty()) {
            throw new WikiFolderNotEmptyException();
        }
        repo.deleteFolder(kbId, id);
    }

    /**
     * 对照 Go {@code PruneEmptyFolderChains}（L1775-1846）：删掉文档回收之后变空的文件夹，
     * 再顺着被删除而变空的祖先往上继续。它<b>只</b>考虑传入的文件夹链，所以 wiki 里
     * 别处刻意留着的空文件夹会被保住。
     *
     * <p>调用方必须等该 KB 的摄取队列排空后再调：taxonomy 规划会先建文件夹，
     * reduce 之后才写入引用它的页面。</p>
     */
    @Override
    public List<String> pruneEmptyFolderChains(String kbId, List<String> folderIDs) {
        if (folderIDs == null || folderIDs.isEmpty()) {
            return null; // 对照 Go `return nil, nil`
        }
        List<WikiFolder> all = repo.listAllFolders(kbId);
        Map<String, WikiFolder> byID = new LinkedHashMap<>(all.size());
        for (WikiFolder folder : all) {
            if (folder != null) {
                byID.put(folder.getId(), folder);
            }
        }

        Map<String, WikiFolder> candidates = new LinkedHashMap<>();
        for (String start : folderIDs) {
            String id = start;
            Set<String> seen = new LinkedHashSet<>();
            while (!WikiConstants.FOLDER_ROOT_ID.equals(id)) {
                if (!seen.add(id)) {
                    break; // 环
                }
                WikiFolder folder = byID.get(id);
                if (folder == null) {
                    break;
                }
                candidates.put(id, folder);
                id = folder.getParentId();
            }
        }

        List<WikiFolder> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator
                .comparingInt(WikiFolder::getDepth).reversed()
                .thenComparing(Comparator.comparing(WikiFolder::getPath).reversed()));

        List<String> deleted = new ArrayList<>(ordered.size());
        for (WikiFolder folder : ordered) {
            List<WikiFolder> children = repo.listChildFolders(kbId, folder.getId());
            if (!children.isEmpty()) {
                continue;
            }
            List<WikiPage> pages = repo.listPagesByFolderIDs(kbId, List.of(folder.getId()));
            if (!pages.isEmpty()) {
                continue;
            }
            try {
                repo.deleteFolder(kbId, folder.getId());
            } catch (WikiFolderNotFoundException | WikiFolderNotEmptyException e) {
                continue;
            }
            deleted.add(folder.getId());
        }
        return deleted;
    }

    /**
     * 对照 Go {@code applyFolderToPage}（L1389-1407）：从权威的 FolderID 刷新页面的
     * 派生 category_path 缓存。根（""）清空路径。解析不到的文件夹 id 视为<b>硬错误</b>，
     * 这样我们永远不会静默地把页面放错地方。
     */
    private void applyFolderToPage(WikiPage page) {
        if (page == null) {
            return;
        }
        if (page.getFolderId().trim().isEmpty()) {
            page.setFolderId("");
            page.setCategoryPath(new ArrayList<>());
            return;
        }
        WikiFolder folder;
        try {
            folder = repo.getFolderByID(page.getKnowledgeBaseId(), page.getFolderId());
        } catch (WikiFolderNotFoundException e) {
            throw new WikiException("wiki page references unknown folder \""
                    + page.getFolderId() + "\"");
        }
        page.setCategoryPath(WikiCategoryPaths.folderPathSegments(folder.getPath()));
    }
}
