package com.ragagent.wiki.service.page;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.wiki.domain.WikiCategoryPaths;
import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiFolder;
import com.ragagent.wiki.domain.WikiFolderNode;
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
import com.ragagent.wiki.domain.WikiStats;
import com.ragagent.wiki.mapper.WikiPageRepository;
import com.ragagent.wiki.service.WikiChunkCleaner;
import com.ragagent.wiki.service.ingest.WikiGraphCalculator;
import com.ragagent.wiki.service.ingest.WikiPendingOpsCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * wiki 页面服务实现。
 *
 * <p>错误模型：「返回值 + 抛 {@link WikiException} 子类」；sentinel 判别改成
 * 异常类型判别（sentinel 见 {@code com.ragagent.wiki.domain} 下的异常类）。</p>
 *
 * <h3>行为要点</h3>
 * <ol>
 *   <li><b>版本号策略</b>：只有 title/content/summary/page_type/status/aliases
 *       真的变了才走 {@code updateWithRevision} 并递增 version；否则走 {@code updateMeta}。</li>
 *   <li><b>链接维护</b>：{@code parseOutLinks} 是纯字符串算法；
 *       {@code updateInLinks}/{@code removeInLinks} 逐目标页做读-改-写，目标不存在时静默跳过。</li>
 *   <li><b>图谱子集</b>：抽到 {@link WikiGraphCalculator}（纯函数）。</li>
 *   <li><b>文件夹子树重算</b>：改名/移动后按 path 前缀重算整棵子树的
 *       path/depth，再重算子树下每个页面的缓存路径。</li>
 *   <li><b>chunk 同步 / 待处理任务计数 / Redis 活跃标志</b>：用可插拔端口 + 缺席时的
 *       同款降级（见各端口接口注释）。</li>
 * </ol>
 *
 * <h3>上下文</h3>
 * <p>编辑来源与用户身份由 {@link WikiEditContext} +
 * {@link com.ragagent.common.context.TenantContext} 承担，service 签名不带额外的来源参数。</p>
 */
@Service
public class WikiPageServiceImpl implements WikiPageService {

    static final Logger log = LoggerFactory.getLogger(WikiPageServiceImpl.class);



    /** 构成用户可见目录的页面类型 */
    static final List<String> WIKI_INDEX_CONTENT_PAGE_TYPES = List.of(
            WikiConstants.PAGE_TYPE_SUMMARY,
            WikiConstants.PAGE_TYPE_ENTITY,
            WikiConstants.PAGE_TYPE_CONCEPT,
            WikiConstants.PAGE_TYPE_SYNTHESIS,
            WikiConstants.PAGE_TYPE_COMPARISON);

    /** 默认索引页的字面内容 */
    static final String DEFAULT_INDEX_CONTENT =
            "# Wiki Index\n\nThis is the index page. It will be automatically updated as pages are added.\n";

    final WikiPageRepository repo;
    final KnowledgeBaseMapper kbMapper;
    final ObjectProvider<WikiChunkCleaner> chunkCleaner;
    final ObjectProvider<WikiCrossLinker> crossLinker;
    final ObjectProvider<WikiPendingOpsCounter> pendingOps;
    final ObjectProvider<WikiActiveFlag> activeFlag;

    /** 链接维护域协作者(构造期装配;只存 service 引用,调用期才解引)。 */
    final WikiPageLinkOps linkOps;

    /** 修订/问题域协作者(构造期装配;只存 service 引用,调用期才解引)。 */
    final WikiPageRevisionOps revisionOps;

