package com.ragagent.wiki.service;

import java.util.List;
import java.util.Map;

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
import com.ragagent.wiki.domain.WikiPageRevision;
import com.ragagent.wiki.domain.WikiPageRevisionListResponse;
import com.ragagent.wiki.domain.WikiStats;

/**
 * wiki 页面服务接口。
 *
 * <p><b>handler 接线提示</b></p>
 * <ul>
 *   <li>集合参数/返回用 {@code List<String>}；"未提供"与"空列表"的区分逐方法在实现里标明。</li>
 *   <li>{@code Map<String, WikiPageLite>} 的实现保持插入序（LinkedHashMap）。</li>
 * </ul>
 *
 * <p>回滚目标即当前版本的特殊语义由 {@link WikiRevertToCurrentVersionException} 承担
 * （handler 映射成 400）。</p>
 */
public interface WikiPageService {

    // ──────────────────────────── 页面写入 ────────────────────────────

    /**
     * 新建页面，解析出链、
     * 维护目标页的入链，并（可选）同步 chunk。
     *
     * <p>副作用：入参会就地补全 ID / Status / Version，并在写入后回写
     * {@code OutLinks}。</p>
     */
    WikiPage createPage(WikiPage page);

    /**
     * 更新页面。
     *
     * <p><b>版本号策略</b>：只有用户可见字段真的变了（title / content / summary /
     * page_type / status / aliases）才递增 {@code version} 并快照被取代的版本；
     * 纯记账写入（同内容重摄取的 source_refs 刷新、索引导语重建、没替换任何东西的
     * 交叉链接注入……）走 {@code UpdateMeta}，版本号不动。</p>
     */
    WikiPage updatePage(WikiPage page);

    /** 只刷元数据，不动版本、不重解析链接 */
    void updatePageMeta(WikiPage page);

    /**
     * 持久化<b>机器侧</b>链接
     * 修饰产生的正文变更，不递增版本；出链重解析、目标页入链刷新。
     */
    void updateAutoLinkedContent(WikiPage page);

    // ──────────────────────────── 页面读取 ────────────────────────────

    WikiPage getPageBySlug(String kbId, String slug);

    /**
     * {@link #getPageBySlug} 的"调用侧双返回"形态：把 not found 折成 null 返回，
     * 供把 not found 当正常分支的服务内部调用点
     * （wiki ingest 的清理/发布/交叉链接等）使用。controller 的 404 语义仍走
     * {@link #getPageBySlug}——那里有显式 catch，不能改。
     */
    WikiPage findPageBySlug(String kbId, String slug);

    /**
     * 把正文里指向不存在页面的
     * {@code [[slug]]} 重写成最可能的真实目标。<b>只重写、绝不剥离</b>，因此对任何
     * 写入路径都安全。返回「可能被改写的正文 + 是否发生改写」。
     */
    RepairResult repairContentLinks(String kbId, String selfSlug, String content);

    /** 结果：可能被改写的正文 + 是否发生改写 */
    record RepairResult(String content, boolean changed) {}

    WikiPage getPageByID(String id);

    /** 过滤 + 分页 */
    WikiPageListResponse listPages(WikiPageListRequest req);

    /** 软删页面 + 摘掉入链 + 硬删其快照历史 */
    void deletePage(String kbId, String slug);

    /** 取索引页，不存在则建默认页 */
    WikiPage getIndex(String kbId);

    /**
     * 结构化索引响应
     * —— intro（来自索引行）+ 每个 page_type 的分页窗口。
     *
     * @param pageTypes 只包含这些页面类型；空 = 全部内容类型
     * @param limit     每组窗口大小，默认 50、上限 200
     * @param cursor    不透明偏移字符串
     */
    WikiIndex.Response getIndexView(String kbId, List<String> pageTypes, int limit, String cursor);

    WikiGraph.Data getGraph(WikiGraph.Request req);

    WikiStats getStats(String kbId);

    /** 全量重解析出链并重建入链 */
    void rebuildLinks(String kbId);

    /** 扫描指定页面注入 {@code [[wiki-link]]} */
    void injectCrossLinks(String kbId, List<String> affectedSlugs);

