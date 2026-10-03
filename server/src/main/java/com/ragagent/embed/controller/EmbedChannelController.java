package com.ragagent.embed.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * embed 渠道 HTTP 层（管理面/公开面路由）。
 *
 * <p><b>管理面</b>（JWT + RBAC + API-Key manage_channels 门）：POST/GET
 * /agents/:id/embed-channels、GET/PUT/DELETE /embed-channels/:channel_id、
 * rotate-token、preview-session、stats。</p>
 *
 * <p><b>公开面</b>（EmbedAuthFilter 已注入渠道与租户上下文）：exchange、config、
 * suggested-questions、chunks/:chunk_id、sessions(POST)、messages/:sid/load、
 * sessions/:sid/stop、suggestions GET/POST、suggestion-events、events（webhook 中继）、
 * mcp-oauth authorize-url/status、mcp-oauth-resolutions(+cancel)、tool-approvals。
 * 委托面直接调用既有控制器方法，确保字节契约同源。</p>
 *
 * <p><b>W5d 收口（2026-09-21）</b>：{@code POST /embed/:cid/knowledge-chat/:sid} 与
 * {@code POST /embed/:cid/agent-chat/:sid}（patchEmbedChatPayload + 委托
 * KnowledgeQaController 的 KnowledgeQA/AgentQA）与 {@code GET /embed/:cid/files}
 * （委托 FileProxyService.serveTenantFiles，与 /files 同 handler 体）已落地。</p>
 *
 * <h2>响应形态</h2>
 * <ul>
 *   <li>管理面响应键<b>字母序</b>（TreeMap 构建）；create 是 201；</li>
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

    /** 管理面协作者。 */
    final EmbedChannelMgmtOps mgmtOps;

    /** 公开面协作者。 */
    final EmbedChannelPublicOps publicOps;

    /** 委托协作者（chat 委托/会话/建议/MCP 段）。 */
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

    // ═══════════════════ 请求体 ═══════════════════

    record EmbedChannelRequest( String name, Boolean enabled, JsonNode allowedOrigins, String welcomeMessage, Integer rateLimitPerMinute, Integer rateLimitPerDay, String primaryColor, String pageTitle, String headerTitleMode, Boolean showSuggestedQuestions, Boolean showThinking, String widgetPosition, Boolean allowWebSearch, Boolean allowFileUpload, String defaultLocale, String webhookUrl, String webhookSecret, String agentId, String launcherIcon) {
    }


    @PostMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        return mgmtOps.create(agentId, rawBody);
    }

    @GetMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<List<Map<String, Object>>> listByAgent(@PathVariable("id") String agentId) {
        return mgmtOps.listByAgent(agentId);
    }

    @GetMapping("/api/v1/embed-channels")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
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
    public ResponseEntity<Void> delete(@PathVariable("channel_id") String channelId) {
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
    public ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> config(@PathVariable("channel_id") String channelId) {
        return publicOps.config(channelId);
    }

    @GetMapping("/api/v1/embed/{channel_id}/suggested-questions")
    public ResponseEntity<Map<String, Object>> suggestedQuestions(
            @PathVariable("channel_id") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        return publicOps.suggestedQuestions(channelId, limit);
    }

    @GetMapping("/api/v1/embed/{channel_id}/chunks/{chunk_id}")
    public ResponseEntity<?> chunk(@PathVariable("chunk_id") String chunkId) {
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
    public ResponseEntity<?> stop(@PathVariable("session_id") String sessionId,
                                  @RequestBody(required = false)
                                  com.ragagent.session.dto.StopSessionRequest body) {
        return delegateOps.stop(sessionId, body);
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
    public ResponseEntity<?> events(@PathVariable("session_id") String sessionId,
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
    public ResponseEntity<?> mcpStatus(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId) {
        return delegateOps.mcpStatus(sessionId, serviceId);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}")
    public ResponseEntity<Void> mcpResolve(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId,
            @RequestBody(required = false) String rawBody) {
        return delegateOps.mcpResolve(sessionId, pendingId, rawBody);
    }

    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}/cancel")
    public ResponseEntity<Void> mcpResolveCancel(
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

    // ═══════════════════ ensureSession ═══════════════════

    /**
     * 会话门：失败直接抛 {@link PlainErrorException}
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
                // 失败仅记 warn 后继续
            }
        }
        String sig = trim(request0().getHeader("X-Embed-Session"));
        if (!EmbedTokens.verifyHandle(ch, sessionId, sig)) {
            throw new PlainErrorException(403, "session signature invalid");
        }
        // X-Embed-Visitor：合法时挂到上下文
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
        // embed 访客恒收 resource:// 句柄
        StorageUrlContext.force();
    }

    /**
     * 渠道关闭推荐问题 → 200 裸对象 {@code {questions, status, suppressionReason}}：
     * 与委托路径（{@code MessageSuggestionController} 返回裸 {@code MessageSuggestionSet}）同形，
     * 键名取该实体的字段名（不包 data/success 信封）。
     */
    ResponseEntity<Object> suppressedIfChannelOff() {
        EmbedChannelEntity ch = channel(request0());
        if (ch == null || !ch.isShowSuggestedQuestions()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("questions", new ArrayList<>());
            data.put("status", "suppressed");
            data.put("suppressionReason", "channel_disabled");
            return ResponseEntity.ok(data);
        }
        return null;
    }

    /** 访客 ID 校验：非空、≤128、无控制字符。 */
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
     * 渠道行视图：键字母序。publishToken 非空才带键。
     */
    static Map<String, Object> row(EmbedChannelEntity ch, boolean withPublishToken) {
        return row(ch, ch.getPublishToken() == null ? "" : ch.getPublishToken(), withPublishToken);
    }

    /**
     * 渠道行视图（键名＝实体字段名、无 {@code {data,success}} 信封）。
     *
     * <p>唯一的**条件键**是 {@code publishToken}：管理详情/创建/轮换带它、列表行不带——
     * 这是**授权边界**（列表里泄漏 publish token 等于把渠道会话签发权发给任何读列表的人），
     * 不是"有时出现有时消失"的数据条件键，故刻意保留。</p>
     */
    static Map<String, Object> row(EmbedChannelEntity ch, String token) {
        return row(ch, token, true);
    }

    static Map<String, Object> row(EmbedChannelEntity ch, String token, boolean includeToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agentId", ch.getAgentId());
        m.put("allowFileUpload", ch.isAllowFileUpload());
        m.put("allowWebSearch", ch.isAllowWebSearch());
        m.put("allowedOrigins", originsJson(EmbedChannelService.allowedOriginsList(ch)));
        m.put("createdAt", ch.getCreatedAt());
        m.put("defaultLocale", ch.getDefaultLocale());
        m.put("enabled", ch.isEnabled());
        m.put("hasWebhookSecret", ch.getWebhookSecret() != null && !ch.getWebhookSecret().isEmpty());
        m.put("headerTitleMode", EmbedChannelService.normalizeHeaderTitleMode(ch.getHeaderTitleMode()));
        m.put("id", ch.getId());
        m.put("launcherIcon", ch.getLauncherIcon());
        m.put("name", ch.getName());
        m.put("pageTitle", ch.getPageTitle());
        m.put("primaryColor", ch.getPrimaryColor());
        if (includeToken && token != null && !token.isEmpty()) {
            m.put("publishToken", token);
        }
        m.put("rateLimitPerDay", ch.getRateLimitPerDay());
        m.put("rateLimitPerMinute", ch.getRateLimitPerMinute());
        m.put("showSuggestedQuestions", ch.isShowSuggestedQuestions());
        m.put("showThinking", ch.isShowThinking());
        m.put("tenantId", ch.getTenantId());
        m.put("updatedAt", ch.getUpdatedAt());
        m.put("webhookUrl", ch.getWebhookUrl());
        m.put("welcomeMessage", ch.getWelcomeMessage());
        m.put("widgetPosition", ch.getWidgetPosition());
        return m;
    }

    /** allowed_origins 列值：空 → JSON null，非空 → 数组（列表行用）。 */
    private static Object originsJson(List<String> origins) {
        return origins.isEmpty() ? null : origins;
    }

    /** 列表行（裸数组；不带 publish token）。 */
    static List<Map<String, Object>> rows(List<EmbedChannelEntity> list) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (EmbedChannelEntity ch : list) {
            data.add(row(ch, "", false));
        }
        return data;
    }

    static ResponseEntity<Map<String, Object>> plainError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 管理面错误的分派（纯字符串错误信封）。 */
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

    /** 取当前 HTTP 请求（MockMvc/Servlet 通用）。 */
    static jakarta.servlet.http.HttpServletRequest request0() {
        var attrs = org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }

    /**
     * 请求体绑定：空 body → 400 "EOF"，坏 JSON → 解析器原文措辞（{@link GoJsonBindError}）。
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
     * allowed_origins 的列值。create 路径：缺键（null 节点）落库为字符串 "null"
     * （但 handler 校验会先拒掉缺键/空数组，实际到不了 service）；存在则原样文本。
     */
    static String allowedOriginsColumn(JsonNode node) {
        return node == null ? "null" : node.toString();
    }

    /** JsonNode → List&lt;String&gt;（校验入口）。非数组 → 空列表，非字符串元素按空串。 */
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
