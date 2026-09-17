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
 * wiki 页面服务接口（对照 Go internal/types/interfaces/wiki_page.go L9-236 的
 * {@code WikiPageService}）。
 *
 * <p>方法名 = Go 方法名（首字母小写），参数顺序一致，返回类型一一对应；
 * Go 的 {@code (value, error)} 二元返回在 Java 拆成「返回值 + 抛异常」。</p>
 *
 * <p><b>handler 接线提示</b></p>
 * <ul>
 *   <li>Go 的 {@code []string} 参数/返回 → {@code List<String>}；
 *       Go 的 nil slice → {@code null} 或空 List，逐方法在实现里标明。</li>
 *   <li>{@code map[string]*WikiPageLite} → {@code Map<String, WikiPageLite>}（保序 LinkedHashMap）。</li>
 *   <li>Go 的 {@code [][]string}（ListDistinctCategoryPaths）→ {@code List<List<String>>}。</li>
 * </ul>
 *
 * <p>Go 接口里没有、但 Go 实现文件里存在的 {@code ErrWikiRevertToCurrentVersion} 语义，
 * Java 侧由 {@link WikiRevertToCurrentVersionException} 承担（handler 映射成 400）。</p>
 */
public interface WikiPageService {

    // ──────────────────────────── 页面写入 ────────────────────────────

    /**
     * 对照 Go {@code CreatePage}（wiki_page.go L70-109）：新建页面，解析出链、
     * 维护目标页的入链，并（可选）同步 chunk。
     *
     * <p>副作用：入参会就地补全 ID / Status / Version，并在写入后回写
     * {@code OutLinks}。</p>
     */
    WikiPage createPage(WikiPage page);

    /**
     * 对照 Go {@code UpdatePage}（L121-197）。
     *
     * <p><b>版本号策略</b>：只有用户可见字段真的变了（title / content / summary /
     * page_type / status / aliases）才递增 {@code version} 并快照被取代的版本；
     * 纯记账写入（同内容重摄取的 source_refs 刷新、索引导语重建、没替换任何东西的
     * 交叉链接注入……）走 {@code UpdateMeta}，版本号不动。</p>
     */
    WikiPage updatePage(WikiPage page);

    /** 对照 Go {@code UpdatePageMeta}（L200-204）：只刷元数据，不动版本、不重解析链接 */
    void updatePageMeta(WikiPage page);

    /**
     * 对照 Go {@code UpdateAutoLinkedContent}（L211-231）：持久化<b>机器侧</b>链接
     * 修饰产生的正文变更，不递增版本；出链重解析、目标页入链刷新。
     */
    void updateAutoLinkedContent(WikiPage page);

    // ──────────────────────────── 页面读取 ────────────────────────────

    /** 对照 Go {@code GetPageBySlug}（L345-352） */
    WikiPage getPageBySlug(String kbId, String slug);

    /**
     * 对照 Go {@code RepairContentLinks}（L1121-1200）：把正文里指向不存在页面的
     * {@code [[slug]]} 重写成最可能的真实目标。<b>只重写、绝不剥离</b>，因此对任何
     * 写入路径都安全。返回「可能被改写的正文 + 是否发生改写」。
     */
    RepairResult repairContentLinks(String kbId, String selfSlug, String content);

    /** 对照 Go {@code RepairContentLinks} 的 {@code (string, bool, error)} 返回 */
    record RepairResult(String content, boolean changed) {}

    /** 对照 Go {@code GetPageByID}（L355-362） */
    WikiPage getPageByID(String id);

    /** 对照 Go {@code ListPages}（L365-395）：过滤 + 分页 */
    WikiPageListResponse listPages(WikiPageListRequest req);

    /** 对照 Go {@code DeletePage}（L398-423）：软删页面 + 摘掉入链 + 硬删其快照历史 */
    void deletePage(String kbId, String slug);

    /** 对照 Go {@code GetIndex}（L426-437）：取索引页，不存在则建默认页 */
    WikiPage getIndex(String kbId);

