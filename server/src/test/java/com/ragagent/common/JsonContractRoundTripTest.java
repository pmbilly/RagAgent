package com.ragagent.common;

import static com.ragagent.common.JsonRoundTrip.assertRoundTrips;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.apikey.domain.TenantAPIKey;
import com.ragagent.audit.controller.AuditLogListResponse;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.apikey.domain.TenantAPIKeyCreateResponse;
import com.ragagent.apikey.domain.TenantAPIKeyResponse;
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
import com.ragagent.session.domain.MentionedItem;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionLastRequestState;
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
