package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ragagent.agent.tools.data.DataSchemaTool;
import com.ragagent.agent.tools.sql.DatabaseQueryTool;
import com.ragagent.agent.tools.DocChunkSupport;
import com.ragagent.agent.tools.knowledge.GrepChunksTool;
import com.ragagent.agent.tools.knowledge.KnowledgeSearchTool;
import com.ragagent.agent.tools.knowledge.QueryKnowledgeGraphTool;
import com.ragagent.agent.tools.SearchAuth;
import com.ragagent.agent.tools.knowledge.SearchConversationsTool;
import com.ragagent.agent.tools.knowledge.SearchMemoryTool;
import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.agent.tools.web.WebFetchTool;
import com.ragagent.agent.tools.web.WebSearchTool;
import com.ragagent.agent.tools.wiki.WikiDeletePageTool;
import com.ragagent.agent.tools.wiki.WikiFlagIssueTool;
import com.ragagent.agent.tools.wiki.WikiReadIssueTool;
import com.ragagent.agent.tools.wiki.WikiReadPageTool;
import com.ragagent.agent.tools.wiki.WikiRenamePageTool;
import com.ragagent.agent.tools.wiki.WikiReplaceTextTool;
import com.ragagent.agent.tools.wiki.WikiPages;
import com.ragagent.agent.tools.wiki.WikiRouteResolver;
import com.ragagent.agent.tools.wiki.WikiScope;
import com.ragagent.agent.tools.wiki.WikiUpdateIssueTool;
import com.ragagent.agent.tools.wiki.WikiWritePageTool;
import com.ragagent.agent.tools.wiki.WikiSearchTool;
import com.ragagent.agent.tools.wiki.WikiReadSourceDocTool;
import com.ragagent.common.context.TenantContext;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.service.MemorySearchResult;
import com.ragagent.memory.service.MemoryService;
import com.ragagent.session.domain.MessageSearchGroupItem;
import com.ragagent.session.domain.MessageSearchResult;
import com.ragagent.common.settings.ConversationProperties;
import com.ragagent.knowledge.domain.Knowledge;
import com.ragagent.knowledge.repository.ChunkRepository;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.service.KnowledgeBaseService;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.rerank.Reranker;
import com.ragagent.retrieval.HybridSearchService;
import com.ragagent.wiki.service.page.WikiPageService;

/**
 * agent 引擎检索工具族的接缝适配（2026-09-23 接线批）。
 *
 * <p>agent/tools 下的工具类走窄 seam（{@code KnowledgeSearchBackend} /
 * {@code GrepChunkSearch} / {@code GraphSearch} / {@code KnowledgeInfoReader} /
 * {@code PagedChunks} / {@code ImageInfoCollector} / {@code ImageEnricher}），
 * 本类把这些 seam 桥到真实服务——每个方法对应一个工具的构造点。</p>
 *
 * <p>装配入口：{@link SessionAgentQaService#registerTools}（allowedTools 命中即构造）。</p>
 */
@Component
public class AgentToolBackends {

    static final ObjectMapper JSON = new ObjectMapper();


    private final KnowledgeBaseService kbService;
    private final KnowledgeService knowledgeService;
    private final ChunkRepository chunkRepository;
    private final HybridSearchService hybridSearchService;

    /** 知识库检索簇：KB 检索/grep/图谱/chunk 列举等工具后端。 */
    private final AgentToolKbBackends kbBackends;

    /** wiki 工具簇：WikiPages 端口实现与视图转换。 */
    private final AgentToolWikiBackends wikiBackends;
    private final ConversationProperties conversation;
    private final MessageService messageService;
    private final MemoryService memoryService;
    private final WikiPageService wikiPageService;
    private final com.ragagent.websearch.service.WebSearchService webSearchService;
    private final com.ragagent.auth.service.TenantService tenantService;
    private final com.ragagent.knowledge.storage.TenantFileStorage fileStorage;
    private final JdbcTemplate jdbc;

