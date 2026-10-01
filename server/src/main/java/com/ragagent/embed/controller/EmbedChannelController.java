package com.ragagent.embed.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.embed.EmbedError;
import com.ragagent.embed.EmbedTokens;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.filter.EmbedAuthFilter;
import com.ragagent.embed.service.EmbedChannelService;
import com.ragagent.mcp.controller.AgentToolApprovalController;
import com.ragagent.mcp.controller.McpOAuthController;
import com.ragagent.session.controller.MessageController;
import com.ragagent.session.controller.MessageSuggestionController;
import com.ragagent.session.controller.SessionController;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.SessionRepository;
import com.ragagent.session.service.SessionService;
import com.ragagent.storage.support.StorageUrlContext;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * embed 渠道 HTTP 层（对照 Go internal/handler/embed_channel.go 全文 847 行 +
 * internal/router/routes_agent.go 的管理面/公开面注册）。
 *
 * <p><b>管理面</b>（JWT + RBAC + API-Key manage_channels 门）：POST/GET
 * /agents/:id/embed-channels、GET/PUT/DELETE /embed-channels/:channel_id、
 * rotate-token、preview-session、stats。</p>
 *
 * <p><b>公开面</b>（EmbedAuthFilter 已注入渠道与租户上下文）：exchange、config、
 * suggested-questions、chunks/:chunk_id、sessions(POST)、messages/:sid/load、
 * sessions/:sid/stop、suggestions GET/POST、suggestion-events、events（webhook 中继）、
 * mcp-oauth authorize-url/status、mcp-oauth-resolutions(+cancel)、tool-approvals。
 * 委托面直接调用既有控制器方法（对照 Go 的 handler 委托），确保字节契约同源。</p>
 *
 * <p><b>W5d 收口（2026-09-21）</b>：{@code POST /embed/:cid/knowledge-chat/:sid} 与
 * {@code POST /embed/:cid/agent-chat/:sid}（patchEmbedChatPayload + 委托
 * KnowledgeQaController 的 KnowledgeQA/AgentQA）与 {@code GET /embed/:cid/files}
 * （委托 FileProxyService.serveTenantFiles，与 /files 同 handler 体）已落地。</p>
 *
 * <h2>响应形态</h2>
 * <ul>
 *   <li>管理面响应是 gin.H map → 键<b>字母序</b>（TreeMap 构建）；create 是 201；</li>
 *   <li>公开面 envelope {@code {"data":…,"success":true}}；错误是纯字符串
 *       {@code {"error":"…"}}（webhook 中继/签名/白名单族）或 AppError 信封
 *       （suggestion/MCP 委托面沿用各自控制器的异常）。</li>
 * </ul>
 */
@RestController
public class EmbedChannelController {

static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    final EmbedChannelService service;
    final SessionService sessionService;
    final SessionRepository sessionRepository;
    final MessageController messageController;
    final SessionController sessionController;
    final MessageSuggestionController suggestionController;
    final McpOAuthController mcpOAuthController;
    final AgentToolApprovalController toolApprovalController;
final com.ragagent.session.controller.KnowledgeQaController knowledgeQaController;
final com.ragagent.storage.fileserve.FileProxyService fileProxyService;

    /** 管理面协作者（对照 Go 管理段）。 */
    final EmbedChannelMgmtOps mgmtOps;

    /** 公开面协作者（对照 Go 公开段）。 */
    final EmbedChannelPublicOps publicOps;

    /** 委托协作者（对照 Go W5d/会话/建议/MCP 段）。 */
    final EmbedChannelDelegateOps delegateOps;