    /**
     * 对照 Go {@code GetIndexView}（L466-545）：结构化索引响应
     * —— intro（来自索引行）+ 每个 page_type 的分页窗口。
     *
     * @param pageTypes 只包含这些页面类型；空 = 全部内容类型
     * @param limit     每组窗口大小，默认 50、上限 200
     * @param cursor    不透明偏移字符串（当前就是 strconv 出来的 offset）
     */
    WikiIndex.Response getIndexView(String kbId, List<String> pageTypes, int limit, String cursor);

    /** 对照 Go {@code GetGraph}（L576-586） */
    WikiGraph.Data getGraph(WikiGraph.Request req);

    /** 对照 Go {@code GetStats}（L813-881） */
    WikiStats getStats(String kbId);

    /** 对照 Go {@code RebuildLinks}（L884-920）：全量重解析出链并重建入链 */
    void rebuildLinks(String kbId);

    /** 对照 Go {@code InjectCrossLinks}（L1852-1892）：扫描指定页面注入 {@code [[wiki-link]]} */
    void injectCrossLinks(String kbId, List<String> affectedSlugs);

    /**
     * 对照 Go {@code RebuildIndexPage}（L1909-1913）。
     *
     * <p>Go 侧正文<b>故意是 no-op</b>：目录已不再持久化进 wiki_pages.content，
     * 改为 GetIndexView 按需拼装；保留方法名只为让既有 agent 工具调用点编译不变。
     * Java 侧同样 no-op。</p>
     */
    void rebuildIndexPage(String kbId);

    // ──────────────────────────── 批量读取 ────────────────────────────

    /** 对照 Go {@code ListAllPages}（L923-925）：全部非归档页面，不分页 */
    List<WikiPage> listAllPages(String kbId);

    /** 对照 Go {@code ListByType}（L930-932） */
    List<WikiPage> listByType(String kbId, String pageType);

    /** 对照 Go {@code ListPagesBySourceRef}（L937-939） */
    List<WikiPage> listPagesBySourceRef(String kbId, String knowledgeID);

    /** 对照 Go {@code ListSlugsBySourceRef}（L945-947） */
    List<String> listSlugsBySourceRef(String kbId, String knowledgeID);

    /** 对照 Go {@code ListBySlugs}（L954-956）：一次 IN 查询拿瘦投影 */
    Map<String, WikiPageLite> listBySlugs(String kbId, List<String> slugs);

    /** 对照 Go {@code ListSummariesByKnowledgeIDs}（L961-963） */
    Map<String, String> listSummariesByKnowledgeIDs(String kbId, List<String> kids);

    /** 对照 Go {@code ExistsSlugs}（L968-970） */
    Map<String, Boolean> existsSlugs(String kbId, List<String> slugs);

    /** 对照 Go {@code ListAllSlugs}（L975-977） */
    List<String> listAllSlugs(String kbId);

    /** 对照 Go {@code ListPagesCursor}（L980-982）：返回 {@code (pages, nextCursor)} */
    CursorPage listPagesCursor(String kbId, String cursor, int limit);

    /** 对照 Go {@code ListPagesCursor} 的 {@code ([]*WikiPage, string)} 返回 */
    record CursorPage(List<WikiPage> pages, String nextCursor) {}

    /** 对照 Go {@code ListByTypeRecent}（L986-988） */
    List<WikiIndexEntry> listByTypeRecent(String kbId, String pageType, int limit);

    /** 对照 Go {@code FindSimilarPages}（L992-994） */
    List<WikiPageLite> findSimilarPages(String kbId, String query, List<String> pageTypes, int limit);

    /** 对照 Go {@code FindPagesByNormalizedTitle}（L998-1000） */
    List<WikiPageLite> findPagesByNormalizedTitle(String kbId, String pageType, String identity);

    /** 对照 Go {@code FindPagesByNormalizedTitles}（L1004-1006） */
    List<WikiPageLite> findPagesByNormalizedTitles(String kbId, String pageType,
                                                   List<String> identities);

