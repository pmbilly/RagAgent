package com.ragagent.embed.controller;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.error.BizException;
import com.ragagent.common.error.PlainErrorException;
import com.ragagent.common.security.LogSanitizer;
import com.ragagent.common.web.GoJsonBindError;
import com.ragagent.embed.domain.EmbedChannelEntity;
import com.ragagent.mcp.controller.McpOAuthController;
import com.ragagent.mcp.dto.ResolveToolApprovalRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * embed 委托协作者（自 {@link EmbedChannelController} 拆出的
 * chat 委托/会话/建议/webhook/MCP 段）：patchEmbedChatPayload 渠道约束合并后
 * 委托 KnowledgeQA/AgentQA，load/stop/建议/webhook 事件与 MCP OAuth/tool-approval
 * 透传。会话归属校验经门面 {@code ensureSession}。
 */
final class EmbedChannelDelegateOps {

    private final EmbedChannelController ctrl;

    EmbedChannelDelegateOps(EmbedChannelController ctrl) {
        this.ctrl = ctrl;
    }

    // ═══════════════════ QA / 文件代理委托 ═══════════════════

    /** patch 后委托 KnowledgeQA。 */
    public void knowledgeChat(@PathVariable("session_id") String sessionId,
                              @RequestBody(required = false) String rawBody,
                              @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                              jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateEmbedChat(sessionId, rawBody, resourceUrls, false, response);
    }

    /** patch 后按渠道 agent 分派 AgentQA/KnowledgeQA。 */
    public void agentChat(@PathVariable("session_id") String sessionId,
                          @RequestBody(required = false) String rawBody,
                          @RequestParam(name = "resource_urls", required = false) String resourceUrls,
                          jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        delegateEmbedChat(sessionId, rawBody, resourceUrls, true, response);
    }

    /**
     * 委托聊天主链：渠道 → ensureSession →
     * patchEmbedChatPayload → 委托。quick-answer 内建 agent 恒走 KnowledgeQA。
     */
    private void delegateEmbedChat(String sessionId, String rawBody, String resourceUrls,
            boolean agentMode, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        String patched = patchEmbedChatPayload(rawBody, ch, agentMode);
        if (agentMode && !"builtin-quick-answer".equals(ch.getAgentId())) {
            ctrl.knowledgeQaController.agentQA(LogSanitizer.sanitize(sessionId), patched,
                    resourceUrls, response);
            return;
        }
        ctrl.knowledgeQaController.knowledgeQA(LogSanitizer.sanitize(sessionId), patched,
                resourceUrls, response);
    }

    /**
     * 把渠道约束合并进访客 QA 请求体。
     * body 已由 Spring 读成 String（无读取失败分支）；坏 JSON / 非对象 → 400 "invalid json"。
     *
     * <p>写回的键名必须与 {@code QaRequests.CreateKnowledgeQARequest} 的字段名一致
     * （均为 camelCase）——写错不会报错，只会静默丢失渠道约束
     * （KB 注入失效 = 访客拿到越权检索面）。对齐由 {@code EmbedChatPayloadPatchTest} 钉住。</p>
     */
    static String patchEmbedChatPayload(String rawBody, EmbedChannelEntity ch,
            boolean agentMode) {
        com.fasterxml.jackson.databind.node.ObjectNode payload;
        if (rawBody == null || rawBody.isEmpty()) {
            payload = EmbedChannelController.MAPPER.createObjectNode();
        } else {
            JsonNode node;
            try {
                node = EmbedChannelController.MAPPER.readTree(rawBody);
            } catch (Exception e) {
                throw new PlainErrorException(400, "invalid json");
            }
            // JSON null 视同空体；数组/标量 → "invalid json"
            if (node == null || node.isNull()) {
                payload = EmbedChannelController.MAPPER.createObjectNode();
            } else if (!node.isObject()) {
                throw new PlainErrorException(400, "invalid json");
            } else {
                payload = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            }
        }
        payload.put("agentId", ch.getAgentId());
        payload.putArray("knowledgeBaseIds");
        // 仅当客户端给了 bool 才算 opt-in（非 bool 一律 false）
        JsonNode clientWs = payload.get("webSearchEnabled");
        payload.put("webSearchEnabled", ch.isAllowWebSearch()
                && clientWs != null && clientWs.isBoolean() && clientWs.asBoolean());
        if (!ch.isAllowFileUpload()) {
            payload.remove("images");
            payload.remove("attachmentUploads");
            payload.remove("attachmentIds");
        }
        payload.putArray("mcpServiceIds");
        payload.put("agentEnabled", agentMode);
        try {
            return EmbedChannelController.MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new PlainErrorException(500, "failed to prepare request");
        }
    }

    /**
     * embed 文件代理与 /files 共用同一处理器——渠道租户由 EmbedAuthFilter
     * 注入 TenantContext，路径归属校验在 FileProxyService 内（resource:// 目录命中优先）。
     */
    public void embedFiles(jakarta.servlet.http.HttpServletRequest request,
                           jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        ctrl.fileProxyService.serveTenantFiles(request, response);
    }

