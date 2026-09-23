package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ragagent.agent.tools.DataSchemaTool;
import com.ragagent.agent.tools.DatabaseQueryTool;
import com.ragagent.agent.tools.DocChunkSupport;
import com.ragagent.agent.tools.GrepChunksTool;
import com.ragagent.agent.tools.KnowledgeSearchTool;
import com.ragagent.agent.tools.QueryKnowledgeGraphTool;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.SearchConversationsTool;
import com.ragagent.agent.tools.SearchMemoryTool;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.WikiDeletePageTool;
import com.ragagent.agent.tools.WikiFlagIssueTool;
import com.ragagent.agent.tools.WikiReadIssueTool;
import com.ragagent.agent.tools.WikiReadPageTool;
import com.ragagent.agent.tools.WikiRenamePageTool;
import com.ragagent.agent.tools.WikiReplaceTextTool;
import com.ragagent.agent.tools.WikiSupport;
import com.ragagent.agent.tools.WikiUpdateIssueTool;
import com.ragagent.agent.tools.WikiWritePageTool;
import com.ragagent.agent.tools.WikiSearchTool;
import com.ragagent.agent.tools.WikiReadSourceDocTool;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.service.MemorySearchResult;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.session.domain.MessageSearchGroupItem;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.chatpipeline.SearchParams;
import com.ragagent.config.ConversationProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.ChunkRepository;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.rerank.RankResult;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.searchutil.ImageInfoEnricher;
import com.ragagent.wiki.domain.WikiIndex;
import com.ragagent.wiki.domain.WikiIndexEntry;
import com.ragagent.wiki.domain.WikiPage;
import com.ragagent.wiki.domain.WikiPageIssue;
import com.ragagent.wiki.domain.WikiPageNotFoundException;
import com.ragagent.wiki.service.WikiEditContext;
import com.ragagent.wiki.service.WikiPageService;

/**
 * agent 引擎检索工具族的接缝适配（2026-09-23 接线批）。
 *
 * <p>agent/tools 下的工具类走窄 seam（{@code KnowledgeSearchBackend} /
 * {@code GrepChunkSearch} / {@code GraphSearch} / {@code KnowledgeInfoReader} /
 * {@code PagedChunks} / {@code ImageInfoCollector} / {@code ImageEnricher}），
 * 本类把这些 seam 桥到真实服务——每个方法对照的工具侧 Javadoc 与 Go 原实现
 * （agent_service.go L1030-1137 的构造点）逐条映射。</p>
 *
 * <p>装配入口：{@link SessionAgentQaService#registerTools}（allowedTools 命中即构造）。</p>
 */
@Component
public class AgentToolBackends {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对照 ListPagedChunksByKnowledgeID 的 text+faq 类型过滤。 */
    private static final List<String> TEXT_FAQ_TYPES =
            List.of(com.ragagent.chatpipeline.ChunkTypes.TEXT, com.ragagent.chatpipeline.ChunkTypes.FAQ);

    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final ChunkRepository chunkRepository;
    private final HybridSearchService hybridSearchService;
    private final ConversationProperties conversation;
    private final MessageService messageService;
    private final MemoryService memoryService;
    private final WikiPageService wikiPageService;
    private final JdbcTemplate jdbc;

