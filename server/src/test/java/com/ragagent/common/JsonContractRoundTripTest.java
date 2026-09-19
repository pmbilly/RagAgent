package com.ragagent.common;

import static com.ragagent.common.JsonRoundTrip.assertRoundTrips;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.apikey.domain.TenantAPIKey;
import com.ragagent.audit.controller.AuditLogListResponse;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.agent.domain.ToolCallTarget;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.apikey.domain.TenantAPIKeyCreateResponse;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceSyncPayload;
import com.ragagent.datasource.domain.Resource;
import com.ragagent.datasource.domain.SyncCursor;
import com.ragagent.datasource.domain.SyncItemError;
import com.ragagent.datasource.domain.SyncLog;
import com.ragagent.datasource.domain.SyncResult;
import com.ragagent.datasource.domain.TaskInitiator;
import com.ragagent.datasource.dto.CredentialFieldMetadata;
import com.ragagent.datasource.dto.CredentialsResponse;
import com.ragagent.datasource.dto.DataSourceResponse;
import com.ragagent.apikey.domain.TenantAPIKeyResponse;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.knowledge.domain.ChunkRevision;
import com.ragagent.knowledge.domain.DocumentChunkMetadata;
import com.ragagent.knowledge.dto.FaqDtos;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos;
import com.ragagent.knowledge.domain.FaqChunkMetadata;
import com.ragagent.knowledge.domain.GeneratedQuestion;
import com.ragagent.knowledge.domain.KbAsrConfig;
import com.ragagent.knowledge.domain.KbChunkingConfig;
import com.ragagent.knowledge.domain.KbImageProcessingConfig;
import com.ragagent.knowledge.domain.KbIndexingStrategy;
import com.ragagent.knowledge.domain.KbStorageConfig;
import com.ragagent.knowledge.domain.KbStorageProviderConfig;
import com.ragagent.knowledge.domain.KbVlmConfig;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.retrieval.domain.SearchResult;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.storage.dto.StorageConfig;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.websearch.domain.WebSearchProviderParams;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.memory.domain.MemoryConfig;
import com.ragagent.memory.domain.MemoryConsolidationResult;
import com.ragagent.memory.domain.MemoryDocView;
import com.ragagent.memory.domain.MemorySettings;
import com.ragagent.memory.domain.MemoryTopicView;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import com.ragagent.mcp.domain.McpTestResult;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.MessageArtifact;
import com.ragagent.session.domain.MessageAttachment;
import com.ragagent.session.domain.MessageExecutionContext;
import com.ragagent.session.domain.MessageImage;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionLastRequestState;
import com.ragagent.session.domain.SessionListItem;
import com.ragagent.session.domain.SuggestionAttribution;
import com.ragagent.session.domain.UsedMemory;
import com.ragagent.stream.LiveRunPayload;
import com.ragagent.stream.StreamEvent;
import com.ragagent.wiki.domain.WikiConfig;
import com.ragagent.wiki.service.CombinedExtraction;
import com.ragagent.wiki.service.ExtractedItem;
import com.ragagent.wiki.service.NewSlugFromCitation;
import com.ragagent.wiki.service.SlugUpdate;
import com.ragagent.wiki.service.WikiFinalizeChange;
import com.ragagent.wiki.service.WikiFinalizeRow;
import com.ragagent.wiki.service.WikiIngestConstants;
import com.ragagent.wiki.service.WikiIngestPayload;
import com.ragagent.wiki.service.WikiPendingOp;
import org.junit.jupiter.api.Test;

/**
 * 契约实体的 JSON 往返体检——覆盖所有**会落 jsonb 或直接作响应体**的类型。
 *
 * 这里刻意不写业务断言，只跑 {@link JsonRoundTrip#assertRoundTrips}：
 * 它自动拦截「派生方法漏 @JsonIgnore」与「键名漏蛇形」两类复发错误。
 * 新增领域实体时**请把它加进来**——这是最省事的长期防线。
 *
 * 详情与原理见 {@link JsonRoundTrip} 的类注释。
 */
class JsonContractRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── MCP（auth_config / headers 等 jsonb 列 + 直接作响应体） ──────────────

    @Test
    void mcpAuthConfigRoundTrips() {
        // 这个类型出过事故：isOAuth() 未 @JsonIgnore，把 "oauth":true 写进 auth_config 列，
        // 回读抛 UnrecognizedPropertyException 导致整列不可用。往返断言是本坑的永久防线。
        McpAuthConfig c = new McpAuthConfig();
        c.setAuthType(McpAuthType.API_KEY);
        c.setApiKey("sk-test");
        c.setApiKeyHeader("X-Tenant-Key");
        c.setToken("tok-test");
        c.setCustomHeaders(Map.of("X-Custom", "v1"));
        c.setScopes(List.of("read", "write"));
        c.setAuthServerMetadataUrl("https://auth.example.com/.well-known/oauth-authorization-server");
        assertRoundTrips(c, McpAuthConfig.class, "types.MCPAuthConfig ← McpAuthConfig");
    }

    @Test
    void mcpAdvancedConfigRoundTrips() {
        McpAdvancedConfig c = new McpAdvancedConfig(30, 3, 1);
        assertRoundTrips(c, McpAdvancedConfig.class, "types.MCPAdvancedConfig ← McpAdvancedConfig");
    }

    @Test
    void mcpStdioConfigRoundTrips() {
        McpStdioConfig c = new McpStdioConfig();
        c.setCommand("npx");
        c.setArgs(List.of("-y", "some-mcp-server"));
        assertRoundTrips(c, McpStdioConfig.class, "types.MCPStdioConfig ← McpStdioConfig");
    }

    @Test
    void mcpServiceRoundTrips() {
        McpService s = new McpService();
        s.setId("svc-1");
        s.setTenantId(10002L);
        s.setName("svc");
        s.setDescription("d");
        s.setEnabled(true);
        s.setTransportType("http-streamable");
        s.setUrl("https://mcp.example.com/mcp");
        s.setHeaders(Map.of("X-Custom", "v1"));
        s.setAuthConfig(new McpAuthConfig());
        s.setAdvancedConfig(McpAdvancedConfig.defaults());
        s.setStdioConfig(new McpStdioConfig());
        s.setEnvVars(Map.of("ENV", "1"));
        s.setIsBuiltin(false);
        s.setUsageInstructions("usage");
        assertRoundTrips(s, McpService.class, "types.MCPService ← McpService（含 jsonb 子对象）");
    }

    @Test
    void mcpToolRoundTrips() {
        // 响应体 + mcp_metadata.tools 列。inputSchema/require_approval 是协议/契约键名。
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        McpTool t = new McpTool("search", "search docs", schema);
        t.setRequireApproval(true);
        assertRoundTrips(t, McpTool.class, "types.MCPTool ← McpTool");
    }

    @Test
    void mcpToolApprovalRoundTrips() {
        // 直接作为 GET /{id}/tool-approvals 的响应体，键名必须逐字对齐 Go tag。
        McpToolApproval a = new McpToolApproval();
        a.setId("a-1");
        a.setTenantId(10002L);
        a.setServiceId("svc-1");
        a.setToolName("search");
        a.setRequireApproval(true);
        a.setEnabled(false);
        assertRoundTrips(a, McpToolApproval.class, "types.MCPToolApproval ← McpToolApproval");
    }

    @Test
    void mcpResourceAndTestResultRoundTrip() {
        McpResource r = new McpResource();
        r.setUri("file:///a");
        r.setName("a");
        r.setDescription("d");
        r.setMimeType("text/plain");
        assertRoundTrips(r, McpResource.class, "types.MCPResource ← McpResource");

        McpTestResult tr = McpTestResult.ok("ok");
        tr.setDescription("d");
        tr.setOauthRequired(true);
        tr.setTools(List.of(new McpTool("t", "d", null)));
        tr.setResources(List.of(r));
        // McpTestResult 含 MCPTool 子对象，一并覆盖
        assertRoundTrips(tr, McpTestResult.class, "types.MCPTestResult ← McpTestResult");
    }

    // ── 知识库配置（全部落 jsonb 列） ──────────────────────────────────────

    @Test
    void kbConfigsRoundTrip() {
        KbChunkingConfig chunk = new KbChunkingConfig();
        chunk.setChunkSize(512);
        chunk.setChunkOverlap(80);
        chunk.setSeparators(List.of("\n\n", "\n"));
        chunk.setEnableParentChild(true);
        chunk.setParentChunkSize(4096);
        chunk.setChildChunkSize(384);
        chunk.setStrategy("auto");
        chunk.setTokenLimit(1000);
        chunk.setLanguages(List.of("zh"));
        chunk.setTableMetadataInstructions("instr");
        assertRoundTrips(chunk, KbChunkingConfig.class, "types.ChunkingConfig ← KbChunkingConfig");

        KbVlmConfig vlm = new KbVlmConfig();
        vlm.setEnabled(true);
        vlm.setModelId("m1");
        vlm.setDescriptionLanguage("zh");
        vlm.setCustomInstructions("x");
        vlm.setModelName("n");
        vlm.setBaseUrl("https://x");
        vlm.setApiKey("k");
        vlm.setInterfaceType("openai");
        assertRoundTrips(vlm, KbVlmConfig.class, "types.VLMConfig ← KbVlmConfig");

        KbAsrConfig asr = new KbAsrConfig();
        asr.setEnabled(true);
        asr.setModelId("m");
        asr.setLanguage("zh");
        assertRoundTrips(asr, KbAsrConfig.class, "types.ASRConfig ← KbAsrConfig");

        KbIndexingStrategy idx = KbIndexingStrategy.defaultStrategy();
        assertRoundTrips(idx, KbIndexingStrategy.class, "types.IndexingStrategy ← KbIndexingStrategy");

        KbImageProcessingConfig img = new KbImageProcessingConfig();
        img.setModelId("m");
        assertRoundTrips(img, KbImageProcessingConfig.class, "types.ImageProcessingConfig ← KbImageProcessingConfig");

        KbStorageConfig storage = new KbStorageConfig();
        storage.setProvider("local");
        storage.setSecretId("s");
        storage.setSecretKey("k");
        storage.setRegion("r");
        storage.setBucketName("b");
        storage.setAppId("a");
        storage.setPathPrefix("p");
        assertRoundTrips(storage, KbStorageConfig.class, "types.StorageConfig ← KbStorageConfig");

        KbStorageProviderConfig provider = new KbStorageProviderConfig();
        provider.setProvider("local");
        assertRoundTrips(provider, KbStorageProviderConfig.class,
                "types.StorageProviderConfig ← KbStorageProviderConfig");
    }

    // ── Wiki 配置（落 knowledge_bases.wiki_config 列） ─────────────────────

    @Test
    void wikiConfigRoundTrips() {
        WikiConfig c = new WikiConfig();
        assertRoundTrips(c, WikiConfig.class, "types.WikiConfig ← WikiConfig");
    }

    // ── Wiki 批次管道（落 task_pending_ops.payload / task_dead_letters.payload） ──

    /**
     * 批次执行体（wiki_ingest_batch.go / wiki_ingest_cite.go / wiki_ingest_dedup.go）
     * 读写的那批 jsonb 载荷。
     *
     * <p>跨语言读写时字段名必须逐字对齐 Go 的 json tag；任何
     * {@code isXxx()}/{@code getXxx()} 派生方法都必须 {@code @JsonIgnore}
     * （约定 §9 复发率最高的坑），否则整列回读会抛
     * {@code UnrecognizedPropertyException}。</p>
     */
    @Test
    void wikiBatchPayloadsRoundTrip() {
        assertRoundTrips(
                new WikiIngestPayload(7L, "kb-1", "zh-CN"),
                WikiIngestPayload.class,
                "service.WikiIngestPayload ← WikiIngestPayload");
        // omitempty 的 language 省略后仍须往返幂等
        assertRoundTrips(
                new WikiIngestPayload(7L, "kb-1", null),
                WikiIngestPayload.class,
                "service.WikiIngestPayload（language 省略）← WikiIngestPayload");

        assertRoundTrips(
                WikiFinalizeRow.slug("entity/a", "A"),
                WikiFinalizeRow.class,
                "service.wikiFinalizeRow ← WikiFinalizeRow（slug 行）");
        assertRoundTrips(
                WikiFinalizeRow.change(WikiFinalizeChange.added("Doc", "Sum")),
                WikiFinalizeRow.class,
                "service.wikiFinalizeRow ← WikiFinalizeRow（change 行）");
        assertRoundTrips(
                WikiFinalizeRow.folderIds(List.of("folder-a")),
                WikiFinalizeRow.class,
                "service.wikiFinalizeRow ← WikiFinalizeRow（folder_prune 行）");

        WikiPendingOp op = new WikiPendingOp(WikiIngestConstants.OP_INGEST, "kid-1");
        op.setLanguage("zh-CN");
        op.setDocTitle("Title");
        op.setDocSummary("Summary");
        op.setPageSlugs(List.of("entity/a"));
        op.setFolderIds(List.of("folder-a"));
        assertRoundTrips(op, WikiPendingOp.class, "service.WikiPendingOp ← WikiPendingOp");

        ExtractedItem item = new ExtractedItem(
                "孔子", "entity/kong-zi", List.of("孔丘"), "desc", "details");
        item.setSourceChunks(List.of("c1", "c2"));
        assertRoundTrips(item, ExtractedItem.class, "service.extractedItem ← ExtractedItem");

        SlugUpdate update = new SlugUpdate("entity/kong-zi", SlugUpdate.TYPE_ENTITY);
        update.setItem(item);
        update.setDocTitle("Doc");
        update.setKnowledgeId("kid-1");
        update.setSourceRef("kid-1");
        update.setLanguage("zh-CN");
        update.setSummaryBody("body");
        update.setSummaryLine("line");
        update.setRetractDocContent("old");
        update.setSourceChunks(List.of("c1"));
        update.setDocSummary("doc summary");
        assertRoundTrips(update, SlugUpdate.class, "service.SlugUpdate ← SlugUpdate");

        assertRoundTrips(
                new NewSlugFromCitation("entity", "Fresh", "entity/fresh", List.of("A"),
                        "desc", "details", List.of("c1", "c2")),
                NewSlugFromCitation.class,
                "cite.newSlugFromCitation ← NewSlugFromCitation");

        assertRoundTrips(
                new CombinedExtraction(List.of(item), List.of(item)),
                CombinedExtraction.class,
                "service.combinedExtraction ← CombinedExtraction");
    }

    // ── LLM 契约（StreamResponse 落库 / TokenUsage 落 jsonb） ───────────────

    @Test
    void tokenUsageRoundTrips() {
        TokenUsage u = new TokenUsage();
        u.setPromptTokens(100);
        u.setCompletionTokens(50);
        u.setTotalTokens(150);
        u.setPromptCacheUsage(30, 10, 60, true);
        assertRoundTrips(u, TokenUsage.class, "types.TokenUsage ← TokenUsage");
    }

    @Test
    void chatContractsRoundTrip() {
        ChatMessage m = new ChatMessage("assistant", "hi");
        m.setReasoningContent("thinking");
        m.setToolCallId("tc-1");
        m.setName("search");
        assertRoundTrips(m, ChatMessage.class, "chat.Message ← ChatMessage");

        ChatOptions o = new ChatOptions();
        o.setTemperature(0.7);
        o.setThinking(true);
        o.setToolChoice("auto");
        o.setTools(List.of(new ChatTool("t", "d", null)));
        assertRoundTrips(o, ChatOptions.class, "chat.ChatOptions ← ChatOptions");
    }

    @Test
    void memorySettingsSliceRoundTrips() {
        // 波 0 第 1 步：memory 的 settings 切片。MemoryConfig 是 tenants 上的 jsonb，
        // 其余四个是响应体。注意 vector_recall/retrieval_conditioning 是**三态** Boolean——
        // 往返必须保留 null 与显式 false 的区别。
        MemoryConfig c = new MemoryConfig();
        c.setEnabled(true);
        c.setWriteMode(MemoryConfig.WRITE_MODE_AUTO);
        c.setExtractModelId("m1");
        c.setMaxItems(200);
        c.setExtractDelaySeconds(30);
        c.setExtractMinIntervalSeconds(60);
        c.setExtractInstructions("instr");
        c.setInterestThreshold(3);
        c.setEmbeddingModelId("e1");
        c.setVectorRecall(true);
        c.setRetrievalConditioning(false);
        assertRoundTrips(c, MemoryConfig.class, "types.MemoryConfig ← MemoryConfig");

        MemoryConfig zero = new MemoryConfig();
        assertRoundTrips(zero, MemoryConfig.class, "types.MemoryConfig（零值，两个指针为 null）← MemoryConfig");

        MemorySettings settings = new MemorySettings();
        settings.setWorkspaceEnabled(true);
        settings.setWriteMode(MemoryConfig.WRITE_MODE_EXPLICIT_ONLY);
        settings.setItemCount(7);
        settings.setMaxItems(200);
        assertRoundTrips(settings, MemorySettings.class, "types.MemorySettings ← MemorySettings");

        MemoryConsolidationResult result = new MemoryConsolidationResult();
        result.setMerged(2);
        result.setSkipped(MemoryConsolidationResult.SKIP_TOO_SOON);
        assertRoundTrips(result, MemoryConsolidationResult.class,
                "types.MemoryConsolidationResult ← MemoryConsolidationResult");

        MemoryTopicView topic = new MemoryTopicView();
        topic.setId("t1");
        topic.setTopic("db");
        topic.setAliases(List.of("a", "b"));
        topic.setHits(2);
        topic.setThreshold(3);
        topic.setLastSeenAt(java.time.OffsetDateTime.now());
        assertRoundTrips(topic, MemoryTopicView.class, "types.MemoryTopicView ← MemoryTopicView");

        MemoryDocView doc = new MemoryDocView();
        doc.setId("d1");
        doc.setKnowledgeId("k1");
        doc.setKnowledgeBaseId("kb1");
        doc.setTitle("t");
        doc.setHits(4);
        doc.setLastUsedAt(java.time.OffsetDateTime.now());
        assertRoundTrips(doc, MemoryDocView.class, "types.MemoryDocView ← MemoryDocView");
    }

    @Test
    void searchResultRoundTrips() {
        // 检索结果：既是 SSE references 事件的载荷，也是 messages.knowledge_references 的元素。
        // 两个 json:"-" 的内部字段（ContentRevision/ContentRewritten）必须 @JsonIgnore——
        // 否则会被写进 jsonb 再回读，正是 §9 记的那个复发坑。
        SearchResult sr = new SearchResult();
        sr.setId("chunk-1");
        sr.setContent("hello");
        sr.setKnowledgeId("kb-1");
        sr.setChunkIndex(3);
        sr.setKnowledgeTitle("t");
        sr.setStartAt(10);
        sr.setEndAt(20);
        sr.setSeq(2);
        sr.setScore(0.75);
        sr.setMatchType(3);
        sr.setSubChunkId(List.of("sub-1"));
        sr.setMetadata(Map.of("lang", "zh"));
        sr.setChunkType("text");
        sr.setParentChunkId("p-1");
        sr.setImageInfo("{}");
        sr.setKnowledgeFilename("a.md");
        sr.setKnowledgeSource("file");
        sr.setKnowledgeChannel("web");
        sr.setMatchedContent("matched");
        sr.setKnowledgeDescription("d");
        sr.setKnowledgeCustomMetadata("cm");
        sr.setKnowledgeBaseId("kb-1");
        sr.setContentRevision(7);
        sr.setContentRewritten(true);
        assertRoundTrips(sr, SearchResult.class, "types.SearchResult ← SearchResult");
    }

    @Test
    void agentStepTypesRoundTrip() {
        // agent_steps 列的组成类型。三个派生访问器（getObservations / getExecutionName /
        // getExecutionArgs）在 Go 里都是方法，漏 @JsonIgnore 会把整列写坏——往返断言是防线。
        ToolCallTarget target = new ToolCallTarget();
        target.setName("svc.tool");
        target.setArgs(Map.of("k", "v"));
        target.setServiceName("svc");
        target.setToolName("tool");
        assertRoundTrips(target, ToolCallTarget.class, "types.ToolCallTarget ← ToolCallTarget");

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput("out");
        result.setData(Map.of("k", "v"));
        result.setError("e");
        result.setImages(List.of("i"));
        assertRoundTrips(result, ToolResult.class, "types.ToolResult ← ToolResult");

        ToolCall call = new ToolCall();
        call.setTarget(target);
        call.setId("call-1");
        call.setName("search");
        call.setArgs(Map.of("a", "1"));
        call.setResult(result);
        call.setReflection("ref");
        call.setDuration(42);
        call.setProviderMetadata(Map.of("gemini", MAPPER.createObjectNode().put("x", 1)));
        assertRoundTrips(call, ToolCall.class, "types.ToolCall ← agent.domain.ToolCall");

        AgentStep step = new AgentStep();
        step.setIteration(1);
        step.setThought("t");
        step.setUserMessagesBefore(List.of("m1"));
        step.setIntermediateAnswer(true);
        step.setReasoningContent("rc");
        step.setToolCalls(List.of(call));
        // 时间必须显式给：Go 的零值时间会输出 year-1 字面量，而**裸 STRICT mapper
        // 读不回它**——本类型的两个时间方法自带的序列化器能覆盖，但断言用真实值更稳。
        step.setTimestamp(java.time.OffsetDateTime.now());
        assertRoundTrips(step, AgentStep.class, "types.AgentStep ← AgentStep");
    }

    /** 零值时间也必须能往返（Go 的值类型语义，输出 year-1 字面量而非 null）。 */
    @Test
    void agentStepZeroTimeRoundTrips() {
        assertRoundTrips(new AgentStep(), AgentStep.class, "types.AgentStep（零值）← AgentStep");
    }

    @Test
    void streamResponseRoundTrips() {
        // SSE 事件体：id/response_type/content/done 恒输出，其余 omitempty。
        // data 的键序由 GoMapSerializer 递归对齐 Go——往返必须幂等。
        StreamResponse r = StreamResponse.of(ResponseType.REFERENCES, "", false);
        r.setId("req-1");
        r.setSessionId("sess-1");
        r.setAssistantMessageId("msg-1");
        r.setFinishReason("stop");

        SearchResult sr = new SearchResult();
        sr.setId("chunk-1");
        r.setKnowledgeReferences(List.of(sr));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zeta", "1");
        data.put("alpha", Map.of("inner_z", 1, "inner_a", 2));
        r.setData(data);

        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(5);
        r.setUsage(usage);

        assertRoundTrips(r, StreamResponse.class, "types.StreamResponse ← StreamResponse");
    }

    // ── 租户 API Key（tenant_api_keys 的两个 jsonb 列 + 四个管理端点的响应体） ──

    /**
     * API Key 契约实体。三类风险各钉一条：
     * <ol>
     *   <li>{@code TenantAPIKey.isPlatform()} / {@code tenantIdValue()} 在 Go 里是
     *       <b>方法</b>——漏 {@code @JsonIgnore} 会把 {@code "platform":true} 写进
     *       序列化结果（约定 §9 复发率最高的坑）；</li>
     *   <li>{@code key_hash} 的 Go tag 是 {@code json:"-"}，必须双向忽略；</li>
     *   <li>响应体 {@code TenantAPIKeyResponse} 的蛇形键名与
     *       {@code TenantAPIKeyCreateResponse} 的 token 末位。</li>
     * </ol>
     */
    @Test
    void tenantApiKeyContractsRoundTrip() {
        TenantAPIKey key = new TenantAPIKey();
        key.setId(7L);
        key.setTenantId(42L);
        key.setScopeType("tenant");
        key.setName("integration");
        key.setKeyHash("deadbeef");          // json:"-" → 不进 JSON
        key.setApiKey("sk-plaintext");
        key.setFullAccess(false);
        key.setKnowledgeBaseIds(List.of("kb-1", "kb-2"));
        key.setCapabilities(List.of("retrieve", "chat"));
        // 时间字段留空：本工具用的是**裸** ObjectMapper（未注册 JSR-310 模块），
        // 非空 OffsetDateTime 会在这里炸，而时间键名/格式已由
        // TenantAPIKeyControllerTest 与 JacksonConfig 覆盖。
        assertRoundTrips(key, TenantAPIKey.class,
                "types.TenantAPIKey ← TenantAPIKey（jsonb 数组列 + 派生方法须 @JsonIgnore）");

        TenantAPIKeyResponse response = TenantAPIKeyResponse.from(key);
        assertRoundTrips(response, TenantAPIKeyResponse.class,
                "handler.tenantAPIKeyResponse ← TenantAPIKeyResponse");
        // 三个成功响应体都经它派生，token 在末位
        assertRoundTrips(TenantAPIKeyCreateResponse.of(response, "sk-once"),
                TenantAPIKeyCreateResponse.class,
                "handler.tenantAPIKeyCreateResponse ← TenantAPIKeyCreateResponse");
    }

    // ── 审计日志（audit_logs.details 落 jsonb + 三个端点的响应体元素） ──────

    /**
     * 审计契约实体。三类风险各钉一条：
     * <ol>
     *   <li>{@code AuditLog} 的 15 个键<b>无 omitempty</b>——键名必须逐字对齐 Go tag
     *       （{@code actor_user_id} / {@code target_user_id} / {@code request_method} …
     *       最易漏的是 {@code scope_type}/{@code scope_id}，它们是迁移 000073 才加的）；</li>
     *   <li>它<b>同时是</b> jsonb 列的宿主：{@code details} 走 PgJsonTypeHandler，
     *       本测试的裸 ObjectMapper 不注册 JSR-310，所以时间字段留空
     *       （时间格式另由 JacksonConfig + 控制器测试覆盖）；</li>
     *   <li>响应信封 {@code auditLogListResponse} 的 {@code next_cursor} 是蛇形。</li>
     * </ol>
     * <p>本类刻意<b>没有</b> isXxx() 派生访问器——若将来有人加（例如
     * {@code isDenied()}），往返断言会在反序列化阶段直接炸，把那个坑挡在提交前。</p>
     */
    @Test
    void auditLogContractsRoundTrip() {
        ObjectNode details = MAPPER.createObjectNode();
        details.put("raw_path", "/api/v1/tenants/7");
        details.put("required_role", "admin");

        AuditLog entry = new AuditLog();
        entry.setId(102L);
        entry.setTenantId(7L);
        entry.setActorUserId("u-viewer");
        entry.setActorRole("viewer");
        entry.setAction(AuditAction.ACCESS_DENIED);
        entry.setScopeType("knowledge_base");
        entry.setScopeId("kb-a");
        entry.setTargetType("wiki");
        entry.setTargetId("kb-a");
        entry.setTargetUserId("u-target");
        entry.setRequestPath("/api/v1/tenants/*/audit-log");
        entry.setRequestMethod("GET");
        entry.setOutcome(AuditOutcome.DENIED);
        entry.setDetails(details);
        assertRoundTrips(entry, AuditLog.class,
                "types.AuditLog ← AuditLog（jsonb details + 15 个无 omitempty 的键）");

        // details 省略（nil）也要能往返：Go 侧 nil RawMessage 输出 null / 由库默认补 '{}'。
        AuditLog bare = new AuditLog();
        bare.setTenantId(0L);
        bare.setAction(AuditAction.SYSTEM_SETTING_CHANGED);
        bare.setOutcome(AuditOutcome.SUCCESS);
        assertRoundTrips(bare, AuditLog.class, "types.AuditLog ← AuditLog（details 为空）");

        assertRoundTrips(
                AuditLogListResponse.of(List.of(entry)),
                AuditLogListResponse.class,
                "handler.auditLogListResponse ← AuditLogListResponse");
        // 空页：next_cursor=0 且 data 归一为 []（Go 侧为 null，见类注释的已知差异）
        assertRoundTrips(
                AuditLogListResponse.of(List.of()),
                AuditLogListResponse.class,
                "handler.auditLogListResponse ← AuditLogListResponse（空页）");
    }

    // ── 流事件（落 Redis；Go 与 Java **共用同一批键**，本文件里唯一不是 jsonb/HTTP 的契约） ──

    /**
     * {@code interfaces.StreamEvent} 与 {@code liveRunPayload}。
     *
     * <p>它们不落 jsonb、也不作 HTTP 响应体，但**跨语言共享**：Go 与 Java 两个实现
     * 读写同一批 Redis 键，且 {@code ClearLiveRun} / {@code UpdateSteerEventData}
     * 都在原始字节上做 CAS。键名或零值语义一变，跨语言的 CAS 就会静默失效——
     * 所以同样纳入这道防线。</p>
     *
     * <p>timestamp 留空：本工具用的是**裸** ObjectMapper（未注册 JSR-310），
     * 非空值会在这里炸；其真实字节形状由 {@code com.ragagent.stream.StreamJsonTest}
     * 对着 Go 的实录钉住。</p>
     */
    @Test
    void streamEventContractsRoundTrip() {
        StreamEvent event = new StreamEvent("e-1", ResponseType.ANSWER, "hi", true);
        event.setData(Map.of("consumed", true));
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(3);
        usage.setTotalTokens(3);
        event.setUsage(usage);
        assertRoundTrips(event, StreamEvent.class,
                "interfaces.StreamEvent ← StreamEvent（Redis 流事件，data/usage 为 omitempty）");

        // 空 data/usage 也要能往返：Go 侧 omitempty 省略后仍须幂等
        assertRoundTrips(new StreamEvent("e-2", ResponseType.STEER, "", false), StreamEvent.class,
                "interfaces.StreamEvent ← StreamEvent（省略 data/usage）");

        assertRoundTrips(new LiveRunPayload("msg-1", "req-1"), LiveRunPayload.class,
                "stream.liveRunPayload ← LiveRunPayload");
    }

    // ── 会话 / 消息（阶段 5） ──────────────────────────────────────────────

    /**
     * {@code types.Session} 与它落 jsonb 的两个伴生类型。
     *
     * <p>风险点：{@code last_request_state} 复用**遗留的 {@code agent_config} 列**，
     * 且 {@code Session} 的响应形态是**裸 struct**（GET /sessions/{id} 直接把它塞进
     * {@code data}）——所以键序是 Go struct 声明序，不是字母序。</p>
     *
     * <p>时间字段留空：本工具用的是**裸** ObjectMapper（未注册 JSR-310）。</p>
     */
    @Test
    void sessionContractsRoundTrip() {
        Session s = new Session();
        s.setId("sess-1");
        s.setTitle("标题");
        s.setDescription("desc");
        s.setTenantId(10002L);
        s.setUserId("u-1");
        s.setPinned(true);
        s.setSandboxConfigId("sc-1");
        s.setImPlatform("feishu");

        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId("agent-1");
        state.setAgentEnabled(true);
        state.setModelId("model-1");
        state.setKnowledgeBaseIds(List.of("kb-1"));
        state.setKnowledgeIds(List.of("k-1"));
        state.setTagIds(List.of("t-1"));
        state.setMcpServiceIds(List.of("mcp-1"));
        state.setSkillNames(List.of("skill-1"));
        state.setMentionedItems(List.of(new MentionedItem()));
        state.setLocalBrowserEnabled(true);
        state.setWebSearchEnabled(true);
        s.setLastRequestState(state);

        assertRoundTrips(s, Session.class,
                "types.Session ← Session（裸响应体 + 复用 agent_config 列的 last_request_state）");

        // 全空也要能往返：omitempty 的字段被省略后仍须幂等
        assertRoundTrips(new Session(), Session.class, "types.Session ← Session（全空）");

        // 列表项：同样是裸响应体元素，且内嵌了要遮蔽掉的内层 im_platform
        SessionListItem item = new SessionListItem();
        item.setId("sess-1");
        item.setTitle("T");
        item.setTenantId(10002L);
        item.setUserId("u-1");
        item.setPinned(true);
        item.setImPlatform("feishu");
        item.setImChatId("c1");
        assertRoundTrips(item, SessionListItem.class,
                "types.SessionListItem ← SessionListItem（内嵌 Session + 外层同名字段遮蔽）");
    }

    @Test
    void sessionLastRequestStateRoundTripsAlone() {
        // 它自己就是 agent_config 列的内容，单独钉一条
        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId("agent-1");
        state.setAgentEnabled(true);
        assertRoundTrips(state, SessionLastRequestState.class,
                "types.SessionLastRequestState ← SessionLastRequestState（agent_config 列）");
        assertRoundTrips(new SessionLastRequestState(), SessionLastRequestState.class,
                "types.SessionLastRequestState ← SessionLastRequestState（全空）");
    }

    @Test
    void mentionedItemRoundTrips() {
        // 八个键**全部无 omitempty**：未用的字段要输出空串而不是省略
        MentionedItem item = new MentionedItem();
        item.setId("kb-1");
        item.setName("产品手册");
        item.setType("kb");
        item.setKbType("document");
        item.setKbId("kb-1");
        item.setKbName("产品手册");
        item.setServiceId("svc-1");
        item.setSkillName("skill-1");
        assertRoundTrips(item, MentionedItem.class, "types.MentionedItem ← MentionedItem");

        assertRoundTrips(new MentionedItem(), MentionedItem.class,
                "types.MentionedItem ← MentionedItem（全空——恒输出八个空串键）");
    }

    /**
     * {@code types.Message} 及其 jsonb 子类型（阶段 5）。
     *
     * <p>三类风险各钉一条：</p>
     * <ol>
     *   <li>{@code MessageAttachment.url} 是 {@code json:"-"}——响应与落库**都不该带**
     *       （内部存储句柄外泄 = 可跨会话下载的引用）；</li>
     *   <li>{@code MessageAttachment.isTruncated} 与 {@code Message.isCompleted}/{@code isFallback}
     *       是「字段名不能带 is 前缀」的那类坑（见 §9）；</li>
     *   <li>{@code Message.executionContext} 也是 {@code json:"-"}，但它**要落库**——
     *       所以子结构 {@link MessageExecutionContext} 必须能往返。</li>
     * </ol>
     * <p>时间字段留空：本工具用的是裸 ObjectMapper（未注册 JSR-310）。</p>
     */
    @Test
    void messageContractsRoundTrip() {
        Message m = new Message();
        m.setId("m1");
        m.setSessionId("s1");
        m.setRequestId("r1");
        m.setContent("hi");
        m.setRole(Message.ROLE_ASSISTANT);
        SearchResult mref = new SearchResult();
        mref.setId("k1");
        m.setKnowledgeReferences(new java.util.ArrayList<>(List.of(mref)));
        AgentStep mstep = new AgentStep();
        mstep.setIteration(0);
        m.setAgentSteps(new java.util.ArrayList<>(List.of(mstep)));
        m.setMentionedItems(new java.util.ArrayList<>(List.of(new MentionedItem())));
        m.setImages(new java.util.ArrayList<>(List.of(new MessageImage())));
        m.setAttachments(new java.util.ArrayList<>(List.of(new MessageAttachment())));
        m.setArtifacts(new java.util.ArrayList<>(List.of(new MessageArtifact())));
        m.setCompleted(true);
        m.setFallback(true);
        m.setAgentDurationMs(42);
        m.setChannel("web");
        m.setAgentId("ag1");
        m.setAgentTenantId(7);
        m.setModelId("md1");
        m.setKnowledgeId("kn1");
        m.setUsedMemories(List.of(new UsedMemory()));
        assertRoundTrips(m, Message.class,
                "types.Message ← Message（跨模块列表字段先按不透明类型透传）");

        assertRoundTrips(new Message(), Message.class, "types.Message ← Message（全空）");

        // 附件：url 双向忽略（json:"-" 同时管响应与落库）
        MessageAttachment att = new MessageAttachment();
        att.setId("a1");
        att.setUrl("secret://internal-handle");
        att.setFileName("f.pdf");
        att.setFileSize(10);
        att.setTruncated(true);
        assertRoundTrips(att, MessageAttachment.class, "types.MessageAttachment ← MessageAttachment");

        MessageImage img = new MessageImage();
        img.setUrl("u");
        img.setCaption("c");
        assertRoundTrips(img, MessageImage.class, "types.MessageImage ← MessageImage");

        assertRoundTrips(new UsedMemory(), UsedMemory.class, "types.UsedMemory ← UsedMemory");

        // execution_context 不出响应，但**要落库**，所以子结构必须能往返
        MessageExecutionContext ctx = new MessageExecutionContext();
        ctx.setAgentConfigHash("h");
        ctx.setQuestionSuggestions(Map.of("enabled", true));
        ctx.setTagScopes(List.of(Map.of("tag_id", "t1")));
        ctx.setWebSearchEnabled(true);
        ctx.setSuggestionAttribution(new SuggestionAttribution());
        ctx.setLangfuseTraceparent("00-abc-def-01");
        assertRoundTrips(ctx, MessageExecutionContext.class,
                "types.MessageExecutionContext ← MessageExecutionContext（execution_context 列）");
    }

    /**
     * 数据源模块的契约往返（{@code datasource/domain} + {@code datasource/dto}）。
     *
     * <p>三类东西必须能往返，理由各不相同：</p>
     * <ol>
     *   <li><b>落 jsonb 的</b>：{@code DataSource.config} / {@code last_sync_cursor} /
     *       {@code last_sync_result} 是"原样透传"的列，经 {@code PgJsonTypeHandler}
     *       读写——键名漏蛇形或漏 {@code @JsonIgnore} 会让整列读不回来
     *       （§7.5 第 2、3 条，复发率最高的一条）；</li>
     *   <li><b>作响应体的</b>：{@code SyncLog} / {@code Resource} /
     *       {@code DataSourceResponse} 都是裸实体出参，多一个键就是线上多发一个键；</li>
     *   <li><b>领域对象的派生访问器</b>：{@code DataSourceConfig.isMultimodalEnabled()}
     *       在 Go 里是 {@code json:"-"} 的运行期字段（不落库、不进响应），漏标会让
     *       {@code config} 列多出一个 {@code multimodalEnabled}，回读时因未知属性直接炸
     *       ——本断言就是那道防线。</li>
     * </ol>
     */
    @Test
    void dataSourceContractsRoundTrip() {
        // ── 落 jsonb 的配置 ──────────────────────────────────────────────
        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        cfg.setResourceIds(List.of("r1", "r2"));
        cfg.setSettings(new java.util.LinkedHashMap<>(Map.of("feed_urls", "http://f")));
        cfg.setCredentials(new java.util.LinkedHashMap<>(Map.of("auth_headers", "X-Token: t")));
        cfg.setMultimodalEnabled(true); // json:"-"（本行是它的防线）
        assertRoundTrips(cfg, DataSourceConfig.class,
                "types.DataSourceConfig ← DataSourceConfig（multimodal_enabled 不出现）");

        assertRoundTrips(new DataSourceConfig(), DataSourceConfig.class,
                "types.DataSourceConfig ← DataSourceConfig（全空：四个键恒输出）");

        // ── 同步游标与结果（同样落 jsonb） ────────────────────────────────
        SyncCursor cursor = new SyncCursor();
        cursor.setLastSyncTime(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        cursor.setConnectorCursor(new java.util.LinkedHashMap<>(Map.of("page", "2", "hash", "abc")));
        cursor.setLastSchemaHash("h1");
        assertRoundTrips(cursor, SyncCursor.class, "types.SyncCursor ← SyncCursor");

        SyncItemError itemError = new SyncItemError();
        itemError.setTitle("doc");
        itemError.setCode("FEISHU_TOKEN_EXPIRED");
        itemError.setParams(Map.of("code", "99991663"));
        itemError.setMessage("token expired");

        SyncResult result = new SyncResult();
        result.setTotal(3);
        result.setCreated(1);
        result.setUpdated(1);
        result.setDeleted(1);
        result.setSkipped(1);
        result.setFailed(2);
        result.setDeletionFailed(1);
        result.setErrors(List.of(itemError));
        result.setNextCursor(cursor);
        assertRoundTrips(result, SyncResult.class, "types.SyncResult ← SyncResult");

        assertRoundTrips(new SyncItemError(), SyncItemError.class,
                "types.SyncItemError ← SyncItemError");

        // ── 响应体 ──────────────────────────────────────────────────────
        ObjectNode syncResultNode = MAPPER.createObjectNode();
        syncResultNode.put("total", 2);
        SyncLog syncLog = new SyncLog();
        syncLog.setId("l1");
        syncLog.setDataSourceId("d1");
        syncLog.setTenantId(7L);
        syncLog.setStatus(DataSourceConstants.SYNC_LOG_STATUS_SUCCESS);
        syncLog.setStartedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        syncLog.setFinishedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        syncLog.setItemsTotal(2);
        syncLog.setErrorMessage("");
        syncLog.setResult(syncResultNode);
        assertRoundTrips(syncLog, SyncLog.class, "types.SyncLog ← SyncLog（裸实体响应体）");
        assertRoundTrips(new SyncLog(), SyncLog.class,
                "types.SyncLog ← SyncLog（全空：一个 omitempty 都没有）");

        Resource resource = new Resource();
        resource.setExternalId("e1");
        resource.setName("Stub Feed");
        resource.setType("feed");
        resource.setDescription("desc");
        resource.setUrl("http://u");
        resource.setModifiedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        resource.setParentId("");
        resource.setHasChildren(false);
        resource.setMetadata(new java.util.LinkedHashMap<>(Map.of("item_count", 2)));
        assertRoundTrips(resource, Resource.class, "types.Resource ← Resource（裸数组响应体）");

        // ── 任务载荷（进进程内队列，不落库也不出响应） ────────────────────
        assertRoundTrips(new DataSourceSyncPayload(
                        new TaskInitiator("u1", "admin"), "manual", "d1", 7L, "l1", false, 10),
                DataSourceSyncPayload.class, "types.DataSourceSyncPayload ← DataSourceSyncPayload");
        assertRoundTrips(new TaskInitiator("", ""), TaskInitiator.class, "types.TaskInitiator ← {}");

        // ── HTTP 出参 DTO ───────────────────────────────────────────────
        assertRoundTrips(CredentialsResponse.credentials(true), CredentialsResponse.class,
                "dto.CredentialsResponse ← CredentialsResponse");
        assertRoundTrips(new CredentialFieldMetadata(true), CredentialFieldMetadata.class,
                "dto.CredentialFieldMetadata ← CredentialFieldMetadata");

        DataSource entity = new DataSource();
        entity.setId("d1");
        entity.setTenantId(7L);
        entity.setKnowledgeBaseId("kb1");
        entity.setName("n");
        entity.setType(DataSourceConstants.CONNECTOR_TYPE_RSS);
        entity.setConfig(cfg.toJSON());
        entity.setSyncSchedule("0 0 * * * *");
        entity.setSyncMode(DataSourceConstants.SYNC_MODE_INCREMENTAL);
        entity.setStatus(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        entity.setConflictStrategy(DataSourceConstants.CONFLICT_STRATEGY_OVERWRITE);
        entity.setSyncDeletions(true);
        entity.setLastSyncAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        entity.setErrorMessage("");
        entity.setSyncLogRetentionDays(30);
        entity.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        entity.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        entity.setTotalItemsSynced(5L);
        entity.setLatestSyncLog(syncLog);
        assertRoundTrips(entity, DataSource.class, "types.DataSource ← DataSource（config 落库）");

        // DTO 是**响应体**：config 的 credentials 按构造被剥离，只有"配没配"暴露
        assertRoundTrips(DataSourceResponse.from(entity), DataSourceResponse.class,
                "dto.DataSourceResponse ← DataSourceResponse（凭据剥离）");
        assertRoundTrips(DataSourceResponse.from(new DataSource()), DataSourceResponse.class,
                "dto.DataSourceResponse ← DataSourceResponse（全空实体）");
    }

    @Test
    void chunkRevisionRoundTrips() {
        // chunk 模块（波 2）：响应体（ListChunkRevisions 的 data 元素），字段序 = Go 声明序。
        // is_enabled 是 false 也要恒输出（Go 无 omitempty）——字段名刻意不取 isEnabled。
        ChunkRevision r = new ChunkRevision();
        r.setId(java.util.UUID.randomUUID().toString());
        r.setTenantId(10002L);
        r.setKnowledgeBaseId(java.util.UUID.randomUUID().toString());
        r.setKnowledgeId(java.util.UUID.randomUUID().toString());
        r.setChunkId(java.util.UUID.randomUUID().toString());
        r.setRevision(0);
        r.setContent("");
        r.setEnabled(false);
        r.setEditorId("user-1");
        r.setEditSource("user");
        r.setEditedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        r.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        assertRoundTrips(r, ChunkRevision.class, "types.ChunkRevision ← ChunkRevision");
    }

    @Test
    void documentChunkMetadataRoundTrips() {
        // chunks.metadata（jsonb）的文档形状：generated_questions 空→省略（slice omitempty）、
        // generated_questions_revision 0→省略（int omitempty）、content_revision 非 nil 的 0 要输出（*int）。
        DocumentChunkMetadata meta = new DocumentChunkMetadata();
        meta.setGeneratedQuestions(java.util.List.of(
                new GeneratedQuestion("q-1", "问题？", 3),
                new GeneratedQuestion("q-2", "另一个问题？", null)));
        meta.setGeneratedQuestionsRevision(3);
        assertRoundTrips(meta, DocumentChunkMetadata.class,
                "types.DocumentChunkMetadata ← DocumentChunkMetadata（含问题）");

        DocumentChunkMetadata empty = new DocumentChunkMetadata();
        assertRoundTrips(empty, DocumentChunkMetadata.class,
                "types.DocumentChunkMetadata ← DocumentChunkMetadata（全空 → {}）");
    }

    @Test
    void knowledgeTaskDtosRoundTrip() {
        // 波 2 第三批的响应体（move/copy 的任务面）。进度对象是响应契约（不落 jsonb）：
        // 字段恒输出（Go 无 omitempty），isTerminal() 派生访问器必须 @JsonIgnore（§7.5 第 2 条）。
        var move = new KnowledgeTaskDtos.KnowledgeMoveProgress(
                "kg_move_10002_1704628851692_a1b2c3d4_kb789", "kb-src", "kb-dst",
                "completed", 100, 3, 3, 0, "Moved 3/3 knowledge items", "", 0, 1789767737L);
        assertRoundTrips(move, KnowledgeTaskDtos.KnowledgeMoveProgress.class,
                "types.KnowledgeMoveProgress ← KnowledgeTaskDtos.KnowledgeMoveProgress");

        var clone = new KnowledgeTaskDtos.KBCloneProgress(
                "kb_clone_10002_1704628851692_a1b2c3d4_kb789", "kb-src", "kb-dst",
                "completed", 100, 4, 4, "Knowledge base clone completed successfully", "", 0,
                1789767745L);
        assertRoundTrips(clone, KnowledgeTaskDtos.KBCloneProgress.class,
                "types.KBCloneProgress ← KnowledgeTaskDtos.KBCloneProgress");

        var mvResp = new KnowledgeTaskDtos.MoveKnowledgeResponse(
                "kg_move_10002_1_a", "kb-src", "kb-dst", 1, "Knowledge move task started");
        assertRoundTrips(mvResp, KnowledgeTaskDtos.MoveKnowledgeResponse.class,
                "handler.MoveKnowledgeResponse ← KnowledgeTaskDtos.MoveKnowledgeResponse");

        var cpResp = new KnowledgeTaskDtos.CopyKnowledgeBaseResponse(
                "kb_clone_10002_1_a", "kb-src", "kb-dst", "Knowledge base copy task started");
        assertRoundTrips(cpResp, KnowledgeTaskDtos.CopyKnowledgeBaseResponse.class,
                "handler.CopyKnowledgeBaseResponse ← KnowledgeTaskDtos.CopyKnowledgeBaseResponse");

        var dupResp = new KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse(
                "kb-src", "kb-dst", "Knowledge base duplicate created", null);
        assertRoundTrips(dupResp, KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse.class,
                "handler.DuplicateKnowledgeBaseResponse ← KnowledgeTaskDtos.DuplicateKnowledgeBaseResponse");
    }

    @Test
    void chunkRoundTrips() {
        // Chunk 起作响应体（chunk 模块波 2）：字段序 = Go struct 声明序。
        // source_content / context_header 是 json:"-"（@JsonIgnore）；is_enabled 恒输出；
        // 三个 json 列空 → null（对照 types.JSON.MarshalJSON 的 len==0 → "null"）。
        Chunk chunk = new Chunk();
        chunk.setId(java.util.UUID.randomUUID().toString());
        chunk.setSeqId(42L);
        chunk.setTenantId(10002L);
        chunk.setKnowledgeId(java.util.UUID.randomUUID().toString());
        chunk.setKnowledgeBaseId(java.util.UUID.randomUUID().toString());
        chunk.setTagId("");
        chunk.setContent("正文");
        chunk.setContentRevision(2);
        chunk.setIndexStatus("ready");
        chunk.setLastEditorId("");
        chunk.setChunkIndex(0);
        chunk.setIsEnabled(false);
        chunk.setFlags(1);
        chunk.setStatus(0);
        chunk.setStartAt(0);
        chunk.setEndAt(2);
        chunk.setPreChunkId("");
        chunk.setNextChunkId("");
        chunk.setChunkType("text");
        chunk.setParentChunkId("");
        chunk.setRelationChunks(null);
        chunk.setIndirectRelationChunks(null);
        chunk.setMetadata(null);
        chunk.setContentHash("");
        chunk.setImageInfo("");
        chunk.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        chunk.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        chunk.setDeletedAt(null);
        assertRoundTrips(chunk, Chunk.class, "types.Chunk ← Chunk（活行：deleted_at=null）");
    }

    @Test
    void faqDtosRoundTrip() {
        // FAQ 模块（波 2 第四批）。FaqChunkMetadata 落 chunks.metadata（jsonb）+其余是
        // 响应契约；omitempty 逐字段 NON_DEFAULT（无 omitempty 的 id/index/error 等恒输出）。
        var meta = new FaqChunkMetadata();
        meta.standardQuestion = "怎么 绑定 手机？";
        meta.similarQuestions = List.of("如何绑定手机");
        meta.negativeQuestions = List.of("怎么解绑手机");
        meta.answers = List.of("进入设置。");
        meta.answerStrategy = "all";
        meta.version = 1;
        meta.source = "faq";
        assertRoundTrips(meta, FaqChunkMetadata.class, "types.FAQChunkMetadata ← FaqChunkMetadata");

        var emptyMeta = new FaqChunkMetadata();
        assertRoundTrips(emptyMeta, FaqChunkMetadata.class,
                "types.FAQChunkMetadata ← FaqChunkMetadata（零值：仅 standard_question 恒输出）");

        var entry = new FaqDtos.FaqEntry(970001L, "chunk-1", "kg-1", "kb-1", 965001L, "热门问题",
                true, true, "怎么绑定手机", List.of("如何绑定"), null, List.of("答案"),
                "all", "question_only",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                0.87, 1, "faq", "如何绑定");
        assertRoundTrips(entry, FaqDtos.FaqEntry.class, "types.FAQEntry ← FaqDtos.FaqEntry");

        // 导出面：id 无 omitempty（0 恒输出，golden 实录）
        var exportEntry = new FaqDtos.FaqExportEntry(0L, "", "问题", null, null, List.of("答案"),
                "all", true, false);
        assertRoundTrips(exportEntry, FaqDtos.FaqExportEntry.class,
                "types.FAQExportEntry ← FaqDtos.FaqExportEntry（id=0 恒输出）");

        var payload = new FaqDtos.FaqEntryPayload(5L, "问题", List.of("相似问"), null,
                List.of("答案"), "random", 965001L, "分类", false, true);
        assertRoundTrips(payload, FaqDtos.FaqEntryPayload.class,
                "types.FAQEntryPayload ← FaqDtos.FaqEntryPayload");

        var upsert = new FaqDtos.FaqBatchUpsertPayload(List.of(payload), "append", "kg-1",
                "faq_import_10002_1_a", true);
        assertRoundTrips(upsert, FaqDtos.FaqBatchUpsertPayload.class,
                "types.FAQBatchUpsertPayload ← FaqDtos.FaqBatchUpsertPayload");

        var search = new FaqDtos.FaqSearchRequest("怎么绑定手机", 0.7, 10,
                List.of(965001L), null, true);
        assertRoundTrips(search, FaqDtos.FaqSearchRequest.class,
                "types.FAQSearchRequest ← FaqDtos.FaqSearchRequest");

        var fieldsBatch = new FaqDtos.FaqEntryFieldsBatchUpdate(
                Map.of(970001L, new FaqDtos.FaqEntryFieldsUpdate(true, false, 965001L)),
                Map.of(965002L, new FaqDtos.FaqEntryFieldsUpdate(null, null, null)),
                List.of(970003L));
        assertRoundTrips(fieldsBatch, FaqDtos.FaqEntryFieldsBatchUpdate.class,
                "types.FAQEntryFieldsBatchUpdate ← FaqDtos.FaqEntryFieldsBatchUpdate");

        var failed = new FaqDtos.FaqFailedEntry(0, "标准问不能为空", "pre_validation", false,
                "分类", "问题", List.of("相似问"), null, List.of("答案"),
                true, false, List.of("被移除的相似问"), null);
        assertRoundTrips(failed, FaqDtos.FaqFailedEntry.class, "types.FAQFailedEntry ← FaqDtos.FaqFailedEntry");

        var success = new FaqDtos.FaqSuccessEntry(0, 970002L, 0, "", "问题");
        assertRoundTrips(success, FaqDtos.FaqSuccessEntry.class,
                "types.FAQSuccessEntry ← FaqDtos.FaqSuccessEntry（tag_id=0/tag_name 空省略）");

        var merge = new FaqDtos.FaqMergeDetail(1, "标准问", true, 2, 0);
        assertRoundTrips(merge, FaqDtos.FaqMergeDetail.class, "types.FAQMergeDetail ← FaqDtos.FaqMergeDetail");

        var progress = new FaqDtos.FaqImportProgress("faq_import_10002_1_a", "kb-1", "kg-1",
                "completed", 100, 3, 3, 1, 1, 0, 0,
                null, "local://10002/exports/x.csv", null, List.of(0, 2), List.of(0),
                0, 1, null, "验证完成 / 上传 3 条", "", 1789771866L, 1789771867L, true,
                "append", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC), "open", 5L);
        assertRoundTrips(progress, FaqDtos.FaqImportProgress.class,
                "types.FAQImportProgress ← FaqDtos.FaqImportProgress（message/error 恒输出）");

        var result = new FaqDtos.FaqImportResult(2, 1, 1, 0, 0, 0, 1, "append",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC), "task", null, "open", 5L);
        assertRoundTrips(result, FaqDtos.FaqImportResult.class,
                "types.FAQImportResult ← FaqDtos.FaqImportResult（last_faq_import_result jsonb）");
    }

    @Test
    void infraConfigRoundTrips() {
        // 波 2 第五批：基础设施配置三组的 jsonb/响应体类型（§7.5 第 3 条）
        var wspParams = new com.ragagent.websearch.domain.WebSearchProviderParams();
        wspParams.setApiKey("sk-1");
        wspParams.setEngineId("eng-1");
        wspParams.setBaseUrl("http://searxng.example.internal:8080");
        wspParams.setProxyUrl("http://proxy.example.internal:1080");
        wspParams.setExtraConfig(new java.util.LinkedHashMap<>(java.util.Map.of(
                "search_engine", "search_std", "content_size", "medium")));
        assertRoundTrips(wspParams, WebSearchProviderParams.class,
                "types.WebSearchProviderParameters ← websearch.domain.WebSearchProviderParams（api_key omitempty）");

        var conn = new ConnectionConfig();
        conn.addr = "http://127.0.0.1:9200";
        conn.username = "elastic";
        conn.password = "pw";
        conn.apiKey = "key";
        conn.insecureSkipVerify = true;
        conn.host = "localhost";
        conn.port = 6334;
        conn.useTls = true;
        conn.grpcAddress = "weaviate:50051";
        conn.scheme = "https";
        conn.database = "weknora";
        conn.useDefaultConnection = true;
        conn.httpPort = 8030;
        conn.version = "7.10.1";
        assertRoundTrips(conn, ConnectionConfig.class,
                "types.ConnectionConfig ← vectorstore.domain.ConnectionConfig（全字段 omitempty + GetEndpoint @JsonIgnore）");

        var idx = new IndexConfig();
        idx.indexName = "weknora";
        idx.numberOfShards = 4;
        idx.numberOfReplicas = 1;
        idx.collectionPrefix = "weknora_embeddings";
        idx.collectionName = "weknora_embeddings";
        idx.shardNumber = 1;
        idx.replicationFactor = 1;
        idx.shardsNum = 1;
        idx.replicaNumber = 1;
        idx.desiredShardCount = 1;
        idx.bucketsNum = 10;
        idx.replicationNum = 1;
        idx.hnswM = 16;
        idx.hnswEfConstruction = 100;
        idx.hnswEfSearch = 100;
        idx.knnEngine = "lucene";
        assertRoundTrips(idx, IndexConfig.class,
                "types.IndexConfig ← vectorstore.domain.IndexConfig（GetIndexNameOrDefault @JsonIgnore）");

        var cfg = new StorageConfig();
        cfg.mode = "remote";
        cfg.endpoint = "minio.example.internal:9000";
        cfg.region = "ap-beijing";
        cfg.accessKeyId = "ak";
        cfg.secretAccessKey = "sk";
        cfg.bucketName = "bucket";
        cfg.pathPrefix = "pp";
        cfg.appId = "1250000";
        cfg.useSsl = true;
        cfg.forcePathStyle = true;
        cfg.useTempBucket = true;
        cfg.tempBucketName = "tmp";
        cfg.tempRegion = "ap-shanghai";
        assertRoundTrips(cfg, StorageConfig.class,
                "types.StorageBackendConfig ← storage.dto.StorageConfig（全字段 omitempty）");
    }

    // ── 波 2 收官批（系统管理端 + 评估） ──────────────────────────────────

    @Test
    void systemSettingRoundTrips() {
        // system_settings 行直接作响应体（裸行，无信封）+ value 落 jsonb。
        // enum/lastModifiedByName 是 gorm:"-"（TableField(exist=false)）+ omitempty。
        var row = new com.ragagent.system.domain.SystemSetting();
        row.setId(1L);
        row.setKey("tenant.max_owned_per_user");
        row.setValue(MAPPER.valueToTree(12));
        row.setValueType("int");
        row.setCategory("tenant");
        row.setDescription("每个非超管用户通过自助创建可拥有的最大空间数。");
        row.setIsSecret(false);
        row.setRequiresRestart(false);
        row.setLastModifiedBy("11111111-2222-3333-4444-555555555701");
        row.setEnumOptions(java.util.List.of("a", "b"));
        row.setLastModifiedByName("javasysadmin");
        assertRoundTrips(row, com.ragagent.system.domain.SystemSetting.class,
                "types.SystemSetting ← system.domain.SystemSetting（value jsonb + enum/name omitempty）");
    }

    @Test
    void evaluationDetailRoundTrips() {
        // EvaluationDetail（task+params）直接作响应体；double 字段挂 GoDoubleSerializer，
        // summary_config.thinking 是 *bool 无 omitempty（null 恒输出）。
        var task = new com.ragagent.evaluation.dto.EvaluationDtos.EvaluationTask();
        task.id = "evaluation_10002_1789790586130_89a7491e_default";
        task.tenantId = 10002L;
        task.datasetId = "default";
        task.status = 0;
        var params = new com.ragagent.evaluation.dto.EvaluationDtos.PipelineParams();
        params.maxRounds = 5;
        params.vectorThreshold = 0.2;
        params.keywordThreshold = 0.3;
        params.embeddingTopK = 30;
        params.rerankTopK = 30;
        params.rerankThreshold = 0.3;
        params.chatModelId = "fake-chat-model-id";
        var summary = new com.ragagent.evaluation.dto.EvaluationDtos.SummaryConfigParams();
        summary.repeatPenalty = 1.0;
        summary.temperature = 0.3;
        summary.maxCompletionTokens = 2048;
        params.summaryConfig = summary;
        var detail = new com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail();
        detail.task = task;
        detail.params = params;
        assertRoundTrips(detail, com.ragagent.evaluation.dto.EvaluationDtos.EvaluationDetail.class,
                "types.EvaluationDetail ← evaluation.dto.EvaluationDetail（metric omitempty + thinking null）");
    }

    // ── 波 2 扫尾批 1（auth 注册族响应体） ─────────────────────────────────

    @Test
    void authRegisterContractsRoundTrip() {
        // UserInfo：/auth/validate 与 /auth/me 的 user 投影——**没有 deleted_at**
        // （与 User 实体序列化的差别）；avatar/tenant_id/preferences 恒输出。
        // 时间字段留空：本工具用的是裸 ObjectMapper（未注册 JSR-310）。
        var prefs = new com.ragagent.auth.domain.UserPreferences();
        prefs.setBrowserSearchInstructions("instr");
        prefs.setLastActiveTenantId(10002L);
        var info = new com.ragagent.auth.dto.UserInfo(
                "u-1", "probe", "probe@weknora.test", "", 10002L,
                true, false, false, prefs, null, null);
        assertRoundTrips(info, com.ragagent.auth.dto.UserInfo.class,
                "types.UserInfo ← auth.dto.UserInfo（无 deleted_at，preferences 恒输出）");

        // RegisterResponse：201 响应体，内嵌的是 **User 实体**（含 "deleted_at":null），
        // 与 UserInfo 投影的差异正是 golden 钉住的点
        var user = new com.ragagent.auth.domain.User();
        user.setId("u-1");
        user.setUsername("reg-probe");
        user.setEmail("reg@weknora.test");
        user.setTenantId(10002L);
        user.setIsActive(true);
        user.setPreferences(new com.ragagent.auth.domain.UserPreferences());
        assertRoundTrips(
                new com.ragagent.auth.dto.RegisterResponse(true, "Registration successful", user),
                com.ragagent.auth.dto.RegisterResponse.class,
                "types.RegisterResponse ← auth.dto.RegisterResponse（user 为完整实体）");

        // InvitationLookupResponse：tenant_name 是 omitempty（NON_EMPTY），两种形态各钉一条
        assertRoundTrips(
                new com.ragagent.auth.dto.InvitationLookupResponse(
                        10002L, "Acme", "viewer", "2026-09-20T14:00:00Z"),
                com.ragagent.auth.dto.InvitationLookupResponse.class,
                "handler.invitationLookupResponse ← InvitationLookupResponse");
        assertRoundTrips(
                new com.ragagent.auth.dto.InvitationLookupResponse(
                        10002L, "", "viewer", "2026-09-20T14:00:00Z"),
                com.ragagent.auth.dto.InvitationLookupResponse.class,
                "handler.invitationLookupResponse ← InvitationLookupResponse（tenant_name 省略）");
    }

    // ── 波 2 扫尾批 2（OIDC 端点响应体） ───────────────────────────────────

    @Test
    void authOidcContractsRoundTrip() {
        // OIDCConfigResponse：provider_display_name 是 omitempty，两形态各钉一条
        assertRoundTrips(
                new com.ragagent.auth.dto.OidcConfigResponse(true, false, "OIDC"),
                com.ragagent.auth.dto.OidcConfigResponse.class,
                "types.OIDCConfigResponse ← OidcConfigResponse");
        assertRoundTrips(
                new com.ragagent.auth.dto.OidcConfigResponse(true, true, ""),
                com.ragagent.auth.dto.OidcConfigResponse.class,
                "types.OIDCConfigResponse ← OidcConfigResponse（display name 省略）");

        // OIDCAuthURLResponse：后三字段 omitempty；nonce 标 json:"-" 不建模
        assertRoundTrips(
                new com.ragagent.auth.dto.OidcAuthUrlResponse(true, "OIDC",
                        "https://idp.example.com/authorize?client_id=c", "st.ate"),
                com.ragagent.auth.dto.OidcAuthUrlResponse.class,
                "types.OIDCAuthURLResponse ← OidcAuthUrlResponse");
        assertRoundTrips(
                new com.ragagent.auth.dto.OidcAuthUrlResponse(false, "", "", ""),
                com.ragagent.auth.dto.OidcAuthUrlResponse.class,
                "types.OIDCAuthURLResponse ← OidcAuthUrlResponse（omitempty 全省略）");
    }

    // ── 波 2 扫尾批 3（租户 KV 配置类型族，落 tenants 表 jsonb 列） ─────────

    @Test
    void tenantKvConfigsRoundTrip() {
        // WebSearchConfig：omitempty 家族（空串/0/false 全省略）；api_key 序列化抑制
        // （write-only：响应与回读都见不到，黑名单保留）
        com.ragagent.auth.domain.tenantconfig.WebSearchConfig ws =
                new com.ragagent.auth.domain.tenantconfig.WebSearchConfig();
        ws.setProvider("tavily");
        ws.setApiKey("ak-secret");
        ws.setMaxResults(5);
        ws.setIncludeDate(true);
        ws.setCompressionMethod("summary");
        ws.setBlacklist(java.util.List.of("bad.com"));
        ws.setProxyUrl("http://proxy.local:8080");
        assertRoundTrips(ws, com.ragagent.auth.domain.tenantconfig.WebSearchConfig.class,
                "types.WebSearchConfig ← WebSearchConfig");

        // ParserEngineConfig：rules 是 List<JsonNode> 透传；布尔三态指针
        com.ragagent.auth.domain.tenantconfig.ParserEngineConfig parser =
                new com.ragagent.auth.domain.tenantconfig.ParserEngineConfig();
        parser.setMineruEndpoint("http://mineru.example.com");
        parser.setMineruApiKey("mk-secret");
        parser.setMineruModel("pipeline");
        parser.setMineruEnableFormula(Boolean.TRUE);
        parser.setMineruEnableTable(Boolean.FALSE);
        parser.setChatParserEngineRules(java.util.List.of(
                MAPPER.createObjectNode().put("engine", "mineru").put("priority", 1)));
        assertRoundTrips(parser, com.ragagent.auth.domain.tenantconfig.ParserEngineConfig.class,
                "types.ParserEngineConfig ← ParserEngineConfig");

        // StorageEngineConfig：8 个 provider 嵌套块（null 块省略）
        com.ragagent.auth.domain.tenantconfig.StorageEngineConfig storage =
                new com.ragagent.auth.domain.tenantconfig.StorageEngineConfig();
        storage.setDefaultProvider("minio");
        com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.MinioEngineConfig minio =
                new com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.MinioEngineConfig();
        minio.setMode("remote");
        minio.setEndpoint("http://minio.example.com");
        minio.setAccessKeyId("AK");
        minio.setSecretAccessKey("SK");
        minio.setBucketName("b");
        minio.setUseSsl(false);
        minio.setPathPrefix("p");
        storage.setMinio(minio);
        assertRoundTrips(storage, com.ragagent.auth.domain.tenantconfig.StorageEngineConfig.class,
                "types.StorageEngineConfig ← StorageEngineConfig");

        // ChatHistoryConfig：三字段全输出形态
        com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig chat =
                new com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig();
        chat.setEnabled(true);
        chat.setEmbeddingModelId("emb-1");
        chat.setKnowledgeBaseId("kb-1");
        assertRoundTrips(chat, com.ragagent.auth.domain.tenantconfig.ChatHistoryConfig.class,
                "types.ChatHistoryConfig ← ChatHistoryConfig");

        // RetrievalConfig：double 走 GoDoubleSerializer（0.5 → 0.5、0 → 0）；
        // rrf_* 零值 NON_DEFAULT 省略
        com.ragagent.auth.domain.tenantconfig.RetrievalConfig ret =
                new com.ragagent.auth.domain.tenantconfig.RetrievalConfig();
        ret.setEmbeddingTopK(20);
        ret.setVectorThreshold(0.5);
        ret.setKeywordThreshold(0.4);
        ret.setRerankTopK(5);
        ret.setRerankThreshold(0.1);
        ret.setRerankModelId("rm-1");
        ret.setRrfK(60);
        ret.setRrfVectorWeight(0.7);
        ret.setRrfKeywordWeight(0.3);
        assertRoundTrips(ret, com.ragagent.auth.domain.tenantconfig.RetrievalConfig.class,
                "types.RetrievalConfig ← RetrievalConfig");
    }

    // ── 波 3 sandbox 子批 1（config jsonb 列 + 响应体双用族） ────────────────

    @Test
    void tenantSandboxConfigRoundTrips() {
        // TenantSandboxConfig 是 tenant_sandbox_configs.config 的 jsonb 载荷 + 响应体。
        // 三类风险各钉一条：
        //  1. 全部字段 omitempty（字符串空/0/false/null 全省略）——零值形态往返幂等；
        //  2. skill_image.built_at 是 Go time.Time（struct）：omitempty 无效 → 恒输出，
        //     零值输出 year-1 字面量（字段默认值持 GO_ZERO + 成对序列化器）；
        //  3. env_vars 是 map → GoMapSerializer 键字母序（往返仍须幂等）。
        var cfg = new com.ragagent.sandbox.domain.TenantSandboxConfig();
        cfg.setSandboxType("cube");
        cfg.setDefaultTimeoutSec(120);
        cfg.setTerminalIdleDisconnectSec(900);
        cfg.setAllowPrivateEndpoints(true);
        cfg.setEnvVars(new java.util.LinkedHashMap<>(java.util.Map.of("TOKEN", "t")));
        var cube = new com.ragagent.sandbox.domain.CubeSandboxConfig();
        cube.setApiUrl("http://127.0.0.1:33000");
        cube.setProxyUrl("http://127.0.0.1:80");
        cube.setSandboxDomain("cube.app");
        cube.setApiKey("sk-live");
        cube.setTemplateId("tpl-1");
        cube.setHttpTimeoutSec(45);
        cube.setCubeSandboxTtlSeconds(1800);
        cube.setDnsServers(java.util.List.of("127.0.0.1", "8.8.8.8"));
        cfg.setCube(cube);
        cfg.setVolumeMount(new com.ragagent.sandbox.domain.VolumeMountConfig());
        var image = new com.ragagent.sandbox.domain.SkillImageConfig();
        image.setSnapshotId("snap-1");
        image.setGeneration(2);
        image.setBuiltAt(java.time.OffsetDateTime.parse("2026-09-19T08:00:00.5+08:00"));
        image.setBaseTemplateId("tpl-1");
        image.setOwnerFingerprint("f".repeat(64));
        cfg.setSkillImage(image);
        cfg.setSkillRollout("new_session");
        assertRoundTrips(cfg, com.ragagent.sandbox.domain.TenantSandboxConfig.class,
                "types.TenantSandboxConfig ← TenantSandboxConfig（全字段 + 内嵌快照）");

        // 全空：省略一切（除 built_at 恒输出 year-1）
        assertRoundTrips(new com.ragagent.sandbox.domain.TenantSandboxConfig(),
                com.ragagent.sandbox.domain.TenantSandboxConfig.class,
                "types.TenantSandboxConfig ← TenantSandboxConfig（全空，built_at 恒输出）");
    }

    @Test
    void sandboxNetworkPolicyRoundTrips() {
        // SandboxNetworkPolicy + CubeEgressRule/CubeHeaderInject/E2BHostRule：
        // name/header/secret/host 无 omitempty（恒输出），其余省略；
        // e2b headers map 挂 GoMapSerializer。
        var policy = new com.ragagent.sandbox.domain.SandboxNetworkPolicy();
        policy.setDenyEgressByDefault(true);
        policy.setAllowOut(java.util.List.of("*.example.com", "10.0.0.0/8"));
        policy.setDenyOut(java.util.List.of("0.0.0.0/0"));

        var inject = new com.ragagent.sandbox.domain.SandboxNetworkPolicy.CubeHeaderInject();
        inject.setHeader("X-Token");
        inject.setSecret("s3cr3t");
        inject.setFormat("${SECRET}");
        var rule = new com.ragagent.sandbox.domain.SandboxNetworkPolicy.CubeEgressRule();
        rule.setName("vendor-api");
        rule.setScheme("https");
        rule.setSni("api.example.com");
        rule.setHost("api.example.com");
        rule.setMethods(java.util.List.of("GET", "POST"));
        rule.setPath("/v1/");
        rule.setDeny(false);
        rule.setAudit("metadata");
        rule.setInject(java.util.List.of(inject));
        policy.setCubeRules(java.util.List.of(rule));

        var hostRule = new com.ragagent.sandbox.domain.SandboxNetworkPolicy.E2BHostRule();
        hostRule.setHost("api.example.com");
        hostRule.setHeaders(new java.util.LinkedHashMap<>(java.util.Map.of("Authorization", "Bearer x")));
        policy.setE2bHostRules(java.util.List.of(hostRule));
        assertRoundTrips(policy, com.ragagent.sandbox.domain.SandboxNetworkPolicy.class,
                "types.SandboxNetworkPolicy ← SandboxNetworkPolicy（全规则形态）");

        // 零值：四个键全省略
        assertRoundTrips(new com.ragagent.sandbox.domain.SandboxNetworkPolicy(),
                com.ragagent.sandbox.domain.SandboxNetworkPolicy.class,
                "types.SandboxNetworkPolicy ← SandboxNetworkPolicy（零值）");

        // 后端专属块（e2b/docker/volume_mount）各钉一条
        var e2b = new com.ragagent.sandbox.domain.E2BSandboxConfig();
        e2b.setApiUrl("https://api.e2b.app");
        e2b.setApiKey("k");
        e2b.setTemplateId("t");
        e2b.setE2bSandboxTtlSeconds(300);
        assertRoundTrips(e2b, com.ragagent.sandbox.domain.E2BSandboxConfig.class,
                "types.E2BSandboxConfig ← E2BSandboxConfig");
        var docker = new com.ragagent.sandbox.domain.DockerSandboxConfig();
        docker.setImage("wechatopenai/weknora-sandbox:main");
        docker.setHost("unix:///var/run/docker.sock");
        docker.setCpuLimit(2);
        docker.setMemoryLimitMb(2048);
        assertRoundTrips(docker, com.ragagent.sandbox.domain.DockerSandboxConfig.class,
                "types.DockerSandboxConfig ← DockerSandboxConfig");
        assertRoundTrips(new com.ragagent.sandbox.domain.VolumeMountConfig(),
                com.ragagent.sandbox.domain.VolumeMountConfig.class,
                "types.VolumeMountConfig ← VolumeMountConfig（enabled 恒输出）");
    }

    // ── 元信息：把「哪些类型已覆盖」变成可读清单 ────────────────────────────

    /**
     * 未接入往返断言的实体清单——新增实体时请补上断言并把名字从这里删掉。
     *
     * 只是把「待办」显式化的文档常量，无断言（跑测试不会失败）。
     */
    static final Set<String> UNCOVERED_ENTITIES = Set.of(
            // MCP：仅经 DTO 组装出站，本身不落 jsonb、不作响应体
            "McpOAuthClient", "McpOAuthToken", "McpMetadata", "McpMetadataSummary",
            // Wiki：查询投影，不经 Jackson 出站
            "WikiPageLite", "WikiIndexEntry",
            // 任务队列：payload 为 JsonNode，非强类型契约
            "TaskPendingOp", "TaskDeadLetter");
}
