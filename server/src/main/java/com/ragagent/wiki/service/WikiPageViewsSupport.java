package com.ragagent.wiki.service;




import java.util.ArrayList;





import java.util.List;

import java.util.Map;











import com.ragagent.wiki.domain.WikiException;







import com.ragagent.wiki.domain.WikiIndex;

import com.ragagent.wiki.domain.WikiIndexEntry;

import com.ragagent.wiki.domain.WikiPage;



import com.ragagent.wiki.domain.WikiPageListRequest;

import com.ragagent.wiki.domain.WikiPageListResponse;





import com.ragagent.wiki.domain.WikiStats;

import com.ragagent.wiki.mapper.WikiPageRepository;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;





/**

 * wiki 视图协作者:分页列表、索引视图与统计聚合。

 *

 * <p>持有 {@link WikiPageServiceImpl} 回引以访问仓储与工具;本类不得独立实例化。</p>

 */

final class WikiPageViewsSupport {



    private static final Logger log = LoggerFactory.getLogger(WikiPageViewsSupport.class);



    private final WikiPageServiceImpl service;



    WikiPageViewsSupport(WikiPageServiceImpl service) {

        this.service = service;

    }



    /** 对照 Go {@code ListPages}（L365-395） */

    public WikiPageListResponse listPages(WikiPageListRequest req) {

        WikiPageRepository.PageList result = service.repo.list(req);

        List<WikiPage> pages = result.pages();

        for (WikiPage page : pages) {

            WikiPageServiceImpl.stripWikiPageInlineChunkCitations(page);

            service.normalizeWikiHierarchy(page);

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



    /**

     * 对照 Go {@code GetIndexView}（L466-545）：结构性索引响应——intro（来自索引行）

     * + 每个 page_type 一个分页窗口。

     *

     * <p>目录不再被物化成多兆的 markdown 字符串；每个类型独立用

     * {@code ListByTypeLight} 分页，读放大是 O(page_size) 而不是 O(KB 总页数)。</p>

     */

    public WikiIndex.Response getIndexView(String kbId, List<String> pageTypes, int limit,

                                           String cursor) {

        WikiPage indexPage;

        try {

            indexPage = service.getIndex(kbId);

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

            selected = new ArrayList<>(WikiPageServiceImpl.WIKI_INDEX_CONTENT_PAGE_TYPES);

        }



        List<WikiIndex.Group> groups = new ArrayList<>(selected.size());

        for (String pt : selected) {

            WikiPageRepository.LightList listed;

            try {

                listed = service.repo.listByTypeLight(kbId, pt, lim, offset);

            } catch (RuntimeException e) {

                throw new WikiException("list " + pt + " pages: " + e.getMessage(), e);

            }

            List<WikiIndexEntry> entries = listed.entries();

            for (WikiIndexEntry entry : entries) {

                service.normalizeWikiIndexEntryHierarchy(entry, pt);

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



    /** 对照 Go {@code GetStats}（L813-881） */

    public WikiStats getStats(String kbId) {

        Map<String, Long> counts = service.repo.countByType(kbId);

        long total = 0;

        for (Long c : counts.values()) {

            total += c;

        }



        long orphans = service.repo.countOrphans(kbId);



        // 统计总链接数

        List<WikiPage> pages = service.repo.listAll(kbId);

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

        List<WikiPage> recentPages = service.repo.list(listReq).pages();



        long pendingTasks = 0;

        boolean isActive = false;

        WikiPendingOpsCounter pending = service.pendingOps.getIfAvailable();

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

        WikiActiveFlag flag = service.activeFlag.getIfAvailable();

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

            pendingIssues = service.listIssues(kbId, "", "pending").size();

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

}
