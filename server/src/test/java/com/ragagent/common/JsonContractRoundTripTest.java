package com.ragagent.common;

import static com.ragagent.common.JsonRoundTrip.assertRoundTrips;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.mcp.domain.McpAdvancedConfig;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpResource;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import com.ragagent.mcp.domain.McpTestResult;
import com.ragagent.mcp.domain.McpTool;
import com.ragagent.mcp.domain.McpToolApproval;
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
