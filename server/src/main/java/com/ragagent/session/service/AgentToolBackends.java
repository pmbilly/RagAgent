package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

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
import com.ragagent.agent.tools.WebFetchTool;
import com.ragagent.agent.tools.WebSearchTool;
import com.ragagent.agent.tools.WikiDeletePageTool;
import com.ragagent.agent.tools.WikiFlagIssueTool;
import com.ragagent.agent.tools.WikiReadIssueTool;
import com.ragagent.agent.tools.WikiReadPageTool;
import com.ragagent.agent.tools.WikiRenamePageTool;
import com.ragagent.agent.tools.WikiReplaceTextTool;
import com.ragagent.agent.tools.WikiPages;
import com.ragagent.agent.tools.WikiRouteResolver;
import com.ragagent.agent.tools.WikiScope;
import com.ragagent.agent.tools.WikiUpdateIssueTool;
import com.ragagent.agent.tools.WikiWritePageTool;
import com.ragagent.agent.tools.WikiSearchTool;
import com.ragagent.agent.tools.WikiReadSourceDocTool;
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
 * 本类把这些 seam 桥到真实服务——每个方法对照的工具侧 Javadoc 与 Go 原实现
 * （agent_service.go L1030-1137 的构造点）逐条映射。</p>
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

    /** 知识库检索簇（§14 步骤 2：KB 检索/grep/图谱/chunk 列举等工具后端）。 */
    private final AgentToolKbBackends kbBackends;

    /** wiki 工具簇（§14 步骤 2：WikiPages 端口实现与视图转换）。 */
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
            case com.ragagent.agent.tools.ToolDefinitions.TOOL_DATA_ANALYSIS ->
                // 对照 Go NewDataAnalysisTool(knowledgeService, fileService, db, sessionID)
                // + WithSearchTargets：三个 seam 的生产实现（2026-09-28 评审接线——
                // 此前 UI 可选但 switch 无 case，工具永远注册不上）
                createDataAnalysisTool(targets, sessionId);
            default -> null;
        };
    }

    /**
     * data_analysis 工具的构造面（对照 agent_service.go 的 data_analysis 构造点）：
     * KnowledgeLoader = GetKnowledgeByIDOnly（无租户过滤，scope 由 WithSearchTargets
     * 把守）；Materializer = FileService.GetFile + 临时文件（扩展名取 file_path）；
     * DuckDB = 进程内共享内存连接。
     */
    private com.ragagent.agent.tools.AgentTool createDataAnalysisTool(
            SearchTarget.SearchTargets targets, String sessionId) {
        com.ragagent.agent.tools.DataAnalysisTool tool =
                new com.ragagent.agent.tools.DataAnalysisTool(
                        knowledgeId -> {
                            Knowledge k = knowledgeService.getKnowledgeByIdOnly(knowledgeId);
                            if (k == null) {
                                return null;
                            }
                            return new com.ragagent.agent.tools.DataAnalysisTool.KnowledgeData(
                                    k.getId(), k.getKnowledgeBaseId(),
                                    k.getTenantId() == null ? 0L : k.getTenantId(),
                                    k.getFileType(), k.getFilePath());
                        },
                        knowledge -> materializeKnowledgeFile(knowledge),
                        com.ragagent.agent.tools.AnalysisDuckDbJdbc.get(),
                        sessionId);
        if (targets != null) {
            tool.withSearchTargets(targets);
        }
        return tool;
    }

    /** 对照 materializeKnowledgeFile：知识文件 → 带正确扩展名的本地临时文件（用后即删）。 */
    private java.nio.file.Path materializeKnowledgeFile(
            com.ragagent.agent.tools.DataAnalysisTool.KnowledgeData knowledge) {
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

    /** wiki 工具簇的薄委托（实现见同包 {@link AgentToolWikiBackends}，§14 步骤 2）。 */
    public WikiPages wikiPages() {
        return wikiBackends.wikiPages();
    }

    /** 空串兜底（门面与 KB 检索簇共用；簇内按类名引用）。 */
    static String nz(String v) {
        return v == null ? "" : v;
    }

    // ── 知识库检索簇的薄委托（实现见同包 AgentToolKbBackends，§14 步骤 2） ──────

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







    // ==================================================================
    // web_search / web_fetch（切片 2d，对照 agent_service.go L1071-1082）
    // ==================================================================

    /**
     * Go {@code registerTools} 的 web 构造面：web_search 收 agent 配置的
     * maxResults/providerID（Go L1071-1078），web_fetch 直构造无参（Go L1079-1082）。
     * 租户的 WebSearchConfig 在装配期捕获——Go 的 Execute 从 ctx 取 TenantInfo，
     * 同一回合内取值等价；tenantID 留在执行期读（对照 TenantIDContextKey 的 ==0 拒绝）。
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

    /** 对照 Tool.ConversationSearch 同款的执行期租户读取（engine 线程已 replay）。 */
    public static LongSupplier currentTenantId() {
        return () -> {
            Long t = TenantContext.currentTenantId();
            return t == null ? 0L : t;
        };
    }

    /** 对照 interfaces.WebSearchService.Search：执行配置直传（类型即 Go 的执行形状）。 */
    public WebSearchTool.WebSearchBackend webSearchBackend() {
        return (tenantId, providerId, config, query) ->
                webSearchService.search(tenantId, providerId, config, query);
    }

    /**
     * 对照 Go Execute 里的 {@code types.EffectiveWebSearchConfig(tenant.WebSearchConfig)}
     * 打底拷贝：租户行缺失/无配置 → null（工具侧落 DefaultWebSearchConfig 缺省）。
     * 归一化（applyEffective）与 Go 的 Effective 一致：maxResults≤0→10、
     * blacklist nil→[]。
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
            // 对照 tenant == nil 分支：配置不可得即走缺省
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






    // ==================================================================
    // grep_chunks（对照 grep_chunks.go 的 searchChunks 整段查询）
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