    public EmbedChannelController(EmbedChannelService service,
                                  SessionService sessionService,
                                  SessionRepository sessionRepository,
                                  MessageController messageController,
                                  SessionController sessionController,
                                  MessageSuggestionController suggestionController,
                                  McpOAuthController mcpOAuthController,
                                  AgentToolApprovalController toolApprovalController,
                                  com.ragagent.session.controller.KnowledgeQaController knowledgeQaController,
                                  com.ragagent.storage.fileserve.FileProxyService fileProxyService) {
        this.service = service;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.messageController = messageController;
        this.sessionController = sessionController;
        this.suggestionController = suggestionController;
        this.mcpOAuthController = mcpOAuthController;
        this.toolApprovalController = toolApprovalController;
        this.knowledgeQaController = knowledgeQaController;
        this.fileProxyService = fileProxyService;
        this.mgmtOps = new EmbedChannelMgmtOps(this);
        this.publicOps = new EmbedChannelPublicOps(this);
        this.delegateOps = new EmbedChannelDelegateOps(this);
    }

    // ═══════════════════ 请求体（对照 Go embedChannelRequest） ═══════════════════

    record EmbedChannelRequest(
            @JsonProperty("name") String name,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("allowed_origins") JsonNode allowedOrigins,
            @JsonProperty("welcome_message") String welcomeMessage,
            @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute,
            @JsonProperty("rate_limit_per_day") Integer rateLimitPerDay,
            @JsonProperty("primary_color") String primaryColor,
            @JsonProperty("page_title") String pageTitle,
            @JsonProperty("header_title_mode") String headerTitleMode,
            @JsonProperty("show_suggested_questions") Boolean showSuggestedQuestions,
            @JsonProperty("show_thinking") Boolean showThinking,
            @JsonProperty("widget_position") String widgetPosition,
            @JsonProperty("allow_web_search") Boolean allowWebSearch,
            @JsonProperty("allow_file_upload") Boolean allowFileUpload,
            @JsonProperty("default_locale") String defaultLocale,
            @JsonProperty("webhook_url") String webhookUrl,
            @JsonProperty("webhook_secret") String webhookSecret,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("launcher_icon") String launcherIcon) {
    }


