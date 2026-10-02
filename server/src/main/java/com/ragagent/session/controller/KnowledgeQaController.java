package com.ragagent.session.controller;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ragagent.common.error.BizException;
import com.ragagent.common.retrieval.SearchResult;
import com.ragagent.agent.domain.AgentStep;
import com.ragagent.session.domain.Message;
import com.ragagent.session.dto.QaRequests.CreateKnowledgeQARequest;
import com.ragagent.session.dto.QaRequests.SearchKnowledgeRequest;
import com.ragagent.session.service.AgentResolver;
import com.ragagent.session.service.MessageService;
import com.ragagent.session.service.MessageSuggestionService;
import com.ragagent.session.service.QaSupport;
import com.ragagent.session.service.QaSupport.QaMode;
import com.ragagent.session.service.QaSupport.QaRequestContext;
import com.ragagent.session.service.SessionAgentQaService;
import com.ragagent.session.service.SessionKnowledgeQaService;
import com.ragagent.session.service.SessionService;
import com.ragagent.session.service.TemporaryDocumentService;
import com.ragagent.session.sse.StreamEventEmitter;
import com.ragagent.stream.StreamManager;

import jakarta.servlet.http.HttpServletResponse;


/**
 * chat 三入口（对照 Go internal/handler/session/qa.go 全文 + stream.go 的
 * handleAgentEventsForSSE + quick_answer_timeline.go + helpers.go 的 handler 侧）。
 *
 * <h2>三个端点</h2>
 * <ul>
 *   <li>POST /api/v1/knowledge-chat/{session_id} — KnowledgeQA（RAG/纯聊天管线）</li>
 *   <li>POST /api/v1/agent-chat/{session_id} — AgentQA（agent 引擎）</li>
 *   <li>POST /api/v1/knowledge-search — SearchKnowledge（无 LLM 总结检索）</li>
 * </ul>
 *
 * <h2>关键时序（Go 逐条对照，SSE 时序最高危）</h2>
 * <ol>
 *   <li>agent 模式先 rejectIfOtherAgentRunLive（409）→ Emit(agent.query)；</li>
 *   <li>persistTurnMessages 先建 user/assistant 行；</li>
 *   <li>setupSSEStream：SetLiveRun 在 SSE 头<b>之前</b>（409/503 必须还能改状态码）；
 *       agent_query 事件写流；stop 处理器 + 独立 stop watcher + AgentStreamBridge 订阅；</li>
 *   <li>异步执行 QA 服务（虚拟线程），主线程 handleAgentEventsForSSE 100ms 轮询
 *       StreamManager 推帧，complete 后补 completion 事件；</li>
 *   <li>agent 模式 defer：completeAssistantMessage → follow-up 交接 → ClearLiveRun。</li>
 * </ol>
 *
 * <h2>已备案差异</h2>
 * <ul>
 *   <li>共享 agent 解析（W5α2 已收口）：resolveAgent 共享优先、source==0 才回落 own；
 *       GetSharedAgentForTenant 的 ApplyBuiltinAgentLocalization 只覆盖
 *       name/description/avatar（QA 消费 config/tenant，不进字节契约），随 agent.management 装配层
 *       统一补齐；access.WithSharedAgent 的 KB grant 机制（检索授权收窄）随检索面
 *       专项收口——Java 检索租户已取 agentRow.tenantId（等价执行范围）。</li>
 *   <li>图片上传/附件的存储写入与 VLM 分析：saveImageAttachments 的对象存储写入
 *       在 dev（本地盘）与 Go 行为一致；VLM 分析 emit-only 形态保留。</li>
 *   <li>临时附件的 ResolveForPrompt 内容选择（等待/跳过的事件形态保留，内容解析
 *       seam 随附件管线收口）。</li>
 * </ul>
 */
@RestController
public class KnowledgeQaController {


    private final SessionService sessionService;

    /** 执行/落库簇（§14.9c 刀 6b）。 */
    private final QaTurnExecutor executor;

    /** 租户服务（A3-3 接线；原 @Autowired 字段，改构造注入保持可选语义）。 */
    private final com.ragagent.auth.service.TenantService tenantService;

    /** 附件解析簇（§14.9c 刀 6a）。 */
    private final QaAttachmentResolver attachmentResolver;

    /** SSE 编排簇（§14.9c 刀 5）。 */
    private final QaSseOrchestrator sseOrchestrator;

    /** 收尾簇（§14.9c 刀 7）。 */
    private final QaTurnFinalizer turnFinalizer;

    /** 请求解析主体（§14.9c 刀 4b）。 */
    private final QaRequestParser qaRequestParser;
    private final MessageService messageService;
    private final StreamManager streamManager;
    private final SessionKnowledgeQaService knowledgeQaService;
    private final SessionAgentQaService agentQaService;
    private final MessageSuggestionService suggestionService;
    private final TemporaryDocumentService temporaryDocuments;
    private final com.ragagent.session.service.SteerRunCoordinator steerCoordinator;
    private final StreamEventEmitter emitter;
    private final com.ragagent.session.sse.SseFrameWriter sseFrameWriter;
    private final com.ragagent.storage.support.FileService fileService;
    private final com.ragagent.storage.support.StorageBackendResolver storageBackendResolver;
    private final com.ragagent.memory.service.MemoryExtractionService memoryExtraction;