    /** 页面域协作者(构造期装配;只存 service 引用,调用期才解引)。 */
    final WikiPageFolderSupport folderSupport;
    final WikiPageLinkRepair linkRepair;
    final WikiPageViewsSupport views;

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
        this.revisionOps = new WikiPageRevisionOps(this);
        this.linkOps = new WikiPageLinkOps(this);
        this.folderSupport = new WikiPageFolderSupport(this);
        this.linkRepair = new WikiPageLinkRepair(this);
        this.views = new WikiPageViewsSupport(this);
    }

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
        linkOps.stripWikiPageInlineChunkCitations(page);

        // 解析正文里的出链
        page.setOutLinks(linkOps.parseOutLinks(page.getContent()));
        applyFolderToPage(page);
        normalizeWikiHierarchy(page);

        OffsetDateTime now = OffsetDateTime.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);

        repo.create(page);

        // 维护目标页的入链
        linkOps.updateInLinks(page.getKnowledgeBaseId(), page.getSlug(), page.getOutLinks());
        return page;
    }

    /**
     * 版本号只跟踪<b>用户可见</b>的内容修订，不是每一次行重写：只有
     * title / content / summary / page_type / status / aliases 至少一项真的变了才递增。
     * 纯记账写入（同内容重摄取时刷新 source_refs、同目录重建索引页、没替换任何东西的
     * 交叉链接注入……）仍会落库，但走 {@code UpdateMeta}、version 不动，这样消费方
     * 才能把「版本号变了」当作真实的编辑信号。</p>
     */
    @Override
    public WikiPage updatePage(WikiPage page) {
        WikiPage existing = repo.getBySlug(page.getKnowledgeBaseId(), page.getSlug());
        linkOps.stripWikiPageInlineChunkCitations(page);

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
        // 而不是相信调用方送来的值
        applyFolderToPage(existing);

        // 出链是正文的纯导数，所以只随正文变。无条件重解析以与库中正文保持一致。
        existing.setOutLinks(linkOps.parseOutLinks(existing.getContent()));
        normalizeWikiHierarchy(existing);

        if (contentChanged) {
            // 新版本由驱动这次写入的人署名
            existing.setLastEditSource(WikiEditContext.currentEditSource());
            existing.setLastEditorId(WikiEditContext.currentEditorId());

            // 快照被取代的版本 + 原子写入新版本：每个历史版本的正文都被保住，
            // 且更新失败时不会留下半份快照
            repo.updateWithRevision(existing, WikiPageRevisionOps.revisionFromPage(prev));
            // 限制单页历史；尽力而为——剪枝失败只意味着多占一点存储，直到下次内容变更
            revisionOps.pruneRevisions(existing.getId(), existing.getVersion());
        } else {
            // 没有用户可见的变化——持久化记账字段但保留 version，
            // 让下游消费方能依赖它
            repo.updateMeta(existing);
        }

        // 入链：先摘旧的再加新的。内容没变时 oldOutLinks == existing.OutLinks，
        // 这两次调用实际都是 no-op
        linkOps.removeInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), oldOutLinks);
        linkOps.updateInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), existing.getOutLinks());

        return existing;
    }

    /** 记账型更新：落库但不递增 version。 */
    @Override
    public void updatePageMeta(WikiPage page) {
        normalizeWikiHierarchy(page);
        page.setUpdatedAt(OffsetDateTime.now());
        repo.updateMeta(page);
    }

    /**
     * 持久化<b>机器侧</b>链接修饰
     * （交叉链接注入 / 死链清理）产生的正文，不递增 version。出链从新正文重解析、
     * 目标页的入链刷新，从而导航一致——只有面向用户的修订计数被保留。
     */
    @Override
    public void updateAutoLinkedContent(WikiPage page) {
        WikiPage existing = repo.getBySlug(page.getKnowledgeBaseId(), page.getSlug());
        List<String> oldOutLinks = existing.getOutLinks();

        existing.setContent(linkOps.stripWikiInlineChunkCitations(page.getContent()));
        existing.setOutLinks(linkOps.parseOutLinks(existing.getContent()));
        existing.setUpdatedAt(OffsetDateTime.now());

        repo.updateAutoLinkedContent(existing);

        linkOps.removeInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), oldOutLinks);
        linkOps.updateInLinks(existing.getKnowledgeBaseId(), existing.getSlug(), existing.getOutLinks());
    }



    @Override
    public WikiPage getPageBySlug(String kbId, String slug) {
        WikiPage page = repo.getBySlug(kbId, slug);
        linkOps.stripWikiPageInlineChunkCitations(page);
        return page;
    }

    /** 不抛异常的查询形态：页面不存在时返回 null（见接口注释）。 */
    @Override
    public WikiPage findPageBySlug(String kbId, String slug) {
        try {
            return getPageBySlug(kbId, slug);
        } catch (com.ragagent.wiki.domain.WikiPageNotFoundException e) {
            return null;
        }
    }

    @Override
    public WikiPage getPageByID(String id) {
        WikiPage page = repo.getByID(id);
        linkOps.stripWikiPageInlineChunkCitations(page);
        return page;
    }

    @Override
    public void deletePage(String kbId, String slug) {
        WikiPage page = repo.getBySlug(kbId, slug);

        // 摘掉本页指向的那些目标页上的入链引用
        linkOps.removeInLinks(kbId, slug, page.getOutLinks());

        repo.delete(kbId, slug);

        revisionOps.deletePageRevisions(page);

        deleteChunkForPage(page);
    }

    /** 页面不存在时按默认内容即时建出索引页。 */
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

    @Override
    public WikiGraph.Data getGraph(WikiGraph.Request req) {
        if (req == null) {
            throw new WikiException("wiki graph request is required");
        }
        return WikiGraphCalculator.compute(repo.listAll(req.knowledgeBaseId()), req);
    }



    /**
     * 目录页正文的<b>故意 no-op</b>——目录不再持久化进 wiki_pages.content，
     * 改由 GetIndexView 从 ListByTypeLight 轻投影按需拼装，因此单个页面写入
     * 不必再做 O(N) 字符串拼接、重写多兆 TEXT 列。保留方法名只为让既有 agent
     * 工具调用点（wiki_write_page / wiki_rename_page）编译不变。
     *
     * <p>索引行上仍留着的 intro 由 ingest 管道在批次完成时单独维护
     * （rebuildIndexPage(chatModel, ...)），那里才真正有 LLM + 变更描述上下文。</p>
     */
    @Override
    public void rebuildIndexPage(String kbId) {
        // intentionally no-op
    }

    @Override
    public List<WikiPage> listAllPages(String kbId) {
        return repo.listAll(kbId);
    }

    @Override
    public List<WikiPage> listByType(String kbId, String pageType) {
        return repo.listByType(kbId, pageType);
    }

    @Override
    public List<WikiPage> listPagesBySourceRef(String kbId, String knowledgeID) {
        return repo.listBySourceRef(kbId, knowledgeID);
    }

    @Override
    public List<String> listSlugsBySourceRef(String kbId, String knowledgeID) {
        return repo.listSlugsBySourceRef(kbId, knowledgeID);
    }

    @Override
    public Map<String, WikiPageLite> listBySlugs(String kbId, List<String> slugs) {
        return repo.listBySlugs(kbId, slugs);
    }

    @Override
    public Map<String, String> listSummariesByKnowledgeIDs(String kbId, List<String> kids) {
        return repo.listSummariesByKnowledgeIDs(kbId, kids);
    }

    @Override
    public Map<String, Boolean> existsSlugs(String kbId, List<String> slugs) {
        return repo.existsSlugs(kbId, slugs);
    }

    @Override
    public List<String> listAllSlugs(String kbId) {
        return repo.listAllSlugs(kbId);
    }

    @Override
    public CursorPage listPagesCursor(String kbId, String cursor, int limit) {
        WikiPageRepository.CursorPage page = repo.listPagesCursor(kbId, cursor, limit);
        return new CursorPage(page.pages(), page.nextCursor());
    }

    @Override
    public List<WikiIndexEntry> listByTypeRecent(String kbId, String pageType, int limit) {
        return repo.listByTypeRecent(kbId, pageType, limit);
    }

    @Override
    public List<WikiPageLite> findSimilarPages(String kbId, String query, List<String> pageTypes,
                                               int limit) {
        return repo.findSimilarPages(kbId, query, pageTypes, limit);
    }

    @Override
    public List<WikiPageLite> findPagesByNormalizedTitle(String kbId, String pageType,
                                                         String identity) {
        return repo.findPagesByNormalizedTitle(kbId, pageType, identity);
    }

    @Override
    public List<WikiPageLite> findPagesByNormalizedTitles(String kbId, String pageType,
                                                          List<String> identities) {
        return repo.findPagesByNormalizedTitles(kbId, pageType, identities);
    }

    @Override
    public List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths) {
        return repo.listDistinctCategoryPaths(kbId, maxPaths);
    }

    @Override
    public Map<String, Long> countByType(String kbId) {
        return repo.countByType(kbId);
    }

    @Override
    public List<WikiPage> searchPages(String kbId, String query, int limit) {
        return repo.search(kbId, query, limit);
    }








    /**
     * 删掉页面同步出去的 chunk。
     * chunk 同步是可选接线——没装 chunk 仓储的 service 直接跳过，而不是让删除连带失败。
     */
    final void deleteChunkForPage(WikiPage page) {
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

    final WikiPage createDefaultPage(String kbId, String slug, String title, String pageType,
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
     * 拼出规范化、可排序的
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

    static boolean containsString(List<String> slice, String s) {
        return slice != null && slice.contains(s);
    }

    /** 移除<b>所有</b>等于 s 的元素 */
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

    /** 浅拷贝一份页面，列表字段另起一份 */
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







    /**
     * 把每个文件夹 id 映射到
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
     * trim 后拒绝空名或带目录
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

    // ── 委托:实现随协作者(接口契约在门面) ──

    /** 包内 seam:页面应用文件夹语义(写入路径用,实现在 FolderSupport)。 */
    void applyFolderToPage(WikiPage page) {
        folderSupport.applyFolderToPage(page);
    }

    @Override
    public WikiFolder getFolder(String kbId, String id) {
        return folderSupport.getFolder(kbId, id);
    }

    @Override
    public List<WikiFolderNode> listChildFolders(String kbId, String parentID, List<String> pageTypes) {
        return folderSupport.listChildFolders(kbId, parentID, pageTypes);
    }

    @Override
    public WikiFolder createFolder(String kbId, Long tenantID, String parentID, String name) {
        return folderSupport.createFolder(kbId, tenantID, parentID, name);
    }

    @Override
    public FindOrCreateResult findOrCreateFolderPath(String kbId, Long tenantID, List<String> path) {
        return folderSupport.findOrCreateFolderPath(kbId, tenantID, path);
    }

    @Override
    public WikiPage movePage(String kbId, String slug, String folderID) {
        return folderSupport.movePage(kbId, slug, folderID);
    }

    @Override
    public WikiFolder renameOrMoveFolder(String kbId, String id, String newName, String newParentID,
            boolean moveParent) {
        return folderSupport.renameOrMoveFolder(kbId, id, newName, newParentID, moveParent);
    }

    @Override
    public void deleteFolder(String kbId, String id) {
        folderSupport.deleteFolder(kbId, id);
    }

    @Override
    public List<String> pruneEmptyFolderChains(String kbId, List<String> folderIDs) {
        return folderSupport.pruneEmptyFolderChains(kbId, folderIDs);
    }

    @Override
    public WikiPageService.RepairResult repairContentLinks(String kbId, String selfSlug, String content) {
        return linkRepair.repairContentLinks(kbId, selfSlug, content);
    }

    @Override
    public WikiPageListResponse listPages(WikiPageListRequest req) {
        return views.listPages(req);
    }

    @Override
    public WikiIndex.Response getIndexView(String kbId, List<String> pageTypes, int limit, String cursor) {
        return views.getIndexView(kbId, pageTypes, limit, cursor);
    }

    @Override
    public WikiStats getStats(String kbId) {
        return views.getStats(kbId);
    }

    // ── 修订历史 / 页面问题:实现随协作者(WikiPageRevisionOps) ──

    @Override
    public WikiPageRevisionListResponse listRevisions(String kbId, String slug, int limit,
                                                     int offset) {
        return revisionOps.listRevisions(kbId, slug, limit, offset);
    }

    @Override
    public WikiPageRevision getRevision(String kbId, String slug, int version) {
        return revisionOps.getRevision(kbId, slug, version);
    }

    @Override
    public WikiPage revertPageToVersion(String kbId, String slug, int version) {
        return revisionOps.revertPageToVersion(kbId, slug, version);
    }

    @Override
    public WikiPageIssue createIssue(WikiPageIssue issue) {
        return revisionOps.createIssue(issue);
    }

    @Override
    public List<WikiPageIssue> listIssues(String kbId, String slug, String status) {
        return revisionOps.listIssues(kbId, slug, status);
    }

    @Override
    public void updateIssueStatus(String issueID, String status) {
        revisionOps.updateIssueStatus(issueID, status);
    }

    // ── 链接维护:实现随协作者(WikiPageLinkOps) ──

    @Override
    public void rebuildLinks(String kbId) {
        linkOps.rebuildLinks(kbId);
    }

    @Override
    public void injectCrossLinks(String kbId, List<String> affectedSlugs) {
        linkOps.injectCrossLinks(kbId, affectedSlugs);
    }
}