    @PostMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        return mgmtOps.create(agentId, rawBody);
    }

    @GetMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> listByAgent(@PathVariable("id") String agentId) {
        return mgmtOps.listByAgent(agentId);
    }

    @GetMapping("/api/v1/embed-channels")
    public ResponseEntity<Map<String, Object>> listAll() {
        return mgmtOps.listAll();
    }

    @GetMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("channel_id") String channelId) {
        return mgmtOps.get(channelId);
    }

    @PutMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("channel_id") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        return mgmtOps.update(channelId, rawBody);
    }

    @DeleteMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("channel_id") String channelId) {
        return mgmtOps.delete(channelId);
    }

    @PostMapping("/api/v1/embed-channels/{channel_id}/rotate-token")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable("channel_id") String channelId) {
        return mgmtOps.rotate(channelId);
    }

    @PostMapping("/api/v1/embed-channels/{channel_id}/preview-session")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable("channel_id") String channelId) {
        return mgmtOps.preview(channelId);
    }

    @GetMapping("/api/v1/embed-channels/{channel_id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("channel_id") String channelId) {
        return mgmtOps.stats(channelId);
    }


    @PostMapping("/api/v1/embed/{channel_id}/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@PathVariable("channel_id") String channelId) {
        return publicOps.exchange(channelId);
    }

    @GetMapping("/api/v1/embed/{channel_id}/config")
    public ResponseEntity<Map<String, Object>> config(@PathVariable("channel_id") String channelId) {
        return publicOps.config(channelId);
    }

    @GetMapping("/api/v1/embed/{channel_id}/suggested-questions")
    public ResponseEntity<Map<String, Object>> suggestedQuestions(
            @PathVariable("channel_id") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        return publicOps.suggestedQuestions(channelId, limit);
    }

    @GetMapping("/api/v1/embed/{channel_id}/chunks/{chunk_id}")
    public ResponseEntity<Map<String, Object>> chunk(@PathVariable("chunk_id") String chunkId) {
        return publicOps.chunk(chunkId);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable("channel_id") String channelId) {
        return publicOps.createSession(channelId);
    }


    @PostMapping("/api/v1/embed/{channel_id}/knowledge-chat/{session_id}")
    public void knowledgeChat(@PathVariable("session_id") String sessionId,
                              @RequestBody(required = false) String rawBody,
                              @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                              jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.knowledgeChat(sessionId, rawBody, resourceUrls, response);
    }

    @PostMapping("/api/v1/embed/{channel_id}/agent-chat/{session_id}")
    public void agentChat(@PathVariable("session_id") String sessionId,
                          @RequestBody(required = false) String rawBody,
                          @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.agentChat(sessionId, rawBody, resourceUrls, response);
    }

    @GetMapping("/api/v1/embed/{channel_id}/files")
    public void embedFiles(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateOps.embedFiles(request, response);
    }

    @GetMapping("/api/v1/embed/{channel_id}/messages/{session_id}/load")
    public ResponseEntity<List<com.ragagent.session.domain.Message>> load(@PathVariable("session_id") String sessionId,
                                                    @RequestParam(name = "limit", required = false) String limit,
                                                    @RequestParam(name = "before_time", required = false) String beforeTime,
                                                    @RequestParam(name = "resource_urls", required = false) String resourceUrls) {
        return delegateOps.load(sessionId, limit, beforeTime, resourceUrls);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("session_id") String sessionId,
                                                    @RequestBody(required = false) String rawBody) {
        return delegateOps.stop(sessionId, rawBody);
    }

    @GetMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/messages/{message_id}/suggestions")
    public ResponseEntity<?> suggestionsGet(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId) {
        return delegateOps.suggestionsGet(sessionId, messageId);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/messages/{message_id}/suggestions")
    public ResponseEntity<?> suggestionsEnsure(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.suggestionsEnsure(sessionId, messageId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/suggestion-events")
    public ResponseEntity<?> suggestionEvents(@PathVariable("session_id") String sessionId,
                                              @RequestBody(required = false) String rawBody) {
        return delegateOps.suggestionEvents(sessionId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable("session_id") String sessionId,
                                                      @RequestBody(required = false) String rawBody) {
        return delegateOps.events(sessionId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-services/{svc_id}/oauth/authorize-url")
    public ResponseEntity<Map<String, Object>> mcpAuthorize(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.mcpAuthorize(sessionId, serviceId, rawBody);
    }

    @GetMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-services/{svc_id}/oauth/status")
    public ResponseEntity<Map<String, Object>> mcpStatus(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId) {
        return delegateOps.mcpStatus(sessionId, serviceId);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}")
    public ResponseEntity<Map<String, Object>> mcpResolve(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.mcpResolve(sessionId, pendingId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}/cancel")
    public ResponseEntity<Map<String, Object>> mcpResolveCancel(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId) {
        return delegateOps.mcpResolveCancel(sessionId, pendingId);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/tool-approvals/{pending_id}")
    public ResponseEntity<?> toolApprovals(@PathVariable("session_id") String sessionId,
                                           @PathVariable("pending_id") String pendingId,
                                           @RequestBody(required = false) String rawBody) {
        return delegateOps.toolApprovals(sessionId, pendingId, rawBody);
    }

    // ═══════════════════ ensureEmbedSession（对照 L650-704） ═══════════════════

    /**
     * 对照 ensureEmbedSession（L650-704）：失败直接抛 {@link PlainErrorException}
     * （全局处理器渲染纯字符串错误信封）；成功时上下文已改写为 embed_session 主体。
     */
void ensureSession(String sessionId) {
        EmbedChannelEntity ch = channel(request0());
        long tenantId = ch.getTenantId() == null ? 0 : ch.getTenantId();
        if (sessionId == null || sessionId.isEmpty()) {
            throw new PlainErrorException(400, "session_id is required");
        }
        Session sess;
        try {
            sess = sessionService.getSessionById(tenantId, sessionId);
        } catch (RuntimeException e) {
            sess = null;
        }
        if (sess == null) {
            throw new PlainErrorException(404, "session not found");
        }
        String marker = EmbedChannelService.embedSessionDescription(ch.getId());
        if (sess.getTenantId() == null || sess.getTenantId().longValue() != tenantId
                || !marker.equals(sess.getDescription())) {
            throw new PlainErrorException(403, "session not allowed for this embed channel");
        }
        String owner = SessionOwnerIds.EMBED_SESSION_PREFIX + tenantId + ":" + ch.getId()
                + ":" + sessionId;
        if (sess.getUserId() == null || sess.getUserId().trim().isEmpty()) {
            try {
                sessionRepository.setOwnerId(tenantId, sessionId, owner);
            } catch (RuntimeException e) {
                // 对照 Go：Warnf 后继续
            }
        }
        String sig = trim(request0().getHeader("X-Embed-Session"));
        if (!EmbedTokens.verifyHandle(ch, sessionId, sig)) {
            throw new PlainErrorException(403, "session signature invalid");
        }
        // X-Embed-Visitor：合法时挂到上下文（对照 ValidateEmbedVisitorID）
        String visitor = trim(request0().getHeader("X-Embed-Visitor"));
        if (!visitor.isEmpty() && !validVisitor(visitor)) {
            throw new PlainErrorException(400, "invalid embed visitor id");
        }
        TenantContext.set(tenantId,
                new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_SESSION,
                        tenantId + ":" + ch.getId() + ":" + sessionId),
                com.ragagent.common.tenant.TenantRole.VIEWER.value(),
                false, "embed-" + ch.getId(), false);
        if (!visitor.isEmpty()) {
            com.ragagent.common.context.TenantContext.setEmbedVisitorId(visitor);
        }
        // 对照 storageurl.WithForcedHandleMode：embed 访客恒收 resource:// 句柄
        StorageUrlContext.force();
    }

    /**
     * 对照 EmbedGet/EnsureMessageSuggestions 的 channel 级 suppressed 分支（L487-492）：
     * 渠道关闭推荐问题 → 200 + gin.H 字母序 {questions, status, suppression_reason}。
     */
ResponseEntity<Object> suppressedIfChannelOff() {
        EmbedChannelEntity ch = channel(request0());
        if (ch == null || !ch.isShowSuggestedQuestions()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("questions", new ArrayList<>());
            data.put("status", "suppressed");
            data.put("suppression_reason", "channel_disabled");
            return ResponseEntity.ok(dataEnvelope(data));
        }
        return null;
    }

    /** 对照 types.ValidateEmbedVisitorID：非空、≤128、无控制字符。 */
    private static boolean validVisitor(String id) {
        if (id.isEmpty() || id.length() > 128) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════ 响应组构件 ═══════════════════

    /**
     * 对照 embedChannelResponse（L798-828）：gin.H = 字母序。publishToken 非空才带键。
     */
    static Map<String, Object> row(EmbedChannelEntity ch, boolean withPublishToken) {
        return row(ch, ch.getPublishToken() == null ? "" : ch.getPublishToken(), withPublishToken);
    }

    static Map<String, Object> row(EmbedChannelEntity ch, String token) {
        return row(ch, token, true);
    }

    static Map<String, Object> row(EmbedChannelEntity ch, String token, boolean includeToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_id", ch.getAgentId());
        m.put("allow_file_upload", ch.isAllowFileUpload());
        m.put("allow_web_search", ch.isAllowWebSearch());
        m.put("allowed_origins", originsJson(EmbedChannelService.allowedOriginsList(ch)));
        m.put("created_at", ch.getCreatedAt());
        m.put("default_locale", ch.getDefaultLocale());
        m.put("enabled", ch.isEnabled());
        m.put("has_webhook_secret", ch.getWebhookSecret() != null && !ch.getWebhookSecret().isEmpty());
        m.put("header_title_mode", EmbedChannelService.normalizeHeaderTitleMode(ch.getHeaderTitleMode()));
        m.put("id", ch.getId());
        m.put("launcher_icon", ch.getLauncherIcon());
        m.put("name", ch.getName());
        m.put("page_title", ch.getPageTitle());
        m.put("primary_color", ch.getPrimaryColor());
        if (includeToken && token != null && !token.isEmpty()) {
            m.put("publish_token", token);
        }
        m.put("rate_limit_per_day", ch.getRateLimitPerDay());
        m.put("rate_limit_per_minute", ch.getRateLimitPerMinute());
        m.put("show_suggested_questions", ch.isShowSuggestedQuestions());
        m.put("show_thinking", ch.isShowThinking());
        m.put("tenant_id", ch.getTenantId());
        m.put("updated_at", ch.getUpdatedAt());
        m.put("webhook_url", ch.getWebhookUrl());
        m.put("welcome_message", ch.getWelcomeMessage());
        m.put("widget_position", ch.getWidgetPosition());
        return m;
    }

    /** 对照 ch.AllowedOriginsList()：nil → JSON null，非 nil → 数组（列表行用）。 */
    private static Object originsJson(List<String> origins) {
        return origins.isEmpty() ? null : origins;
    }

    static List<Map<String, Object>> rows(List<EmbedChannelEntity> list) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (EmbedChannelEntity ch : list) {
            data.add(row(ch, "", false));
        }
        return data;
    }

    /** {"data":…,"success":true}（字母序 data < success）。 */
    static Map<String, Object> dataEnvelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

static Map<String, Object> successEnvelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    static ResponseEntity<Map<String, Object>> plainError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 对照 writeEmbedMgmtError 的分派（纯字符串错误信封）。 */
    static PlainErrorException writeMgmtError(EmbedError e) {
        return switch (e.kind) {
            case CHANNEL_NOT_FOUND -> new PlainErrorException(404, "embed channel not found");
            case BAD_REQUEST_TEXT -> new PlainErrorException(400, e.getMessage());
            case CHANNEL_DISABLED -> new PlainErrorException(403, "embed channel is disabled");
            default -> new PlainErrorException(500, "operation failed");
        };
    }

    // ═══════════════════ 工具 ═══════════════════

    static long currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        return tid == null ? 0L : tid;
    }

    static EmbedChannelEntity channel(jakarta.servlet.http.HttpServletRequest request) {
        Object ch = request.getAttribute(EmbedAuthFilter.CHANNEL_ATTRIBUTE);
        if (!(ch instanceof EmbedChannelEntity entity)) {
            throw BizException.unauthorized("unauthorized");
        }
        return entity;
    }

    /** MockMvc/Servlet 通用取当前请求（对照 gin c）。 */
    static jakarta.servlet.http.HttpServletRequest request0() {
        var attrs = org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }

    /**
     * 对照 c.ShouldBindJSON(&req)：空 body → "EOF"，坏 JSON → Go 措辞（GoJsonBindError）。
     */
    static EmbedChannelRequest bind(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new PlainErrorException(400, "EOF");
        }
        try {
            return MAPPER.readValue(rawBody, EmbedChannelRequest.class);
        } catch (Exception e) {
            throw new PlainErrorException(400,
                    GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /**
     * allowed_origins 的列值。create 路径：缺键（null 节点）按 Go 的 json.Marshal(nil)="null"
     * 处理（但 handler 校验会先拒掉缺键/空数组，实际到不了 service）；存在则原样文本。
     */
    static String allowedOriginsColumn(JsonNode node) {
        return node == null ? "null" : node.toString();
    }

    /** JsonNode → List&lt;String&gt;（校验入口）。非数组/元素非字符串在 Go 是 bind 错误，这里容忍为列表。 */
    static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                out.add(n.asText(""));
            }
        }
        return out;
    }

    static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