    public KnowledgeQaController(SessionService sessionService,
            MessageService messageService,
            StreamManager streamManager,
            SessionKnowledgeQaService knowledgeQaService,
            SessionAgentQaService agentQaService,
            com.ragagent.session.service.MessageSuggestionService suggestionService,
            TemporaryDocumentService temporaryDocuments,
            com.ragagent.session.service.SteerRunCoordinator steerCoordinator,
            StreamEventEmitter emitter,
            com.ragagent.session.sse.SseFrameWriter sseFrameWriter,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.storage.support.FileService> fileService,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.storage.support.StorageBackendResolver> storageBackendResolver,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.memory.service.MemoryExtractionService> memoryExtraction,
            org.springframework.beans.factory.ObjectProvider<com.ragagent.auth.service.TenantService> tenantServiceProvider) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.streamManager = streamManager;
        this.knowledgeQaService = knowledgeQaService;
        this.agentQaService = agentQaService;
        this.suggestionService = suggestionService;
        this.temporaryDocuments = temporaryDocuments;
        this.steerCoordinator = steerCoordinator;
        this.emitter = emitter;
        this.sseFrameWriter = sseFrameWriter;
        // 两个端口按 ObjectProvider 取（A3-3 起 StorageBackendResolver 有生产实现）；
        // 缺 bean 时 Rewriter 按 Go 的 nil 分支降级（handle 模式同形）
        this.fileService = fileService.getIfAvailable();
        this.storageBackendResolver = storageBackendResolver.getIfAvailable();
        this.memoryExtraction = memoryExtraction.getIfAvailable();
            this.qaRequestParser = new QaRequestParser(sessionService, temporaryDocuments, this.fileService, this.storageBackendResolver);
        this.turnFinalizer = new QaTurnFinalizer(this.sessionService, this.messageService, this.suggestionService, this.temporaryDocuments, this.memoryExtraction);
        this.sseOrchestrator = new QaSseOrchestrator(this.streamManager, this.emitter, this.sessionService, this.messageService, this.sseFrameWriter, this.turnFinalizer);
        this.attachmentResolver = new QaAttachmentResolver(this.messageService, this.temporaryDocuments, this.turnFinalizer);
        this.tenantService = tenantServiceProvider.getIfAvailable();
        this.executor = new QaTurnExecutor(this.messageService, this.streamManager, this.knowledgeQaService,
                this.agentQaService, this.steerCoordinator, this.sseOrchestrator, this.turnFinalizer,
                this.attachmentResolver, this.tenantService);
}

    // ── 端点（qa.go L790-964） ───────────────────────────────────────────────

    @PostMapping("/api/v1/knowledge-chat/{session_id}")
    public void knowledgeQA(@PathVariable("session_id") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = com.ragagent.storage.support.Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "KnowledgeQA", agentResolverField, currentTenant());
        executor.executeQA(parsed.reqCtx(), QaMode.NORMAL, !request.disableTitle, response);
    }

    @PostMapping("/api/v1/agent-chat/{session_id}")
    public void agentQA(@PathVariable("session_id") String rawSessionId,
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = com.ragagent.storage.support.Mode.QUERY_PARAM, required = false) String resourceUrls,
            HttpServletResponse response) throws IOException {
        CreateKnowledgeQARequest request = QaRequestBinder.bindQaRequest(rawBody);
        ParsedRequest parsed = qaRequestParser.parseQARequest(rawSessionId, request, resourceUrls, "AgentQA", agentResolverField, currentTenant());
        QaRequestContext reqCtx = parsed.reqCtx();

        // agent 模式判定：customAgent.agent_mode > request.agent_enabled（Go L929-936）
        boolean agentModeEnabled = request.agentEnabled;
        if (reqCtx.agentConfig != null) {
            agentModeEnabled = SessionKnowledgeQaService.isAgentMode(reqCtx.agentConfig);
        }

        if (agentModeEnabled && reqCtx.agentConfig == null) {
            throw BizException.badRequest("agent_id is required when agent mode is enabled");
        }

        if (agentModeEnabled) {
            executor.executeQA(reqCtx, QaMode.AGENT, true, response);
        } else {
            executor.executeQA(reqCtx, QaMode.NORMAL, !request.disableTitle, response);
        }
    }

    @PostMapping("/api/v1/knowledge-search")
    public List<SearchResult> searchKnowledge(@RequestBody(required = false) String rawBody) {
        SearchKnowledgeRequest request = QaRequestBinder.bindSearchRequest(rawBody);
        if (request.query.isEmpty()) {
            // Go 的手动分支被 binding:required 拦截（不可达），保留对应物
            throw BizException.badRequest("Query content cannot be empty");
        }

        // Merge single knowledge_base_id into knowledge_base_ids（Go L819-832）
        List<String> knowledgeBaseIds = new ArrayList<>(request.knowledgeBaseIds());
        if (!request.knowledgeBaseId.isEmpty() && !knowledgeBaseIds.contains(request.knowledgeBaseId)) {
            knowledgeBaseIds.add(request.knowledgeBaseId);
        }

        List<QaSupport.TagScope> mentionScopes = QaSupport.tagScopesFromMentionedItems(request.mentionedItems());
        List<String> requestTagIds = QaSupport.dedupRequestStrings(request.tagIds());
        String tagError = QaSupport.validateUnscopedTagIds(
                QaSupport.orphanTagIdsForScope(requestTagIds, mentionScopes), knowledgeBaseIds);
        if (tagError != null) {
            throw BizException.badRequest(tagError);
        }
        List<QaSupport.TagScope> tagScopes = QaSupport.mergeTagScopesFromRequestIds(
                mentionScopes, requestTagIds, knowledgeBaseIds);

        if (knowledgeBaseIds.isEmpty() && request.knowledgeIds().isEmpty() && tagScopes.isEmpty()) {
            throw BizException.badRequest(
                    "At least one knowledge_base_id, knowledge_base_ids, knowledge_ids, or scoped tag must be provided");
        }
        com.ragagent.auth.apikey.domain.TenantAPIKeyScope.authorizeKnowledgeTargets(
                knowledgeBaseIds, request.knowledgeIds());

        List<SearchResult> searchResults = knowledgeQaService.searchKnowledge(
                knowledgeBaseIds, request.knowledgeIds(), tagScopes, request.query);

        // 裸列表（无 {success,data} 信封）：检索结果直出。
        // 引用形式（resource_urls）在检索面不带存储引用——Go 走 CopyReferences；
        // handle 模式为透传（public 模式的直链生成经 provider 级文件服务，A3-3 起已接线）。
        return searchResults;
    }

    // ── ShouldBindJSON 对应物（Go binding:required 文案逐字对齐） ─────────────


    // ── parseQARequest（qa.go L126-429） ─────────────────────────────────────

    record ParsedRequest(QaRequestContext reqCtx, CreateKnowledgeQARequest request) {}


    /** 共享/自有 agent 解析（Go qa.go 的 resolveAgent；与附件上传入口共用同一组件）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private AgentResolver agentResolverField;

    /**
     * 读者租户实体（A3-3 接线）——供 Rewriter 解析"引用不带 provider scheme 时的租户默认
     * provider"。此前恒传 null，等价于 Go 在 ctx 无租户时的降级（引用一律保留成 handle）；
     * 现在按 TenantContext 的 id 取实体，与 {@code SystemController} /
     * {@code HybridSearchService} 同一写法。
     */
    private com.ragagent.auth.domain.Tenant currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        try {
            return tid == null || tid <= 0 || tenantService == null
                    ? null : tenantService.getTenantById(tid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<String> stringListOf(com.fasterxml.jackson.databind.JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }


    // ── executeQA（qa.go L1069-1324） ────────────────────────────────────────


    // 曾有的 errorEventText（剥 BizException 取 appError().message()）已删除：
    // 其前提被线上 A/B 推翻（W5γ5.12），现统一走 BizException.wireText，理由见上面的调用点注释。


    // ── persistTurnMessages / rollback（qa.go L978-1047） ────────────────────


    // ── setupSSEStream（qa.go L659-775） ─────────────────────────────────────


    // ── handleAgentEventsForSSE（stream.go L330-474） ─────────────────────────


    // ── quick answer timeline（quick_answer_timeline.go 全文） ────────────────


    static AgentStep ensureQuickAnswerStep(Message msg) {
        if (msg.getAgentSteps() == null || msg.getAgentSteps().isEmpty()) {
            AgentStep step = new AgentStep();
            step.setIteration(0);
            step.setTimestamp(OffsetDateTime.now());
            step.setToolCalls(new ArrayList<>());
            List<AgentStep> steps = new ArrayList<>();
            steps.add(step);
            msg.setAgentSteps(steps);
        }
        return msg.getAgentSteps().get(0);
    }


    static void appendQuickAnswerReasoning(Message msg, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        AgentStep step = ensureQuickAnswerStep(msg);
        step.setReasoningContent(QaSupport.orEmpty(step.getReasoningContent()) + content);
    }

    // ── 附件 / 完成 / 状态（qa.go L1417-1768） ───────────────────────────────


    /** 对照 secutils.GetMaxFileSize 的 dev 缺省（100MB 上传闸门同形）。 */
    static long maxFileBytes() {
        return 100L * 1024 * 1024;
    }

    static final class Base64Support {
        static byte[] decode(String data) {
            String payload = data == null ? "" : data;
            int comma = payload.indexOf(',');
            if (payload.startsWith("data:") && comma > 0) {
                payload = payload.substring(comma + 1);
            }
            return java.util.Base64.getDecoder().decode(payload);
        }
    }
}