    /**
     * 重建索引页正文。
     *
     * <p>正文<b>故意是 no-op</b>：目录已不再持久化进 wiki_pages.content，
     * 改为 GetIndexView 按需拼装；保留方法名只为让既有 agent 工具调用点编译不变。</p>
     */
    void rebuildIndexPage(String kbId);

    // ──────────────────────────── 批量读取 ────────────────────────────

    /** 全部非归档页面，不分页 */
    List<WikiPage> listAllPages(String kbId);

    List<WikiPage> listByType(String kbId, String pageType);

    List<WikiPage> listPagesBySourceRef(String kbId, String knowledgeID);

    List<String> listSlugsBySourceRef(String kbId, String knowledgeID);

    /** 一次 IN 查询拿瘦投影 */
    Map<String, WikiPageLite> listBySlugs(String kbId, List<String> slugs);

    Map<String, String> listSummariesByKnowledgeIDs(String kbId, List<String> kids);

    Map<String, Boolean> existsSlugs(String kbId, List<String> slugs);

    List<String> listAllSlugs(String kbId);

    CursorPage listPagesCursor(String kbId, String cursor, int limit);

    /** 分页窗口 + 下一页游标 */
    record CursorPage(List<WikiPage> pages, String nextCursor) {}

    List<WikiIndexEntry> listByTypeRecent(String kbId, String pageType, int limit);

    List<WikiPageLite> findSimilarPages(String kbId, String query, List<String> pageTypes, int limit);

    List<WikiPageLite> findPagesByNormalizedTitle(String kbId, String pageType, String identity);

    List<WikiPageLite> findPagesByNormalizedTitles(String kbId, String pageType,
                                                   List<String> identities);

    List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths);

    // ──────────────────────────── 统计 / 检索 ────────────────────────────

    Map<String, Long> countByType(String kbId);

    List<WikiPage> searchPages(String kbId, String query, int limit);

    // ──────────────────────────── 文件夹树 ────────────────────────────

    /**
     * parentID（"" = 根）的直接子文件夹，
     * PageCount 是<b>递归</b>的子树计数。
     */
    List<WikiFolderNode> listChildFolders(String kbId, String parentID, List<String> pageTypes);

    WikiFolder getFolder(String kbId, String id);

    /** 同名兄弟已存在 → 冲突 */
    WikiFolder createFolder(String kbId, Long tenantID, String parentID, String name);

    /**
     * 改名 / 换父节点，并重算整棵
     * 子树的物化 path/depth 与子树下每个页面的缓存 category_path。
     */
    WikiFolder renameOrMoveFolder(String kbId, String id, String newName, String newParentID,
                                  boolean moveParent);

    /** 只能删空文件夹 */
    void deleteFolder(String kbId, String id);

    /**
     * 删除候选链上仍为空的文件夹，
     * 并向上继续剪掉因删除而变空的祖先。
     *
     * <p><b>空入参时返回 {@code null}</b>。</p>
     */
    List<String> pruneEmptyFolderChains(String kbId, List<String> folderIDs);

    FindOrCreateResult findOrCreateFolderPath(String kbId, Long tenantID, List<String> path);

    /** 叶子文件夹 id 与其清洗后的完整路径 */
    record FindOrCreateResult(String folderId, List<String> path) {}

    /** 把页面移入文件夹并刷新缓存路径 */
    WikiPage movePage(String kbId, String slug, String folderID);

    // ──────────────────────────── 修订历史 ────────────────────────────

    /** 最新在前、不含 content，附当前版本 */
    WikiPageRevisionListResponse listRevisions(String kbId, String slug, int limit, int offset);

    /** 单条快照，含 content */
    WikiPageRevision getRevision(String kbId, String slug, int version);

    /**
     * 把页面回滚到某份快照的正文内容，
     * 并<b>以一次普通编辑的形式应用</b>（回滚前状态会被快照、版本号前进、链接重解析）。
     *
     * @throws WikiRevertToCurrentVersionException 目标是当前版本（handler 映射 400）
     */
    WikiPage revertPageToVersion(String kbId, String slug, int version);

    // ──────────────────────────── 页面问题 ────────────────────────────

    WikiPageIssue createIssue(WikiPageIssue issue);

    List<WikiPageIssue> listIssues(String kbId, String slug, String status);

    void updateIssueStatus(String issueID, String status);
}