    /** 对照 Go {@code ListDistinctCategoryPaths}（L1010-1012） */
    List<List<String>> listDistinctCategoryPaths(String kbId, int maxPaths);

    // ──────────────────────────── 统计 / 检索 ────────────────────────────

    /** 对照 Go {@code CountByType}（L1016-1018） */
    Map<String, Long> countByType(String kbId);

    /** 对照 Go {@code SearchPages}（L1021-1023） */
    List<WikiPage> searchPages(String kbId, String query, int limit);

    // ──────────────────────────── 文件夹树 ────────────────────────────

    /**
     * 对照 Go {@code ListChildFolders}（L1421-1474）：parentID（"" = 根）的直接子文件夹，
     * PageCount 是<b>递归</b>的子树计数。
     */
    List<WikiFolderNode> listChildFolders(String kbId, String parentID, List<String> pageTypes);

    /** 对照 Go {@code GetFolder}（L1410-1412） */
    WikiFolder getFolder(String kbId, String id);

    /** 对照 Go {@code CreateFolder}（L1508-1553）：同名兄弟已存在 → 冲突 */
    WikiFolder createFolder(String kbId, Long tenantID, String parentID, String name);

    /**
     * 对照 Go {@code RenameOrMoveFolder}（L1629-1720）：改名 / 换父节点，并重算整棵
     * 子树的物化 path/depth 与子树下每个页面的缓存 category_path。
     */
    WikiFolder renameOrMoveFolder(String kbId, String id, String newName, String newParentID,
                                  boolean moveParent);

    /** 对照 Go {@code DeleteFolder}（L1748-1767）：只能删空文件夹 */
    void deleteFolder(String kbId, String id);

    /**
     * 对照 Go {@code PruneEmptyFolderChains}（L1775-1846）：删除候选链上仍为空的文件夹，
     * 并向上继续剪掉因删除而变空的祖先。
     *
     * <p><b>空入参时返回 {@code null}</b>（对照 Go {@code return nil, nil}）。</p>
     */
    List<String> pruneEmptyFolderChains(String kbId, List<String> folderIDs);

    /** 对照 Go {@code FindOrCreateFolderPath}（L1558-1602）：返回 {@code (leafId, cleanPath)} */
    FindOrCreateResult findOrCreateFolderPath(String kbId, Long tenantID, List<String> path);

    /** 对照 Go {@code FindOrCreateFolderPath} 的 {@code (string, []string, error)} */
    record FindOrCreateResult(String folderId, List<String> path) {}

    /** 对照 Go {@code MovePage}（L1606-1623）：把页面移入文件夹并刷新缓存路径 */
    WikiPage movePage(String kbId, String slug, String folderID);

    // ──────────────────────────── 修订历史 ────────────────────────────

    /** 对照 Go {@code ListRevisions}（L284-300）：最新在前、不含 content，附当前版本 */
    WikiPageRevisionListResponse listRevisions(String kbId, String slug, int limit, int offset);

    /** 对照 Go {@code GetRevision}（L303-311）：单条快照，含 content */
    WikiPageRevision getRevision(String kbId, String slug, int version);

    /**
     * 对照 Go {@code RevertPageToVersion}（L318-342）：把页面回滚到某份快照的正文内容，
     * 并<b>以一次普通编辑的形式应用</b>（回滚前状态会被快照、版本号前进、链接重解析）。
     *
     * @throws WikiRevertToCurrentVersionException 目标是当前版本（handler 映射 400）
     */
    WikiPage revertPageToVersion(String kbId, String slug, int version);

    // ──────────────────────────── 页面问题 ────────────────────────────

    /** 对照 Go {@code CreateIssue}（L1356-1364） */
    WikiPageIssue createIssue(WikiPageIssue issue);

    /** 对照 Go {@code ListIssues}（L1367-1369） */
    List<WikiPageIssue> listIssues(String kbId, String slug, String status);

    /** 对照 Go {@code UpdateIssueStatus}（L1372-1374） */
    void updateIssueStatus(String issueID, String status);
}
