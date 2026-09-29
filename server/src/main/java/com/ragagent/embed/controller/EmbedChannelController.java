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
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.embed.EmbedError;
import com.ragagent.embed.EmbedTokens;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.embed.filter.EmbedAuthFilter;
import com.ragagent.embed.service.EmbedChannelService;
import com.ragagent.embed.service.EmbedChannelService.UpdateCommand;
import com.ragagent.knowledge.domain.Chunk;
import com.ragagent.mcp.controller.AgentToolApprovalController;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import com.ragagent.mcp.controller.McpOAuthController;
import com.ragagent.session.controller.MessageController;
import com.ragagent.session.controller.MessageSuggestionController;
import com.ragagent.session.controller.SessionController;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.SessionRepository;
import com.ragagent.session.service.SessionService;
import com.ragagent.storageurl.StorageUrlContext;

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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final EmbedChannelService service;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final MessageController messageController;
    private final SessionController sessionController;
    private final MessageSuggestionController suggestionController;
    private final McpOAuthController mcpOAuthController;
    private final AgentToolApprovalController toolApprovalController;
    private final com.ragagent.session.controller.KnowledgeQaController knowledgeQaController;
    private final com.ragagent.storage.fileserve.FileProxyService fileProxyService;

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

    // ═══════════════════ 管理面 ═══════════════════

    /** 对照 CreateEmbedChannel：201 + gin.H（字母序）。 */
    @PostMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> create(@PathVariable("id") String agentId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelRequest req = bind(rawBody);
        try {
            EmbedChannelService.validateAllowedOrigins(stringList(req.allowedOrigins()));
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
        EmbedChannelEntity input = new EmbedChannelEntity();
        input.setName(orEmpty(req.name()));
        input.setEnabled(Boolean.TRUE.equals(req.enabled()));
        input.setAllowedOrigins(allowedOriginsColumn(req.allowedOrigins()));
        input.setWelcomeMessage(orEmpty(req.welcomeMessage()));
        input.setRateLimitPerMinute(req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute());
        input.setRateLimitPerDay(req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay());
        input.setPrimaryColor(orEmpty(req.primaryColor()));
        input.setPageTitle(orEmpty(req.pageTitle()));
        input.setHeaderTitleMode(orEmpty(req.headerTitleMode()));
        input.setShowSuggestedQuestions(!Boolean.FALSE.equals(req.showSuggestedQuestions()));
        input.setShowThinking(Boolean.TRUE.equals(req.showThinking()));
        input.setWidgetPosition(orEmpty(req.widgetPosition()));
        input.setAllowWebSearch(Boolean.TRUE.equals(req.allowWebSearch()));
        input.setAllowFileUpload(Boolean.TRUE.equals(req.allowFileUpload()));
        input.setDefaultLocale(EmbedChannelService.normalizeDefaultLocale(orEmpty(req.defaultLocale())));
        input.setLauncherIcon(orEmpty(req.launcherIcon()));
        try {
            EmbedChannelEntity ch = service.create(currentTenant(), LogSanitizer.sanitize(agentId), input);
            return ResponseEntity.status(201).body(dataEnvelope(row(ch, true)));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 ListEmbedChannels。 */
    @GetMapping("/api/v1/agents/{id}/embed-channels")
    public ResponseEntity<Map<String, Object>> listByAgent(@PathVariable("id") String agentId) {
        try {
            List<EmbedChannelEntity> rows =
                    service.listByAgent(currentTenant(), LogSanitizer.sanitize(agentId));
            return ResponseEntity.ok(dataEnvelope(rows(rows)));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 ListAllEmbedChannels（跨 agent，publish token 永不出现在列表里）。 */
    @GetMapping("/api/v1/embed-channels")
    public ResponseEntity<Map<String, Object>> listAll() {
        try {
            return ResponseEntity.ok(dataEnvelope(rows(service.listByTenant(currentTenant()))));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 GetEmbedChannel：管理详情**含** publish token。 */
    @GetMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("channel_id") String channelId) {
        try {
            EmbedChannelEntity ch = service.getOwnedChannel(currentTenant(), trim(channelId));
            return ResponseEntity.ok(dataEnvelope(row(ch, true)));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 UpdateEmbedChannel：200 + gin.H（不含 publish token）。 */
    @PutMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable("channel_id") String channelId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelRequest req = bind(rawBody);
        try {
            if (req.allowedOrigins() != null) {
                EmbedChannelService.validateAllowedOrigins(stringList(req.allowedOrigins()));
            }
            if (req.webhookUrl() != null) {
                EmbedChannelService.validateWebhookUrl(req.webhookUrl());
            }
            if (req.launcherIcon() != null) {
                EmbedChannelService.validateLauncherIcon(req.launcherIcon().trim());
            }
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
        UpdateCommand cmd = new UpdateCommand();
        cmd.name = orEmpty(req.name());
        cmd.welcomeMessage = req.welcomeMessage();
        cmd.primaryColor = req.primaryColor();
        cmd.pageTitle = req.pageTitle();
        cmd.headerTitleMode = req.headerTitleMode();
        cmd.widgetPosition = req.widgetPosition();
        cmd.agentId = req.agentId();
        cmd.enabled = req.enabled();
        cmd.showSuggested = req.showSuggestedQuestions();
        cmd.showThinking = req.showThinking();
        cmd.allowWebSearch = req.allowWebSearch();
        cmd.allowFileUpload = req.allowFileUpload();
        cmd.defaultLocale = req.defaultLocale();
        cmd.webhookUrl = req.webhookUrl();
        cmd.webhookSecret = req.webhookSecret();
        cmd.launcherIcon = req.launcherIcon();
        cmd.rateLimitPerMinute = req.rateLimitPerMinute() == null ? 0 : req.rateLimitPerMinute();
        cmd.rateLimitPerDay = req.rateLimitPerDay() == null ? 0 : req.rateLimitPerDay();
        // ⚠️ golden 钉死：缺键 = json.Marshal(nil) = "null" 整列覆写（allowlist 清空）
        cmd.allowedOriginsColumn = req.allowedOrigins() == null
                ? "null" : req.allowedOrigins().toString();
        try {
            EmbedChannelEntity ch = service.update(currentTenant(), trim(channelId), cmd);
            return ResponseEntity.ok(dataEnvelope(row(ch, false)));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 DeleteEmbedChannel：{"success":true}。 */
    @DeleteMapping("/api/v1/embed-channels/{channel_id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("channel_id") String channelId) {
        try {
            service.delete(currentTenant(), trim(channelId));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** 对照 RotateEmbedToken：200 + 含新 token 的行。 */
    @PostMapping("/api/v1/embed-channels/{channel_id}/rotate-token")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable("channel_id") String channelId) {
        try {
            var result = service.rotateToken(currentTenant(), trim(channelId));
            return ResponseEntity.ok(dataEnvelope(row(result.channel(), result.token())));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
    }

    /** 对照 IssuePreviewSession：禁用渠道 → 403 "embed channel is disabled"（专用分支）。 */
    @PostMapping("/api/v1/embed-channels/{channel_id}/preview-session")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable("channel_id") String channelId) {
        EmbedChannelService.IssueResult result;
        try {
            result = service.issuePreviewSession(currentTenant(), trim(channelId));
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.CHANNEL_DISABLED) {
                return plainError(403, "embed channel is disabled");
            }
            throw writeMgmtError(e);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expires_in", result.expiresIn());
        data.put("session_token", result.token());
        return ResponseEntity.ok(dataEnvelope(data));
    }

    /** 对照 GetEmbedChannelStats：{session_count:N}。 */
    @GetMapping("/api/v1/embed-channels/{channel_id}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("channel_id") String channelId) {
        try {
            service.getOwnedChannel(currentTenant(), trim(channelId));
        } catch (EmbedError e) {
            throw writeMgmtError(e);
        }
        long total = service.countEmbedSessions(currentTenant(), trim(channelId), sessionRepository);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_count", total);
        return ResponseEntity.ok(dataEnvelope(data));
    }

    // ═══════════════════ 公开面（EmbedAuthFilter 已跑） ═══════════════════

    /** 对照 ExchangeEmbedSession：只有 publish token 能换 session token。 */
    @PostMapping("/api/v1/embed/{channel_id}/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = channel(request0());
        String auth = trim(request0().getHeader("Authorization"));
        boolean publishToken = auth.startsWith("Embed ")
                && !EmbedTokens.isSessionToken(auth.substring("Embed ".length()));
        if (!publishToken) {
            return plainError(403, "publish token required");
        }
        EmbedChannelService.IssueResult result;
        try {
            result = service.issueSessionToken(ch.getId());
        } catch (EmbedError e) {
            if (e.kind == EmbedError.Kind.SESSION_UNAVAILABLE) {
                return plainError(503, "session tokens unavailable");
            }
            return plainError(500, "failed to issue session token");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expires_in", result.expiresIn());
        data.put("session_token", result.token());
        return ResponseEntity.ok(dataEnvelope(data));
    }

    /** 对照 GetEmbedConfig。 */
    @GetMapping("/api/v1/embed/{channel_id}/config")
    public ResponseEntity<Map<String, Object>> config(@PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = channel(request0());
        return ResponseEntity.ok(dataEnvelope(service.publicConfig(ch)));
    }

    /** 对照 GetEmbedSuggestedQuestions。 */
    @GetMapping("/api/v1/embed/{channel_id}/suggested-questions")
    public ResponseEntity<Map<String, Object>> suggestedQuestions(
            @PathVariable("channel_id") String channelId,
            @RequestParam(name = "limit", required = false) String limit) {
        EmbedChannelEntity ch = channel(request0());
        if (!ch.isShowSuggestedQuestions()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("questions", new ArrayList<>());
            return ResponseEntity.ok(dataEnvelope(data));
        }
        int limitInt = 0;
        if (limit != null && !limit.isEmpty()) {
            try {
                int n = Integer.parseInt(limit);
                if (n > 0) {
                    limitInt = Math.min(n, 12);
                }
            } catch (NumberFormatException ignored) {
                // Go：解析失败按"未指定"处理
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode questions;
        try {
            questions = service.suggestedQuestions(ch, limitInt);
        } catch (RuntimeException e) {
            return plainError(500, "failed to load suggested questions");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questions", questions == null ? new ArrayList<>() : questions);
        return ResponseEntity.ok(dataEnvelope(data));
    }

    /** 对照 GetEmbedChunk。 */
    @GetMapping("/api/v1/embed/{channel_id}/chunks/{chunk_id}")
    public ResponseEntity<Map<String, Object>> chunk(@PathVariable("chunk_id") String chunkId) {
        EmbedChannelEntity ch = channel(request0());
        String cid = LogSanitizer.sanitize(chunkId);
        if (cid.isEmpty()) {
            return plainError(400, "chunk_id is required");
        }
        try {
            Chunk chunk = service.embedChunk(ch, cid);
            return ResponseEntity.ok(dataEnvelope(chunk));
        } catch (EmbedChannelService.ChunkNotFoundError e) {
            return plainError(404, "chunk not found");
        } catch (EmbedChannelService.ChunkForbiddenError e) {
            return plainError(403, "chunk not accessible");
        }
    }

    /** 对照 CreateEmbedSession：201 {id, sig}。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable("channel_id") String channelId) {
        EmbedChannelEntity ch = channel(request0());
        long tenantId = currentTenant();
        Session created;
        try {
            created = sessionService.createSession(
                    EmbedChannelService.newEmbedSession(tenantId, ch.getId()));
        } catch (RuntimeException e) {
            return plainError(500, "failed to create session");
        }
        String owner = SessionOwnerIds.EMBED_SESSION_PREFIX + tenantId + ":" + ch.getId()
                + ":" + created.getId();
        try {
            sessionRepository.setOwnerId(tenantId, created.getId(), owner);
            created.setUserId(owner);
        } catch (RuntimeException e) {
            // 对照 Go：Warnf 后继续
        }
        String sig = EmbedTokens.signHandle(ch, created.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", created.getId());
        data.put("sig", sig);
        return ResponseEntity.status(201).body(dataEnvelope(data));
    }

    // ═══════════════════ QA / 文件代理委托（W5d 收口） ═══════════════════

    /** 对照 EmbedKnowledgeChat（L460-462）：patch 后委托 KnowledgeQA。 */
    @PostMapping("/api/v1/embed/{channel_id}/knowledge-chat/{session_id}")
    public void knowledgeChat(@PathVariable("session_id") String sessionId,
                              @RequestBody(required = false) String rawBody,
                              @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                              jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateEmbedChat(sessionId, rawBody, resourceUrls, false, response);
    }

    /** 对照 EmbedAgentChat（L464-466）：patch 后按渠道 agent 分派 AgentQA/KnowledgeQA。 */
    @PostMapping("/api/v1/embed/{channel_id}/agent-chat/{session_id}")
    public void agentChat(@PathVariable("session_id") String sessionId,
                          @RequestBody(required = false) String rawBody,
                          @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateEmbedChat(sessionId, rawBody, resourceUrls, true, response);
    }

    /**
     * 对照 delegateEmbedChat（L620-648）：渠道 → ensureEmbedSession →
     * patchEmbedChatPayload → 委托。quick-answer 内建 agent 恒走 KnowledgeQA。
     */
    private void delegateEmbedChat(String sessionId, String rawBody, String resourceUrls,
            boolean agentMode, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        EmbedChannelEntity ch = channel(request0());
        ensureSession(LogSanitizer.sanitize(sessionId));
        String patched = patchEmbedChatPayload(rawBody, ch, agentMode);
        if (agentMode && !"builtin-quick-answer".equals(ch.getAgentId())) {
            knowledgeQaController.agentQA(LogSanitizer.sanitize(sessionId), patched,
                    resourceUrls, response);
            return;
        }
        knowledgeQaController.knowledgeQA(LogSanitizer.sanitize(sessionId), patched,
                resourceUrls, response);
    }

    /**
     * 对照 patchEmbedChatPayload（L712-750）：把渠道约束合并进访客 QA 请求体。
     * 「invalid request body」（Go 的 io.ReadAll 失败）在 Java 不可达——body 已由
     * Spring 读成 String；坏 JSON / 非对象 → 400 "invalid json"。
     */
    private static String patchEmbedChatPayload(String rawBody, EmbedChannelEntity ch,
            boolean agentMode) {
        com.fasterxml.jackson.databind.node.ObjectNode payload;
        if (rawBody == null || rawBody.isEmpty()) {
            payload = MAPPER.createObjectNode();
        } else {
            JsonNode node;
            try {
                node = MAPPER.readTree(rawBody);
            } catch (Exception e) {
                throw new PlainErrorException(400, "invalid json");
            }
            // Go 的 json.Unmarshal("null", &map) 得 nil map 无错误 → 视同空体；
            // 数组/标量是 unmarshal 类型错误 → "invalid json"
            if (node == null || node.isNull()) {
                payload = MAPPER.createObjectNode();
            } else if (!node.isObject()) {
                throw new PlainErrorException(400, "invalid json");
            } else {
                payload = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            }
        }
        payload.put("agent_id", ch.getAgentId());
        payload.putArray("knowledge_base_ids");
        // Go：仅当客户端给了 bool 才算 opt-in（非 bool 一律 false）
        JsonNode clientWs = payload.get("web_search_enabled");
        payload.put("web_search_enabled", ch.isAllowWebSearch()
                && clientWs != null && clientWs.isBoolean() && clientWs.asBoolean());
        if (!ch.isAllowFileUpload()) {
            payload.remove("images");
            payload.remove("attachment_uploads");
            payload.remove("attachment_ids");
        }
        payload.putArray("mcp_service_ids");
        payload.put("agent_enabled", agentMode);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new PlainErrorException(500, "failed to prepare request");
        }
    }

    /**
     * 对照 routes_agent.go L255：embed 文件代理与 /files 共用同一 handler 体
     * （newFileServeHandler）——渠道租户由 EmbedAuthFilter 注入 TenantContext，
     * 路径归属校验在 FileProxyService 内（resource:// 目录命中优先）。
     */
    @GetMapping("/api/v1/embed/{channel_id}/files")
    public void embedFiles(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        fileProxyService.serveTenantFiles(request, response);
    }

    /** 对照 EmbedLoadMessages：先 ensureEmbedSession，再委托 MessageController.LoadMessages。 */
    @GetMapping("/api/v1/embed/{channel_id}/messages/{session_id}/load")
    public ResponseEntity<List<com.ragagent.session.domain.Message>> load(@PathVariable("session_id") String sessionId,
                                                    @RequestParam(name = "limit", required = false) String limit,
                                                    @RequestParam(name = "before_time", required = false) String beforeTime,
                                                    @RequestParam(name = "resource_urls", required = false) String resourceUrls) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        return messageController.loadMessages(LogSanitizer.sanitize(sessionId), limit, beforeTime,
                resourceUrls);
    }

    /** 对照 EmbedStopSession：委托 SessionController.StopSession。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("session_id") String sessionId,
                                                    @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        return sessionController.stopSession(LogSanitizer.sanitize(sessionId), rawBody);
    }

    /** 对照 EmbedGetMessageSuggestions：channel 级 suppressed 分支优先于委托。 */
    @GetMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/messages/{message_id}/suggestions")
    public ResponseEntity<?> suggestionsGet(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        ResponseEntity<Object> suppressed = suppressedIfChannelOff();
        if (suppressed != null) {
            return suppressed;
        }
        return suggestionController.get(LogSanitizer.sanitize(sessionId), null,
                LogSanitizer.sanitize(messageId));
    }

    /** 对照 EmbedEnsureMessageSuggestions。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/messages/{message_id}/suggestions")
    public ResponseEntity<?> suggestionsEnsure(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId,
            @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        ResponseEntity<Object> suppressed = suppressedIfChannelOff();
        if (suppressed != null) {
            return suppressed;
        }
        return suggestionController.ensure(LogSanitizer.sanitize(sessionId),
                LogSanitizer.sanitize(messageId), rawBody);
    }

    /** 对照 EmbedRecordSuggestionEvent：成功 204 无响应体。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/suggestion-events")
    public ResponseEntity<?> suggestionEvents(@PathVariable("session_id") String sessionId,
                                              @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        return suggestionController.recordEvent(LogSanitizer.sanitize(sessionId), rawBody);
    }

    /**
     * 对照 EmbedRelayWebhookEvent：message_sent / message_received 之外全拒；
     * DispatchEmbedWebhook 是 best-effort 异步（渠道 webhook 为空 → no-op），响应恒 200。
     */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable("session_id") String sessionId,
                                                      @RequestBody(required = false) String rawBody) {
        EmbedChannelEntity ch = channel(request0());
        ensureSession(LogSanitizer.sanitize(sessionId));
        EventRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = MAPPER.readValue(rawBody, EventRequest.class);
            } catch (Exception e) {
                req = null;
            }
        }
        if (req == null) {
            return plainError(400, "invalid request body");
        }
        String eventType = trim(req.type());
        if (!"message_sent".equals(eventType) && !"message_received".equals(eventType)) {
            return plainError(400, "unsupported event type");
        }
        // DispatchEmbedWebhook：webhook_url 为空直接返回；golden 渠道未配 webhook → no-op。
        return ResponseEntity.ok(successEnvelope());
    }

    record EventRequest(@JsonProperty("type") String type,
                        @JsonProperty("session_id") String sessionId,
                        @JsonProperty("query") String query,
                        @JsonProperty("content") String content) {
    }

    /** 对照 EmbedMCPOAuthAuthorizeURL（委托 McpOAuthController.AuthorizeURL）。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-services/{svc_id}/oauth/authorize-url")
    public ResponseEntity<Map<String, Object>> mcpAuthorize(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId,
            @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        McpOAuthController.AuthorizeRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = MAPPER.readValue(rawBody, McpOAuthController.AuthorizeRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return mcpOAuthController.authorizeUrl(serviceId, req);
    }

    /** 对照 EmbedMCPOAuthStatus（委托 McpOAuthController.Status）。 */
    @GetMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-services/{svc_id}/oauth/status")
    public ResponseEntity<Map<String, Object>> mcpStatus(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        return mcpOAuthController.status(serviceId, null);
    }

    /** 对照 EmbedResolveMCPOAuth（gate 依赖分支；Gate 未接线时 500，与 Go dev 装配差 = 已知差异）。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}")
    public ResponseEntity<Map<String, Object>> mcpResolve(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId,
            @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        McpOAuthController.ResolveRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = MAPPER.readValue(rawBody, McpOAuthController.ResolveRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return mcpOAuthController.resolveMcpOAuth(pendingId, req);
    }

    /** 对照 EmbedCancelMCPOAuth。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/mcp-oauth-resolutions/{pending_id}/cancel")
    public ResponseEntity<Map<String, Object>> mcpResolveCancel(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        return mcpOAuthController.cancelMcpOAuth(pendingId);
    }

    /** 对照 EmbedResolveToolApproval（gate 依赖分支；Gate 未接线时 500 = 已知差异）。 */
    @PostMapping("/api/v1/embed/{channel_id}/sessions/{session_id}/tool-approvals/{pending_id}")
    public ResponseEntity<?> toolApprovals(@PathVariable("session_id") String sessionId,
                                           @PathVariable("pending_id") String pendingId,
                                           @RequestBody(required = false) String rawBody) {
        ensureSession(LogSanitizer.sanitize(sessionId));
        ResolveToolApprovalRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = MAPPER.readValue(rawBody, ResolveToolApprovalRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return toolApprovalController.resolveToolApproval(pendingId, req);
    }

    // ═══════════════════ ensureEmbedSession（对照 L650-704） ═══════════════════

    /**
     * 对照 ensureEmbedSession（L650-704）：失败直接抛 {@link PlainErrorException}
     * （全局处理器渲染纯字符串错误信封）；成功时上下文已改写为 embed_session 主体。
     */
    private void ensureSession(String sessionId) {
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
                com.ragagent.auth.domain.TenantRole.VIEWER.value(),
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
    private ResponseEntity<Object> suppressedIfChannelOff() {
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
    private static Map<String, Object> row(EmbedChannelEntity ch, boolean withPublishToken) {
        return row(ch, ch.getPublishToken() == null ? "" : ch.getPublishToken(), withPublishToken);
    }

    private static Map<String, Object> row(EmbedChannelEntity ch, String token) {
        return row(ch, token, true);
    }

    private static Map<String, Object> row(EmbedChannelEntity ch, String token, boolean includeToken) {
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

    private static List<Map<String, Object>> rows(List<EmbedChannelEntity> list) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (EmbedChannelEntity ch : list) {
            data.add(row(ch, "", false));
        }
        return data;
    }

    /** {"data":…,"success":true}（字母序 data < success）。 */
    private static Map<String, Object> dataEnvelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("success", true);
        return body;
    }

    private static Map<String, Object> successEnvelope() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> plainError(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 对照 writeEmbedMgmtError 的分派（纯字符串错误信封）。 */
    private static PlainErrorException writeMgmtError(EmbedError e) {
        return switch (e.kind) {
            case CHANNEL_NOT_FOUND -> new PlainErrorException(404, "embed channel not found");
            case BAD_REQUEST_TEXT -> new PlainErrorException(400, e.getMessage());
            case CHANNEL_DISABLED -> new PlainErrorException(403, "embed channel is disabled");
            default -> new PlainErrorException(500, "operation failed");
        };
    }

    // ═══════════════════ 工具 ═══════════════════

    private static long currentTenant() {
        Long tid = com.ragagent.common.context.TenantContext.currentTenantId();
        return tid == null ? 0L : tid;
    }

    private static EmbedChannelEntity channel(jakarta.servlet.http.HttpServletRequest request) {
        Object ch = request.getAttribute(EmbedAuthFilter.CHANNEL_ATTRIBUTE);
        if (!(ch instanceof EmbedChannelEntity entity)) {
            throw BizException.unauthorized("unauthorized");
        }
        return entity;
    }

    /** MockMvc/Servlet 通用取当前请求（对照 gin c）。 */
    private static jakarta.servlet.http.HttpServletRequest request0() {
        var attrs = org.springframework.web.context.request.RequestContextHolder
                .currentRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }

    /**
     * 对照 c.ShouldBindJSON(&req)：空 body → "EOF"，坏 JSON → Go 措辞（GoJsonBindError）。
     */
    private static EmbedChannelRequest bind(String rawBody) {
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
    private static String allowedOriginsColumn(JsonNode node) {
        return node == null ? "null" : node.toString();
    }

    /** JsonNode → List&lt;String&gt;（校验入口）。非数组/元素非字符串在 Go 是 bind 错误，这里容忍为列表。 */
    private static List<String> stringList(JsonNode node) {
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

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