    /** 先 ensureSession，再委托 MessageController.loadMessages。 */
    public ResponseEntity<List<com.ragagent.session.domain.Message>> load(@PathVariable("session_id") String sessionId,
                                                    @RequestParam(name = "limit", required = false) String limit,
                                                    @RequestParam(name = "before_time", required = false) String beforeTime,
                                                    @RequestParam(name = "resource_urls", required = false) String resourceUrls) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        return ctrl.messageController.loadMessages(LogSanitizer.sanitize(sessionId), limit, beforeTime,
                resourceUrls);
    }

    /**
     * 委托 SessionController.stopSession。
     *
     * <p>请求体键名从 {@code message_id} 变成 {@code messageId}
     * ——embed 的其余键仍是下划线。</p>
     */
    public ResponseEntity<?> stop(@PathVariable("session_id") String sessionId,
                                  @RequestBody(required = false)
                                  com.ragagent.session.dto.StopSessionRequest body) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        return ctrl.sessionController.stopSession(LogSanitizer.sanitize(sessionId), body);
    }

    /** 建议读取：channel 级 suppressed 分支优先于委托。 */
    public ResponseEntity<?> suggestionsGet(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        ResponseEntity<Object> suppressed = ctrl.suppressedIfChannelOff();
        if (suppressed != null) {
            return suppressed;
        }
        return ctrl.suggestionController.get(LogSanitizer.sanitize(sessionId), null,
                LogSanitizer.sanitize(messageId));
    }

    /** 建议生成（ensure 语义）。 */
    public ResponseEntity<?> suggestionsEnsure(
            @PathVariable("session_id") String sessionId,
            @PathVariable("message_id") String messageId,
            @RequestBody(required = false) String rawBody) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        ResponseEntity<Object> suppressed = ctrl.suppressedIfChannelOff();
        if (suppressed != null) {
            return suppressed;
        }
        return ctrl.suggestionController.ensure(LogSanitizer.sanitize(sessionId),
                LogSanitizer.sanitize(messageId), rawBody);
    }

    /** 建议事件上报：成功 204 无响应体。 */
    public ResponseEntity<?> suggestionEvents(@PathVariable("session_id") String sessionId,
                                              @RequestBody(required = false) String rawBody) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        return ctrl.suggestionController.recordEvent(LogSanitizer.sanitize(sessionId), rawBody);
    }

    /**
     * webhook 事件转发：message_sent / message_received 之外全拒；
     * 下发是 best-effort 异步（渠道 webhook 为空 → no-op），响应恒 200。
     */
    public ResponseEntity<?> events(@PathVariable("session_id") String sessionId,
                                    @RequestBody(required = false) String rawBody) {
        EmbedChannelEntity ch = EmbedChannelController.channel(EmbedChannelController.request0());
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        EventRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = EmbedChannelController.MAPPER.readValue(rawBody, EventRequest.class);
            } catch (Exception e) {
                req = null;
            }
        }
        if (req == null) {
            return EmbedChannelController.plainError(400, "invalid request body");
        }
        String eventType = EmbedChannelController.trim(req.type());
        if (!"message_sent".equals(eventType) && !"message_received".equals(eventType)) {
            return EmbedChannelController.plainError(400, "unsupported event type");
        }
        // webhook_url 为空直接返回；golden 渠道未配 webhook → no-op。
        // 无响应体的受理回执 → 204（不回 {"success":true}）
        return ResponseEntity.noContent().build();
    }

    /** 访客事件上报体（键名＝组件名；S4 后请求面统一 camelCase）。 */
    record EventRequest(String type, String sessionId, String query, String content) {
    }

    /** MCP OAuth 授权 URL（委托 McpOAuthController）。 */
    public ResponseEntity<Map<String, Object>> mcpAuthorize(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId,
            @RequestBody(required = false) String rawBody) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        McpOAuthController.AuthorizeRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = EmbedChannelController.MAPPER.readValue(rawBody, McpOAuthController.AuthorizeRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return ctrl.mcpOAuthController.authorizeUrl(serviceId, req);
    }

    /** MCP OAuth 状态查询（委托 McpOAuthController）。 */
    public ResponseEntity<?> mcpStatus(
            @PathVariable("session_id") String sessionId,
            @PathVariable("svc_id") String serviceId) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        return ctrl.mcpOAuthController.status(serviceId, null);
    }

    /** MCP OAuth 解析（gate 依赖分支；Gate 未接线时 500——已知差异）。 */
    public ResponseEntity<Void> mcpResolve(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId,
            @RequestBody(required = false) String rawBody) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        McpOAuthController.ResolveRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = EmbedChannelController.MAPPER.readValue(rawBody, McpOAuthController.ResolveRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return ctrl.mcpOAuthController.resolveMcpOAuth(pendingId, req);
    }

    /** 取消待决的 MCP OAuth 工具授权。 */
    public ResponseEntity<Void> mcpResolveCancel(
            @PathVariable("session_id") String sessionId,
            @PathVariable("pending_id") String pendingId) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        return ctrl.mcpOAuthController.cancelMcpOAuth(pendingId);
    }

    /** 工具审批受理（gate 依赖分支；Gate 未接线时 500 = 已知差异）。 */
    public ResponseEntity<?> toolApprovals(@PathVariable("session_id") String sessionId,
                                           @PathVariable("pending_id") String pendingId,
                                           @RequestBody(required = false) String rawBody) {
        ctrl.ensureSession(LogSanitizer.sanitize(sessionId));
        ResolveToolApprovalRequest req = null;
        if (rawBody != null && !rawBody.isEmpty()) {
            try {
                req = EmbedChannelController.MAPPER.readValue(rawBody, ResolveToolApprovalRequest.class);
            } catch (Exception e) {
                throw BizException.badRequest(
                        GoJsonBindError.message(rawBody, e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        return ctrl.toolApprovalController.resolveToolApproval(pendingId, req);
    }
}