    public AgentToolBackends(KnowledgeBaseService kbService,
                             KnowledgeService knowledgeService,
                             ChunkRepository chunkRepository,
                             HybridSearchService hybridSearchService,
                             ConversationProperties conversation,
                             MessageService messageService,
                             MemoryService memoryService,
                             WikiPageService wikiPageService,
                             DataSource dataSource) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.chunkRepository = chunkRepository;
        this.hybridSearchService = hybridSearchService;
        this.conversation = conversation;
        this.messageService = messageService;
        this.memoryService = memoryService;
        this.wikiPageService = wikiPageService;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Go {@code registerTools} 的构造面（KB 检索族 5 件 + 会话/记忆/DB 3 件）——
     * allowedTools 命中即构造。非本族名返回 {@code null}（调用方照旧记 "Unknown tool"）。
     *
     * @param ownerId   search_conversations 的 owner（引擎装配期从调用方身份捕获，Go 同款）
     * @param sessionId 当前会话（工具用于剔除本轮会话自身）
     */
    public com.ragagent.agent.tools.AgentTool createTool(String toolName,
            SearchTarget.SearchTargets targets, Reranker rerankModel,
            String ownerId, String sessionId) {
        return switch (toolName) {
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_KNOWLEDGE_SEARCH ->
                    new KnowledgeSearchTool(knowledgeSearchBackend(), chunkInfoBackend(),
                            imageEnricher(), rerankerModel(rerankModel), targets, searchConfig());
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_GREP_CHUNKS ->
                    new GrepChunksTool(grepChunkSearch(), targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_LIST_KNOWLEDGE_CHUNKS ->
                    new com.ragagent.agent.tools.ListKnowledgeChunksTool(knowledgeInfoReader(),
                            chunkById(), pagedChunks(), imageInfoCollector(), targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH ->
                    new QueryKnowledgeGraphTool(graphSearch(), targets,
                            DocChunkSupport.asScopeReader(knowledgeInfoReader()));
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_GET_DOCUMENT_INFO ->
                    new com.ragagent.agent.tools.GetDocumentInfoTool(knowledgeInfoReader(),
                            chunkById(), pagedChunks(), targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_SEARCH_CONVERSATIONS ->
                    new SearchConversationsTool(conversationSearch(), ownerId, sessionId);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_SEARCH_MEMORY ->
                    new SearchMemoryTool(memorySearch());
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_DATABASE_QUERY ->
                    new DatabaseQueryTool(sqlQueryExecutor(), targets, () -> {
                        Long t = TenantContext.currentTenantId();
                        return t == null ? 0L : t;
                    });
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_DATA_SCHEMA -> {
                // 对照 NewDataSchemaTool(knowledgeService, chunkRepo) + WithSearchTargets
                DataSchemaTool tool = new DataSchemaTool(dataSchemaKnowledgeLookup(),
                        dataSchemaChunkLister());
                if (targets != null) {
                    tool.withScopeAuthorizer(dataSchemaScopeAuthorizer(targets));
                }
                yield tool;
            }
            default -> null;
        };
    }

    // ==================================================================
    // wiki 工具族 10 件（切片 2c，对照 agent_service.go L1093-1116）
    // ==================================================================

    /**
     * Go {@code registerTools} 的 wiki 构造面。{@code scopes} 只给 wiki_read_page /
     * wiki_search（Go 同款不对称：其余八件收扁平 kbIDs，内部用
     * NewWikiScopesFromKBIDs 重建 scope，按设计丢失 doc/tag 窄化）。
     *
     * @param routes 请求级共享的 slug→KB 路由记忆（Go L870 一个引擎一个实例）
     */
    public com.ragagent.agent.tools.AgentTool createWikiTool(String toolName,
            SearchTarget.SearchTargets targets, List<WikiSupport.WikiScope> scopes,
            List<String> wikiKbIds, WikiSupport.WikiRouteResolver routes) {
        WikiSupport.WikiPages pages = wikiPages();
        SearchAuth.KnowledgeScopeReader scopeReader =
                DocChunkSupport.asScopeReader(knowledgeInfoReader());
        return switch (toolName) {
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_READ_PAGE ->
                    new WikiReadPageTool(pages, scopeReader, scopes, routes);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_SEARCH ->
                    new WikiSearchTool(pages, scopeReader, scopes, routes);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC ->
                    // Go：NewWikiReadSourceDocTool(knowledgeService, chunkService, searchTargets)
                    // —— 不碰 wiki 服务，只用 chunk 面
                    new WikiReadSourceDocTool(knowledgeInfoReader(), pagedChunks(),
                            imageInfoCollector(), targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_FLAG_ISSUE ->
                    new WikiFlagIssueTool(pages, wikiKbIds, routes)
                            .withKnowledgeScope(scopeReader, targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_READ_ISSUE ->
                    new WikiReadIssueTool(pages, wikiKbIds);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_UPDATE_ISSUE ->
                    new WikiUpdateIssueTool(pages, wikiKbIds);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_WRITE_PAGE ->
                    new WikiWritePageTool(pages, wikiKbIds, scopeReader, routes)
                            .withSearchTargets(targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_REPLACE_TEXT ->
                    new WikiReplaceTextTool(pages, wikiKbIds, scopeReader, routes)
                            .withSearchTargets(targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_RENAME_PAGE ->
                    new WikiRenamePageTool(pages, wikiKbIds, routes);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_DELETE_PAGE ->
                    new WikiDeletePageTool(pages, wikiKbIds, routes);
            default -> null;
        };
    }

    /**
     * 对照 Go 注入 agentService 的 {@code s.wikiPageService}
     * （interfaces.WikiPageService）——桥到 {@link WikiPageService} 的真实实现。
     *
     * <p>三处契约翻译：</p>
     * <ol>
     *   <li>Go 的 {@code repository.ErrWikiPageNotFound} 在 Java 侧是
     *       {@link WikiPageNotFoundException}，而接缝约定「返回 null = 页不存在」，
     *       故 getPageBySlug 单独吞掉它、其余异常照抛（resolveUniqueWikiPage 会跳过
     *       null 但把异常当致命错误）。</li>
     *   <li>Go 用 {@code types.WithWikiEditSource(ctx, …)} 挂上下文，Java 侧对应
     *       {@link WikiEditContext#callWith}（service 落库时读取）。</li>
     *   <li>Go 的 {@code time.Time} 直接进 {@code json.MarshalIndent}，RFC3339 文本；
     *       IssueView 以字符串承载，故在此格式化（Go 读库得到 UTC location）。</li>
     * </ol>
     */
    public WikiSupport.WikiPages wikiPages() {
        return new WikiSupport.WikiPages() {

            @Override
            public WikiSupport.PageView getPageBySlug(String kbId, String slug) {
                try {
                    return toPageView(wikiPageService.getPageBySlug(kbId, slug));
                } catch (WikiPageNotFoundException e) {
                    return null;
                }
            }

            @Override
            public WikiSupport.PageView createPage(WikiSupport.PageView page, String editSource) {
                WikiPage entity = toEntity(page);
                // Go 的两个调用点（wiki_write_page / wiki_rename_page）都用字段字面量建页，
                // ID 恒为空串 → CreatePage 生成新 UUID。Java 工具经 PageView.copy()
                // 会带来旧 ID，必须在接缝处清掉，否则与旧页撞主键。
                entity.setId(null);
                WikiPage created = WikiEditContext.callWith(editSource,
                        () -> wikiPageService.createPage(entity));
                return toPageView(created);
            }

            @Override
            public void updatePage(WikiSupport.PageView page, String editSource) {
                WikiEditContext.callWith(editSource,
                        () -> wikiPageService.updatePage(toEntity(page)));
            }

            @Override
            public void updateAutoLinkedContent(WikiSupport.PageView page, String editSource) {
                WikiEditContext.runWith(editSource,
                        () -> wikiPageService.updateAutoLinkedContent(toEntity(page)));
            }

            @Override
            public void deletePage(String kbId, String slug, String editSource) {
                WikiEditContext.runWith(editSource, () -> wikiPageService.deletePage(kbId, slug));
            }

            @Override
            public WikiSupport.RepairResult repairContentLinks(String kbId, String slug, String content) {
                try {
                    WikiPageService.RepairResult r =
                            wikiPageService.repairContentLinks(kbId, slug, content);
                    return r == null ? null : new WikiSupport.RepairResult(r.content(), r.changed());
                } catch (RuntimeException e) {
                    // Go: if rerr == nil { content = repaired } —— 修复失败永不阻塞写入
                    return null;
                }
            }

            @Override
            public void injectCrossLinks(String kbId, List<String> slugs) {
                wikiPageService.injectCrossLinks(kbId, slugs);
            }

            @Override
            public void rebuildIndexPage(String kbId) {
                wikiPageService.rebuildIndexPage(kbId);
            }

            @Override
            public List<WikiSupport.IssueView> listIssues(String kbId, String slug, String status) {
                List<WikiSupport.IssueView> out = new ArrayList<>();
                for (WikiPageIssue issue : wikiPageService.listIssues(kbId, slug, status)) {
                    if (issue != null) {
                        out.add(toIssueView(issue));
                    }
                }
                return out;
            }

            @Override
            public WikiSupport.IssueView createIssue(WikiSupport.IssueView issue) {
                WikiPageIssue entity = new WikiPageIssue();
                entity.setTenantId(issue.tenantId());
                entity.setKnowledgeBaseId(issue.knowledgeBaseId());
                entity.setSlug(issue.slug());
                entity.setIssueType(issue.issueType());
                entity.setDescription(issue.description());
                entity.setSuspectedKnowledgeIds(issue.suspectedKnowledgeIds());
                entity.setStatus(issue.status());
                entity.setReportedBy(issue.reportedBy());
                return toIssueView(wikiPageService.createIssue(entity));
            }

            @Override
            public void updateIssueStatus(String issueId, String status) {
                wikiPageService.updateIssueStatus(issueId, status);
            }

            @Override
            public List<WikiSupport.PageView> searchPages(String kbId, String query, int limit) {
                List<WikiSupport.PageView> out = new ArrayList<>();
                for (WikiPage page : wikiPageService.searchPages(kbId, query, limit)) {
                    if (page != null) {
                        out.add(toPageView(page));
                    }
                }
                return out;
            }

            @Override
            public WikiSupport.IndexOverviewView getIndexView(String kbId, int topK) {
                try {
                    // Go: GetIndexView(ctx, kbID, nil, wikiIndexAgentTopK, "")
                    WikiIndex.Response resp =
                            wikiPageService.getIndexView(kbId, null, topK, "");
                    return toIndexOverviewView(resp);
                } catch (RuntimeException e) {
                    // Go: err != nil || overview == nil 时静默跳过 overview
                    return null;
                }
            }
        };
    }

    /** 对照 types.WikiPage → 工具侧页视图。 */
    static WikiSupport.PageView toPageView(WikiPage page) {
        WikiSupport.PageView view = WikiSupport.PageView.of(page.getKnowledgeBaseId(),
                page.getSlug());
        view.setId(page.getId());
        view.setTenantId(page.getTenantId() == null ? 0L : page.getTenantId());
        view.setTitle(page.getTitle());
        view.setPageType(page.getPageType());
        view.setStatus(page.getStatus());
        view.setContent(page.getContent());
        view.setSummary(page.getSummary());
        view.setAliases(page.getAliases());
        view.setParentSlug(page.getParentSlug());
        view.setFolderId(page.getFolderId());
        view.setSortOrder(page.getSortOrder());
        view.setSourceRefs(page.getSourceRefs());
        view.setChunkRefs(page.getChunkRefs());
        view.setInLinks(page.getInLinks());
        view.setOutLinks(page.getOutLinks());
        view.setPageMetadata(page.getPageMetadata() == null
                || page.getPageMetadata().isNull() ? "" : page.getPageMetadata().toString());
        return view;
    }

    /** 对照工具侧页视图 → types.WikiPage（service 就地补全 ID/Status/Version/OutLinks）。 */
    static WikiPage toEntity(WikiSupport.PageView view) {
        WikiPage entity = new WikiPage();
        entity.setId(view.id());
        entity.setTenantId(view.tenantId());
        entity.setKnowledgeBaseId(view.knowledgeBaseId());
        entity.setSlug(view.slug());
        entity.setTitle(view.title());
        entity.setPageType(view.pageType());
        entity.setStatus(view.status());
        entity.setContent(view.content());
        entity.setSummary(view.summary());
        entity.setAliases(view.aliases());
        entity.setParentSlug(view.parentSlug());
        entity.setFolderId(view.folderId());
        entity.setSortOrder(view.sortOrder());
        entity.setSourceRefs(view.sourceRefs());
        entity.setChunkRefs(view.chunkRefs());
        entity.setInLinks(view.inLinks());
        entity.setOutLinks(view.outLinks());
        JsonNode metadata = readJson(view.pageMetadata());
        entity.setPageMetadata(metadata);
        return entity;
    }

    /** 对照 types.WikiPageIssue → 工具侧 issue 视图（时间以 Go RFC3339 文本透传）。 */
    static WikiSupport.IssueView toIssueView(WikiPageIssue issue) {
        WikiSupport.IssueView view = new WikiSupport.IssueView();
        view.setId(issue.getId());
        view.setTenantId(issue.getTenantId() == null ? 0L : issue.getTenantId());
        view.setKnowledgeBaseId(issue.getKnowledgeBaseId());
        view.setSlug(issue.getSlug());
        view.setIssueType(issue.getIssueType());
        view.setDescription(issue.getDescription());
        view.setSuspectedKnowledgeIds(issue.getSuspectedKnowledgeIds());
        view.setStatus(issue.getStatus());
        view.setReportedBy(issue.getReportedBy());
        view.setCreatedAt(goTimeText(issue.getCreatedAt()));
        view.setUpdatedAt(goTimeText(issue.getUpdatedAt()));
        view.setDeletedAtValid(issue.getDeletedAt() != null);
        view.setDeletedAt(goTimeText(issue.getDeletedAt()));
        return view;
    }

    /** 对照 types.WikiIndexResponse → 工具侧 overview 视图。 */
    static WikiSupport.IndexOverviewView toIndexOverviewView(WikiIndex.Response resp) {
        if (resp == null) {
            return null;
        }
        List<WikiSupport.IndexGroupView> groups = new ArrayList<>();
        for (WikiIndex.Group g : resp.getGroups()) {
            List<WikiSupport.IndexEntryView> items = new ArrayList<>();
            for (WikiIndexEntry entry : g.getItems()) {
                items.add(new WikiSupport.IndexEntryView(entry.getSlug(), entry.getTitle(),
                        entry.getSummary()));
            }
            groups.add(new WikiSupport.IndexGroupView(g.getType(), g.getTotal(), items));
        }
        return new WikiSupport.IndexOverviewView(resp.getIntro(), groups);
    }

    /**
     * 对照 Go {@code json.Marshal(time.Time)}（格式串 {@code 2006-01-02T15:04:05.999999999Z07:00}）：
     * GORM 读 timestamptz 得到 UTC location，故按 UTC 渲染；小数秒**尾零连同空小数点一起
     * 去掉**（Java 的 ISO_OFFSET_DATE_TIME 会补齐到 3/6/9 位）；零值写
     * {@code 0001-01-01T00:00:00Z}。
     */
    static String goTimeText(java.time.OffsetDateTime value) {
        if (GoTimeSerializer.isGoZero(value)) {
            return GoTimeSerializer.GO_ZERO_TIME_LITERAL;
        }
        String s = value.atZoneSameInstant(java.time.ZoneOffset.UTC).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        int fracEnd = dot + 1;
        while (fracEnd < s.length() && Character.isDigit(s.charAt(fracEnd))) {
            fracEnd++;
        }
        int keep = fracEnd;
        while (keep > dot + 1 && s.charAt(keep - 1) == '0') {
            keep--;
        }
        return s.substring(0, dot) + (keep > dot + 1 ? s.substring(dot, keep) : "")
                + s.substring(fracEnd);
    }

    // ==================================================================
    // search_conversations / search_memory / database_query（切片 2a）
    // ==================================================================

    /** 对照 NewSearchConversationsTool：messageService.SearchMessages（hybrid、owner 显式）。 */
    public SearchConversationsTool.ConversationSearch conversationSearch() {
        return (query, limit, ownerId) -> {
            MessageSearchResult r = messageService.searchMessages(
                    query, MessageService.MODE_HYBRID, limit, null,
                    ownerId == null || ownerId.isEmpty() ? null : ownerId);
            List<SearchConversationsTool.ExchangeView> out = new ArrayList<>();
            if (r != null && r.getItems() != null) {
                for (MessageSearchGroupItem item : r.getItems()) {
                    out.add(new SearchConversationsTool.ExchangeView(
                            nz(item.getSessionId()), nz(item.getSessionTitle()),
                            item.getCreatedAt() == null
                                    ? java.time.LocalDate.of(1, 1, 1)
                                    : item.getCreatedAt().toLocalDate(),
                            nz(item.getQueryContent()), nz(item.getAnswerContent())));
                }
            }
            return out;
        };
    }

    /** 对照 NewSearchMemoryTool(s.memoryService)。 */
    public SearchMemoryTool.MemorySearch memorySearch() {
        return (query, limit) -> {
            MemorySearchResult r = memoryService.searchMemory(query, limit);
            if (r == null) {
                return new SearchMemoryTool.MemorySearchResultView(false, List.of());
            }
            List<SearchMemoryTool.MemoryItemView> items = new ArrayList<>();
            if (r.items() != null) {
                for (MemoryItem it : r.items()) {
                    items.add(new SearchMemoryTool.MemoryItemView(nz(it.getKind()),
                            nz(it.getTopic()), nz(it.getContent()),
                            it.getValidFrom() == null
                                    ? java.time.LocalDate.of(1, 1, 1)
                                    : it.getValidFrom().toLocalDate()));
                }
            }
            return new SearchMemoryTool.MemorySearchResultView(r.available(), items);
        };
    }

    // ==================================================================
    // data_schema（切片 2b）
    // ==================================================================

    /** 对照 data_schema 的 knowledgeService.GetKnowledgeByIDOnly（拿 tenant 用）。 */
    public DataSchemaTool.KnowledgeLookup dataSchemaKnowledgeLookup() {
        return knowledgeId -> {
            Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
            if (k == null) {
                return null;
            }
            return new DataSchemaTool.KnowledgeView(k.getId(),
                    k.getTenantId() == null ? 0L : k.getTenantId());
        };
    }

    /** 对照 chunkRepo.ListPagedChunksByKnowledgeID（tenant 取 knowledge 行，Go 同款）。 */
    public DataSchemaTool.ChunkLister dataSchemaChunkLister() {
        return (knowledgeId, page, pageSize, chunkTypes, enabled) -> {
            Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
            long tenant = k == null || k.getTenantId() == null ? 0L : k.getTenantId();
            int offset = Math.max(page - 1, 0) * Math.max(pageSize, 0);
            ChunkRepository.ChunkPage p = chunkRepository.listPagedChunksByKnowledgeId(
                    tenant, knowledgeId, offset, pageSize, chunkTypes, null,
                    "", "", "", "", enabled);
            List<DataSchemaTool.ChunkView> out = new ArrayList<>();
            for (com.ragagent.knowledge.domain.Chunk c : p.items()) {
                out.add(new DataSchemaTool.ChunkView(nz(c.getChunkType()), c.getContent()));
            }
            return out;
        };
    }

    /** 对照 authorizeKnowledgeInSearchTargets（scopeEnforced 时的授权器）。 */
    public DataSchemaTool.ScopeAuthorizer dataSchemaScopeAuthorizer(
            SearchTarget.SearchTargets targets) {
        return knowledgeId -> {
            SearchAuth.KnowledgeView scoped = SearchAuth.authorizeKnowledgeInSearchTargets(
                    targets, knowledgeId, DocChunkSupport.asScopeReader(knowledgeInfoReader()));
            Knowledge k = knowledgeService.getKnowledgeByIdOnly(scoped.id());
            return new DataSchemaTool.KnowledgeView(scoped.id(),
                    k == null || k.getTenantId() == null ? 0L : k.getTenantId());
        };
    }

    /** 对照 {@code db.Raw(securedSQL).Rows()}（值类型约定见 seam 文档）。 */
    public DatabaseQueryTool.SqlQueryExecutor sqlQueryExecutor() {
        return securedSql -> jdbc.query(securedSql, rs -> {
            java.sql.ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> columns = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                columns.add(md.getColumnLabel(i));
            }
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    row.add(coerceSqlValue(rs.getObject(i)));
                }
                rows.add(row);
            }
            return new DatabaseQueryTool.QueryResult(columns, rows);
        });
    }

    /**
     * 值类型约定对齐 Go 的 {@code rows.Scan(interface{})} + {@code []byte→string}：
     * 文本→String、整型→Long、浮点→Double、数值→BigDecimal、布尔→Boolean；
     * PG 的 uuid/时间类型在 Go 侧同样经 []byte 落到 string，这里统一 toString。
     */
    private static Object coerceSqlValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof byte[] b) {
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (v instanceof java.util.UUID || v instanceof java.sql.Timestamp
                || v instanceof java.sql.Date || v instanceof java.sql.Time) {
            return v.toString();
        }
        return v;
    }

    // ==================================================================
    // knowledge_search
    // ==================================================================

    /** 对照 NewKnowledgeSearchTool 的 cfg 参数（Conversation 检索段）。 */
    public KnowledgeSearchTool.SearchConfig searchConfig() {
        return new KnowledgeSearchTool.SearchConfig(
                conversation.getEmbeddingTopK(), conversation.getVectorThreshold(),
                conversation.getKeywordThreshold(), conversation.getRerankThreshold());
    }

    public KnowledgeSearchTool.KnowledgeSearchBackend knowledgeSearchBackend() {
        return new KnowledgeSearchTool.KnowledgeSearchBackend() {
            @Override
            public KnowledgeSearchTool.KBView getKnowledgeBaseById(String kbId) {
                KnowledgeBase kb = kbService.getAllTenantById(kbId);
                if (kb == null) {
                    throw new RuntimeException("knowledge base not found");
                }
                return toKbView(kb);
            }

            @Override
            public List<KnowledgeSearchTool.KBView> getKnowledgeBasesByIdsOnly(List<String> ids) {
                List<KnowledgeSearchTool.KBView> out = new ArrayList<>();
                if (ids == null) {
                    return out;
                }
                for (String id : ids) {
                    try {
                        KnowledgeBase kb = kbService.getAllTenantById(id);
                        if (kb != null) {
                            out.add(toKbView(kb));
                        }
                    } catch (RuntimeException ignored) {
                        // 对照 Go GetKnowledgeBasesByIDsOnly：异常返回空表
                    }
                }
                return out;
            }

            @Override
            public Map<String, String> resolveEmbeddingModelKeys(
                    List<KnowledgeSearchTool.KBView> kbs) {
                List<KnowledgeBase> rows = new ArrayList<>();
                if (kbs != null) {
                    for (KnowledgeSearchTool.KBView v : kbs) {
                        KnowledgeBase kb = kbService.getAllTenantById(v.id());
                        if (kb != null) {
                            rows.add(kb);
                        }
                    }
                }
                return hybridSearchService.resolveEmbeddingModelKeys(rows);
            }

            @Override
            public float[] getQueryEmbedding(String kbId, String queryText) {
                return hybridSearchService.getQueryEmbedding(kbId, queryText);
            }

            @Override
            public List<KnowledgeSearchTool.SearchResultView> hybridSearch(
                    String kbId, KnowledgeSearchTool.HybridParams params) {
                SearchParams sp = new SearchParams();
                sp.setQueryText(params.queryText());
                sp.setQueryEmbedding(params.queryEmbedding());
                sp.setMatchCount(params.matchCount());
                sp.setVectorThreshold(params.vectorThreshold());
                sp.setKeywordThreshold(params.keywordThreshold());
                sp.setKnowledgeIds(params.knowledgeIDs());
                sp.setTagIds(params.tagIDs());
                sp.setScopeTagIds(params.scopeTagIDs());
                sp.setKnowledgeBaseIds(params.knowledgeBaseIDs());
                List<SearchResult> rows = hybridSearchService.hybridSearch(kbId, sp);
                List<KnowledgeSearchTool.SearchResultView> out = new ArrayList<>();
                if (rows != null) {
                    for (SearchResult r : rows) {
                        out.add(toSearchResultView(r));
                    }
                }
                return out;
            }
        };
    }

    public KnowledgeSearchTool.ChunkInfoBackend chunkInfoBackend() {
        return new KnowledgeSearchTool.ChunkInfoBackend() {
            @Override
            public com.ragagent.knowledge.domain.Chunk faqChunkById(String chunkId) {
                return chunkRepository.getChunkByIdOnly(chunkId);
            }

            @Override
            public long totalChunks(long tenantId, String knowledgeId) {
                // 对照 ListPagedChunksByKnowledgeID(text+faq, enabled) 的 Count 段
                return pagedChunkCount(tenantId, knowledgeId);
            }
        };
    }

    /** 对照 searchutil.EnrichSearchResultsImageInfo（工具侧 enrich 回调）。 */
    public KnowledgeSearchTool.ImageEnricher imageEnricher() {
        return (tenantId, results) -> {
            if (results == null || results.isEmpty()) {
                return;
            }
            List<String> chunkIDs = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (KnowledgeSearchTool.SearchResultView r : results) {
                if (r.imageInfo != null && !r.imageInfo.isEmpty()) {
                    continue;
                }
                if (!seen.containsKey(r.id)) {
                    seen.put(r.id, Boolean.TRUE);
                    chunkIDs.add(r.id);
                }
            }
            if (chunkIDs.isEmpty()) {
                return;
            }
            Map<String, String> infoMap = ImageInfoEnricher.collectImageInfoByChunkIds(
                    chunkRepository::listChunksByParentIDs, tenantId, chunkIDs);
            if (infoMap == null || infoMap.isEmpty()) {
                return;
            }
            for (KnowledgeSearchTool.SearchResultView r : results) {
                if (r.imageInfo != null && !r.imageInfo.isEmpty()) {
                    continue;
                }
                String merged = infoMap.get(r.id);
                if (merged != null) {
                    r.imageInfo = merged;
                }
            }
        };
    }

    /** 对照 rerank.Reranker.Rerank → tool 的 RankResult（失败上抛，工具侧回落原序）。 */
    public static KnowledgeSearchTool.RerankerModel rerankerModel(Reranker reranker) {
        if (reranker == null) {
            return null;
        }
        return (query, passages) -> {
            List<RankResult> ranked = reranker.rerank(query, passages);
            List<KnowledgeSearchTool.RankResult> out = new ArrayList<>();
            if (ranked != null) {
                for (RankResult r : ranked) {
                    out.add(new KnowledgeSearchTool.RankResult(r.getIndex(), r.getRelevanceScore()));
                }
            }
            return out;
        };
    }

    // ==================================================================
    // grep_chunks（对照 grep_chunks.go 的 searchChunks 整段查询）
    // ==================================================================

    public GrepChunksTool.GrepChunkSearch grepChunkSearch() {
        return (queries, fullKbIDs, knowledgeIDs, tagTargets, kbTenantMap) -> {
            if ((fullKbIDs == null || fullKbIDs.isEmpty())
                    && (knowledgeIDs == null || knowledgeIDs.isEmpty())
                    && (tagTargets == null || tagTargets.isEmpty())) {
                return List.of();
            }
            List<Object> args = new ArrayList<>();
            String scope = grepScopeClause(fullKbIDs, knowledgeIDs, tagTargets, kbTenantMap, args);
            if (scope.isEmpty()) {
                return List.of();
            }
            RegexDialect dialect = regexDialect();
            List<String> regexParts = new ArrayList<>();
            for (String q : queries) {
                regexParts.add("(" + dialect.condition("chunks.content") + " OR "
                        + dialect.condition("knowledges.title") + ")");
                args.add(q);
                args.add(q);
            }
            String sql = "SELECT chunks.id, chunks.content, chunks.chunk_index, chunks.knowledge_id, "
                    + "chunks.knowledge_base_id, chunks.chunk_type, chunks.metadata, "
                    + "knowledges.title AS knowledge_title "
                    + "FROM chunks JOIN knowledges ON chunks.knowledge_id = knowledges.id "
                    + "WHERE chunks.is_enabled = TRUE AND chunks.deleted_at IS NULL "
                    + "AND knowledges.deleted_at IS NULL "
                    + "AND (" + scope + ") AND (" + String.join(" OR ", regexParts) + ") "
                    + "ORDER BY chunks.created_at DESC LIMIT 500";

            List<GrepChunksTool.GrepChunkView> results = jdbc.query(sql, (rs, i) -> {
                GrepChunksTool.GrepChunkView v = new GrepChunksTool.GrepChunkView();
                v.id = rs.getString("id");
                v.content = rs.getString("content");
                v.chunkIndex = rs.getInt("chunk_index");
                v.knowledgeId = rs.getString("knowledge_id");
                v.knowledgeBaseId = rs.getString("knowledge_base_id");
                v.chunkType = rs.getString("chunk_type");
                String meta = rs.getString("metadata");
                v.metadata = readJson(meta);
                v.knowledgeTitle = rs.getString("knowledge_title");
                return v;
            }, args.toArray());

            if (!results.isEmpty()) {
                backfillTotalChunkCounts(results);
            }
            return results;
        };
    }

    /** 对照 grep_chunks.go 的 scopeClause（OR 组合：knowledge_id IN / 标签 EXISTS / kb+tenant 对）。 */
    private String grepScopeClause(List<String> kbIDs, List<String> knowledgeIDs,
                                   List<SearchTarget> tagTargets, Map<String, Long> kbTenantMap,
                                   List<Object> args) {
        List<String> clauses = new ArrayList<>();
        if (knowledgeIDs != null && !knowledgeIDs.isEmpty()) {
            clauses.add("chunks.knowledge_id IN (" + placeholders(knowledgeIDs.size()) + ")");
            args.addAll(knowledgeIDs);
        }
        if (tagTargets != null) {
            for (SearchTarget target : tagTargets) {
                if (target == null || target.knowledgeBaseId() == null
                        || target.knowledgeBaseId().isEmpty()
                        || target.tagIds() == null || target.tagIds().isEmpty()) {
                    continue;
                }
                long tenantID = target.tenantId();
                if (tenantID == 0) {
                    Long mapped = kbTenantMap == null ? null : kbTenantMap.get(target.knowledgeBaseId());
                    tenantID = mapped == null ? 0 : mapped;
                }
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ? AND EXISTS ("
                        + "SELECT 1 FROM knowledge_tag_relations ktr "
                        + "WHERE ktr.knowledge_id = chunks.knowledge_id AND ktr.tag_id IN ("
                        + placeholders(target.tagIds().size()) + ")))");
                args.add(target.knowledgeBaseId());
                args.add(tenantID);
                args.addAll(target.tagIds());
            }
        }
        if (kbIDs != null) {
            for (String kbID : kbIDs) {
                Long mapped = kbTenantMap == null ? null : kbTenantMap.get(kbID);
                long tenantID = mapped == null ? 0 : mapped;
                if (tenantID == 0) {
                    continue;
                }
                clauses.add("(chunks.knowledge_base_id = ? AND chunks.tenant_id = ?)");
                args.add(kbID);
                args.add(tenantID);
            }
        }
        if (clauses.isEmpty()) {
            return "";
        }
        return String.join(" OR ", clauses);
    }

    /** 对照 count 回填：knowledge_id → 该 knowledge 的 enabled chunk 数。 */
    private void backfillTotalChunkCounts(List<GrepChunksTool.GrepChunkView> results) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (GrepChunksTool.GrepChunkView r : results) {
            if (r.knowledgeId != null && !r.knowledgeId.isEmpty()) {
                seen.put(r.knowledgeId, Boolean.TRUE);
            }
        }
        if (seen.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(seen.keySet());
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            jdbc.query("SELECT knowledge_id, COUNT(*) AS cnt FROM chunks "
                            + "WHERE knowledge_id IN (" + placeholders(ids.size()) + ") "
                            + "AND is_enabled = TRUE AND deleted_at IS NULL "
                            + "GROUP BY knowledge_id",
                    rs -> {
                        counts.put(rs.getString("knowledge_id"), rs.getInt("cnt"));
                    }, ids.toArray());
            for (GrepChunksTool.GrepChunkView r : results) {
                Integer c = counts.get(r.knowledgeId);
                r.totalChunkCount = c == null ? 0 : c;
            }
        } catch (RuntimeException ignored) {
            // 对照 Go：count 失败只 warn，跳过回填
        }
    }

    /**
     * 对照 regexOperatorForDialect：PostgreSQL {@code ~*}，其余 {@code REGEXP}；
     * H2（仅测试内存库）用 {@code REGEXP_LIKE(...,'i')} 等价表达大小写不敏感。
     */
    private RegexDialect regexDialect() {
        try (java.sql.Connection conn = jdbc.getDataSource().getConnection()) {
            String name = conn.getMetaData().getDatabaseProductName();
            String lower = name == null ? "" : name.toLowerCase();
            if (lower.contains("postgres")) {
                return RegexDialect.PG_OPERATOR;
            }
            if (lower.contains("h2")) {
                return RegexDialect.H2_FUNCTION;
            }
            return RegexDialect.GENERIC_OPERATOR;
        } catch (java.sql.SQLException e) {
            return RegexDialect.GENERIC_OPERATOR;
        }
    }

    /** 正则条件的三方言表达（含/不含列名的完整布尔片段）。 */
    private enum RegexDialect {
        PG_OPERATOR {
            @Override
            String condition(String column) {
                return column + " ~* ?";
            }
        },
        GENERIC_OPERATOR {
            @Override
            String condition(String column) {
                return column + " REGEXP ?";
            }
        },
        H2_FUNCTION {
            @Override
            String condition(String column) {
                return "REGEXP_LIKE(" + column + ", ?, 'i')";
            }
        };

        abstract String condition(String column);
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    /** Knowledge.metadata（jsonb JsonNode）→ 工具视图的 Map 形态；非对象/null → null。 */
    private static Map<String, Object> metadataMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            return JSON.convertValue(node,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode readJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // list_knowledge_chunks / get_document_info / wiki_read_source_doc 共享面
    // ==================================================================

    public DocChunkSupport.KnowledgeInfoReader knowledgeInfoReader() {
        return new DocChunkSupport.KnowledgeInfoReader() {
            @Override
            public DocChunkSupport.KnowledgeInfoView byIdOnly(String knowledgeId) {
                Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
                if (k == null) {
                    return null;
                }
                return new DocChunkSupport.KnowledgeInfoView(
                        k.getId(), k.getTenantId() == null ? 0L : k.getTenantId(),
                        nz(k.getKnowledgeBaseId()), nz(k.getTitle()), nz(k.getDescription()),
                        nz(k.getType()), nz(k.getSource()), nz(k.getFileName()), nz(k.getFileType()),
                        k.getFileSize() == null ? 0L : k.getFileSize(), nz(k.getParseStatus()),
                        metadataMap(k.getMetadata()));
            }

            @Override
            public Map<String, List<SearchAuth.TagView>> fetchTags(List<String> knowledgeIds) {
                Map<String, List<SearchAuth.TagView>> out = new LinkedHashMap<>();
                if (knowledgeIds == null || knowledgeIds.isEmpty()) {
                    return out;
                }
                // 对照 GetKnowledgeTags：knowledge_tag_relations 按 knowledge 聚合
                jdbc.query("SELECT knowledge_id, tag_id FROM knowledge_tag_relations "
                                + "WHERE knowledge_id IN (" + placeholders(knowledgeIds.size()) + ") "
                                + "ORDER BY knowledge_id, tag_id",
                        rs -> {
                            String kid = rs.getString("knowledge_id");
                            out.computeIfAbsent(kid, x -> new ArrayList<>())
                                    .add(new SearchAuth.TagView(rs.getString("tag_id")));
                        }, knowledgeIds.toArray());
                return out;
            }
        };
    }

    /** 对照 chunkService.GetChunkByID（工具侧的 chunkById 回调）。 */
    public java.util.function.Function<String, com.ragagent.knowledge.domain.Chunk> chunkById() {
        return chunkRepository::getChunkByIdOnly;
    }

    public DocChunkSupport.PagedChunks pagedChunks() {
        return (tenantId, knowledgeId, page, pageSize) -> {
            int offset = Math.max(page - 1, 0) * Math.max(pageSize, 0);
            ChunkRepository.ChunkPage page1 = chunkRepository.listPagedChunksByKnowledgeId(
                    tenantId, knowledgeId, offset, pageSize,
                    TEXT_FAQ_TYPES, null, "", "", "", "", Boolean.TRUE);
            return new DocChunkSupport.ChunkPage(page1.items(), page1.total());
        };
    }

    /** 对照 ListPagedChunksByKnowledgeID 的 text+faq + enabled 过滤（count 段）。 */
    private long pagedChunkCount(long tenantId, String knowledgeId) {
        return chunkRepository.listPagedChunksByKnowledgeId(
                tenantId, knowledgeId, 0, 1, TEXT_FAQ_TYPES, null, "", "", "", "",
                Boolean.TRUE).total();
    }

    public DocChunkSupport.ImageInfoCollector imageInfoCollector() {
        return (tenantId, chunkIds) -> ImageInfoEnricher.collectImageInfoByChunkIds(
                chunkRepository::listChunksByParentIDs, tenantId, chunkIds);
    }

    // ==================================================================
    // query_knowledge_graph
    // ==================================================================

    public QueryKnowledgeGraphTool.GraphSearch graphSearch() {
        return new QueryKnowledgeGraphTool.GraphSearch() {
            @Override
            public QueryKnowledgeGraphTool.KnowledgeBaseView getKnowledgeBaseByIdOnly(String kbId) {
                KnowledgeBase kb = kbService.getAllTenantById(kbId);
                if (kb == null) {
                    return null;
                }
                return new QueryKnowledgeGraphTool.KnowledgeBaseView(kb.getId(),
                        extractConfigView(kb.getExtractConfig()));
            }

            @Override
            public List<QueryKnowledgeGraphTool.SearchResultView> hybridSearch(
                    String kbId, String queryText, int matchCount) {
                SearchParams sp = new SearchParams();
                sp.setQueryText(queryText);
                sp.setMatchCount(matchCount);
                List<SearchResult> rows = hybridSearchService.hybridSearch(kbId, sp);
                List<QueryKnowledgeGraphTool.SearchResultView> out = new ArrayList<>();
                if (rows != null) {
                    for (SearchResult r : rows) {
                        out.add(new QueryKnowledgeGraphTool.SearchResultView(r.getId(),
                                r.getScore(), r.getContent(), r.getKnowledgeId(),
                                r.getKnowledgeBaseId(), r.getKnowledgeTitle(), r.getChunkIndex(),
                                r.getChunkType(), r.getMatchType()));
                    }
                }
                return out;
            }
        };
    }

    /** 对照 ExtractConfig 的 nodes[].name / relations[].type。 */
    private static QueryKnowledgeGraphTool.ExtractConfigView extractConfigView(JsonNode cfg) {
        if (cfg == null || cfg.isNull()) {
            return null;
        }
        List<QueryKnowledgeGraphTool.GraphNodeView> nodes = new ArrayList<>();
        JsonNode n = cfg.path("nodes");
        if (n.isArray()) {
            for (JsonNode item : n) {
                nodes.add(new QueryKnowledgeGraphTool.GraphNodeView(item.path("name").asText("")));
            }
        }
        List<QueryKnowledgeGraphTool.GraphRelationView> relations = new ArrayList<>();
        JsonNode r = cfg.path("relations");
        if (r.isArray()) {
            for (JsonNode item : r) {
                relations.add(new QueryKnowledgeGraphTool.GraphRelationView(item.path("type").asText("")));
            }
        }
        return new QueryKnowledgeGraphTool.ExtractConfigView(nodes, relations);
    }

    // ==================================================================
    // 映射辅助
    // ==================================================================

    private static KnowledgeSearchTool.KBView toKbView(KnowledgeBase kb) {
        boolean vector = false;
        boolean keyword = false;
        if (kb.getIndexingStrategy() != null) {
            vector = kb.getIndexingStrategy().isVectorEnabled();
            keyword = kb.getIndexingStrategy().isKeywordEnabled();
        }
        return new KnowledgeSearchTool.KBView(kb.getId(), nz(kb.getType()), vector, keyword);
    }

    private static KnowledgeSearchTool.SearchResultView toSearchResultView(SearchResult r) {
        KnowledgeSearchTool.SearchResultView v = new KnowledgeSearchTool.SearchResultView();
        v.id = nz(r.getId());
        v.content = nz(r.getContent());
        v.knowledgeId = nz(r.getKnowledgeId());
        v.knowledgeBaseId = nz(r.getKnowledgeBaseId());
        v.knowledgeTitle = nz(r.getKnowledgeTitle());
        v.chunkIndex = r.getChunkIndex();
        v.chunkType = nz(r.getChunkType());
        v.parentChunkId = nz(r.getParentChunkId());
        v.imageInfo = nz(r.getImageInfo());
        v.knowledgeCustomMetadata = nz(r.getKnowledgeCustomMetadata());
        v.knowledgeSource = nz(r.getKnowledgeSource());
        v.startAt = r.getStartAt();
        v.endAt = r.getEndAt();
        v.score = r.getScore();
        v.matchType = r.getMatchType();
        return v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