    public AgentToolBackends(KnowledgeBaseService kbService,
                             KnowledgeService knowledgeService,
                             ChunkRepository chunkRepository,
                             HybridSearchService hybridSearchService,
                             ConversationProperties conversation,
                             MessageService messageService,
                             MemoryService memoryService,
                             WikiPageService wikiPageService,
                             com.ragagent.websearch.service.WebSearchService webSearchService,
                             com.ragagent.auth.service.TenantService tenantService,
                             com.ragagent.knowledge.storage.TenantFileStorage fileStorage,
                             DataSource dataSource) {
        this.kbService = kbService;
        this.knowledgeService = knowledgeService;
        this.chunkRepository = chunkRepository;
        this.hybridSearchService = hybridSearchService;
        this.conversation = conversation;
        this.messageService = messageService;
        this.memoryService = memoryService;
        this.wikiPageService = wikiPageService;
        this.webSearchService = webSearchService;
        this.tenantService = tenantService;
        this.fileStorage = fileStorage;
        this.jdbc = new JdbcTemplate(dataSource);
        this.kbBackends = new AgentToolKbBackends(kbService, knowledgeService, chunkRepository,
                hybridSearchService, conversation, jdbc);
        this.wikiBackends = new AgentToolWikiBackends(wikiPageService);
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
                    new com.ragagent.agent.tools.knowledge.ListKnowledgeChunksTool(knowledgeInfoReader(),
                            chunkById(), pagedChunks(), imageInfoCollector(), targets);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_QUERY_KNOWLEDGE_GRAPH ->
                    new QueryKnowledgeGraphTool(graphSearch(), targets,
                            DocChunkSupport.asScopeReader(knowledgeInfoReader()));
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_GET_DOCUMENT_INFO ->
                    new com.ragagent.agent.tools.knowledge.GetDocumentInfoTool(knowledgeInfoReader(),
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
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_DATA_ANALYSIS ->
                // data_analysis 工具构造：三个 seam 的生产实现
                createDataAnalysisTool(targets, sessionId);
            default -> null;
        };
    }

    /**
     * data_analysis 工具的构造面：
     * KnowledgeLoader = GetKnowledgeByIDOnly（无租户过滤，scope 由 WithSearchTargets
     * 把守）；Materializer = FileService.GetFile + 临时文件（扩展名取 file_path）；
     * DuckDB = 进程内共享内存连接。
     */
    private com.ragagent.agent.tools.AgentTool createDataAnalysisTool(
            SearchTarget.SearchTargets targets, String sessionId) {
        com.ragagent.agent.tools.data.DataAnalysisTool tool =
                new com.ragagent.agent.tools.data.DataAnalysisTool(
                        knowledgeId -> {
                            Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
                            if (k == null) {
                                return null;
                            }
                            return new com.ragagent.agent.tools.data.DataAnalysisTool.KnowledgeData(
                                    k.getId(), k.getKnowledgeBaseId(),
                                    k.getTenantId() == null ? 0L : k.getTenantId(),
                                    k.getFileType(), k.getFilePath());
                        },
                        knowledge -> materializeKnowledgeFile(knowledge),
                        com.ragagent.agent.tools.data.AnalysisDuckDbJdbc.get(),
                        sessionId);
        if (targets != null) {
            tool.withSearchTargets(targets);
        }
        return tool;
    }

    /** 知识文件 → 带正确扩展名的本地临时文件（用后即删）。 */
    private java.nio.file.Path materializeKnowledgeFile(
            com.ragagent.agent.tools.data.DataAnalysisTool.KnowledgeData knowledge) {
        if (knowledge == null || knowledge.filePath() == null || knowledge.filePath().isEmpty()) {
            throw new IllegalArgumentException("knowledge file path is empty");
        }
        byte[] content = fileStorage.read(knowledge.tenantId(), knowledge.filePath());
        String path = knowledge.filePath();
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot) : "";
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("data-analysis-", ext);
            java.nio.file.Files.write(tmp, content);
            return tmp;
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to materialize knowledge file: " + e.getMessage(), e);
        }
    }

    /** wiki 工具簇的薄委托（实现见同包 {@link AgentToolWikiBackends}）。 */
    public WikiPages wikiPages() {
        return wikiBackends.wikiPages();
    }

    /** 空串兜底（门面与 KB 检索簇共用；簇内按类名引用）。 */
    static String nz(String v) {
        return v == null ? "" : v;
    }

    // ── 知识库检索簇的薄委托（实现见同包 AgentToolKbBackends） ──────

    public KnowledgeSearchTool.SearchConfig searchConfig() {
        return kbBackends.searchConfig();
    }

    public KnowledgeSearchTool.KnowledgeSearchBackend knowledgeSearchBackend() {
        return kbBackends.knowledgeSearchBackend();
    }

    public KnowledgeSearchTool.ChunkInfoBackend chunkInfoBackend() {
        return kbBackends.chunkInfoBackend();
    }

    public KnowledgeSearchTool.ImageEnricher imageEnricher() {
        return kbBackends.imageEnricher();
    }

    public static KnowledgeSearchTool.RerankerModel rerankerModel(Reranker reranker) {
        return AgentToolKbBackends.rerankerModel(reranker);
    }

    public GrepChunksTool.GrepChunkSearch grepChunkSearch() {
        return kbBackends.grepChunkSearch();
    }

    public DocChunkSupport.KnowledgeInfoReader knowledgeInfoReader() {
        return kbBackends.knowledgeInfoReader();
    }

    public java.util.function.Function<String, Chunk> chunkById() {
        return kbBackends.chunkById();
    }

    public DocChunkSupport.PagedChunks pagedChunks() {
        return kbBackends.pagedChunks();
    }

    public DocChunkSupport.ImageInfoCollector imageInfoCollector() {
        return kbBackends.imageInfoCollector();
    }

    public QueryKnowledgeGraphTool.GraphSearch graphSearch() {
        return kbBackends.graphSearch();
    }

    // ==================================================================
    // wiki 工具族 10 件
    // ==================================================================

    /**
     * wiki 工具的构造面。{@code scopes} 只给 wiki_read_page /
     * wiki_search（刻意不对称：其余八件收扁平 kbIDs，内部用
     * NewWikiScopesFromKBIDs 重建 scope，按设计丢失 doc/tag 窄化）。
     *
     * @param routes 请求级共享的 slug→KB 路由记忆（一个引擎一个实例）
     */
    public com.ragagent.agent.tools.AgentTool createWikiTool(String toolName,
            SearchTarget.SearchTargets targets, List<WikiScope> scopes,
            List<String> wikiKbIds, WikiRouteResolver routes) {
        WikiPages pages = wikiBackends.wikiPages();
        SearchAuth.KnowledgeScopeReader scopeReader =
                DocChunkSupport.asScopeReader(knowledgeInfoReader());
        return switch (toolName) {
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_READ_PAGE ->
                    new WikiReadPageTool(pages, scopeReader, scopes, routes);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_SEARCH ->
                    new WikiSearchTool(pages, scopeReader, scopes, routes);
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WIKI_READ_SOURCE_DOC ->
                    // 不碰 wiki 服务，只用 chunk 面
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







    // ==================================================================
    // web_search / web_fetch
    // ==================================================================

    /**
     * web 工具的构造面：web_search 收 agent 配置的
     * maxResults/providerID，web_fetch 直构造无参。
     * 租户的 WebSearchConfig 在装配期捕获——执行期从上下文取租户 id，
     * 同一回合内取值等价；tenantID 留在执行期读（==0 拒绝）。
     */
    public com.ragagent.agent.tools.AgentTool createWebTool(String toolName,
            int webSearchMaxResults, String webSearchProviderId) {
        return switch (toolName) {
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WEB_SEARCH -> new WebSearchTool(
                    webSearchBackend(), webSearchMaxResults, webSearchProviderId,
                    currentTenantId(), loadTenantWebSearchConfig());
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_WEB_FETCH -> new WebFetchTool();
            default -> null;
        };
    }

    /** 执行期租户读取（engine 线程已 replay）。 */
    public static LongSupplier currentTenantId() {
        return () -> {
            Long t = TenantContext.currentTenantId();
            return t == null ? 0L : t;
        };
    }

    /** 执行配置直传的检索后端。 */
    public WebSearchTool.WebSearchBackend webSearchBackend() {
        return (tenantId, providerId, config, query) ->
                webSearchService.search(tenantId, providerId, config, query);
    }

    /**
     * 读租户的联网搜索配置并做有效性归一化：
     * 租户行缺失/无配置 → null（工具侧落 DefaultWebSearchConfig 缺省）。
     * 归一化（applyEffective）：maxResults≤0→10、
     * blacklist null→[]。
     */
    private com.ragagent.websearch.service.WebSearchService.WebSearchConfig loadTenantWebSearchConfig() {
        Long tid = TenantContext.currentTenantId();
        if (tid == null || tid == 0) {
            return null;
        }
        try {
            com.ragagent.auth.domain.Tenant tenant = tenantService.getTenantById(tid);
            if (tenant == null || tenant.getWebSearchConfig() == null
                    || tenant.getWebSearchConfig().isNull()) {
                return null;
            }
            com.ragagent.auth.domain.tenantconfig.WebSearchConfig cfg = JSON.treeToValue(
                    tenant.getWebSearchConfig(),
                    com.ragagent.auth.domain.tenantconfig.WebSearchConfig.class);
            cfg.applyEffective();
            com.ragagent.websearch.service.WebSearchService.WebSearchConfig out =
                    new com.ragagent.websearch.service.WebSearchService.WebSearchConfig();
            out.provider = cfg.getProvider();
            out.apiKey = cfg.getApiKey();
            out.maxResults = cfg.getMaxResults();
            out.includeDate = cfg.isIncludeDate();
            out.blacklist = cfg.getBlacklist() == null
                    ? new ArrayList<>() : new ArrayList<>(cfg.getBlacklist());
            out.embeddingModelId = cfg.getEmbeddingModelId();
            out.documentFragments = cfg.getDocumentFragments();
            out.proxyUrl = cfg.getProxyUrl();
            return out;
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
            // 租户/配置不可得即走缺省
            return null;
        }
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
     * SQL 结果值的类型收敛：文本→String、整型→Long、浮点→Double、数值→BigDecimal、布尔→Boolean；
     * uuid/时间类型统一 toString（与既有线格式一致）。
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






    // ==================================================================
    // grep_chunks
    // ==================================================================








    static JsonNode readJson(String raw) {
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






    // ==================================================================
    // query_knowledge_graph
    // ==================================================================



    // ==================================================================
    // 映射辅助
    // ==================================================================



}
