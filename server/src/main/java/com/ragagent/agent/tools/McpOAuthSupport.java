package com.ragagent.agent.tools;

import java.time.Duration;
import java.time.Instant;

import com.ragagent.common.approval.Cancellation;
import com.ragagent.common.approval.Decision;
import com.ragagent.common.approval.OAuthPendingRequest;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.MCPOAuthRequiredData;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.oauth.OAuthReauthorizationRequiredException;
import com.ragagent.mcp.protocol.McpAuthorizationRequiredException;
import com.ragagent.mcp.protocol.McpOAuthRequiredException;

/**
 * MCP OAuth 的会话挂载与重试。
 *
 * <p>MCPOAuthSession 携带 chat/session 元数据，让 MCP connect 与工具注册能在会话内
 * 暂停等 OAuth。null = 不弹提示。等待<b>总是</b>有界——用户既不授权也不跳过时，
 * 被阻塞的线程不能泄漏（值 ≤0 时回落 gate 缺省超时）。</p>
 *
 * <p><b>显式参数</b>：等待所需的 tenant/user/requestID 身份由调用方
 * （工具，引擎内嵌）显式传入，成功重试直接回到原调用点。</p>
 */
public final class McpOAuthSupport {

    /** 工具执行的缺省超时。 */
    static final Duration DEFAULT_MCP_TOOL_EXEC_TIMEOUT = Duration.ofSeconds(60);

    private McpOAuthSupport() {
    }

    /**
     * 会话内 OAuth 等待的超时：来自 agent 的用户配置（秒）。
     * ≤0 返回 0，告诉 gate 回落到它配置的缺省超时。
     */
    public static Duration oauthWaitTimeout(McpOAuthSession sess) {
        if (sess == null || sess.authWaitTimeoutSeconds() <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(sess.authWaitTimeoutSeconds());
    }

    /**
     * 携带 chat/session 元数据的 OAuth 会话。
     * approvalCancellation 是无每操作超时的取消源；execTimeout 是授权成功后重试的上限。
     */
    public record McpOAuthSession(
            EventBus eventBus,
            String sessionId,
            String assistantMessageId,
            String userId,
            String requestId,
            ToolCancellation approvalCancellation,
            Duration execTimeout,
            int authWaitTimeoutSeconds) {

        /** 设置等待超时（安全作用于 null 会话——见静态重载）。 */
        public McpOAuthSession withAuthWaitTimeout(int seconds) {
            return new McpOAuthSession(eventBus, sessionId, assistantMessageId, userId, requestId,
                    approvalCancellation, execTimeout, seconds);
        }
    }

    /** 从执行元数据取 OAuth 会话；meta 无 EventBus 时返回 null。 */
    public static McpOAuthSession oauthSessionFromToolExec(ToolExecContext meta) {
        if (meta == null || meta.eventBus() == null) {
            return null;
        }
        ToolCancellation approvalCtx = meta.approvalCancellation();
        Duration execTimeout = Duration.ofMillis(meta.execTimeoutMillis());
        if (meta.execTimeoutMillis() <= 0) {
            execTimeout = DEFAULT_MCP_TOOL_EXEC_TIMEOUT;
        }
        return new McpOAuthSession(meta.eventBus(), meta.sessionId(), meta.assistantMessageId(),
                meta.userId(), meta.requestId(), approvalCtx, execTimeout, 0);
    }

    /**
     * OAuth 等待门。Gate.requestOAuthAndWait 的签名与本接口
     * 一致，装配层传 {@code gate::requestOAuthAndWait} 即可适配（既有 Gate 不改）。
     */
    @FunctionalInterface
    public interface OAuthWaiter {
        Decision requestOAuthAndWait(Cancellation ctx, OAuthPendingRequest req);
    }

    /** 一次等待所需的调用方身份（回落值 + 非交互旗标）。 */
    public record CallerIdentity(long tenantId, String userId, String requestId, boolean nonInteractive) {
    }

    /**
     * 等待会话内 OAuth 授权的钩子（ok 布尔语义）：
     * 返回 true = 授权完成，调用方应关闭旧连接并重建；false = 继续带着原始错误失败。
     */
    public static boolean waitForMcpOauthAuthorization(
            OAuthWaiter waiter,
            McpOAuthSession sess,
            McpService service,
            String mcpToolName,
            String toolCallId,
            CallerIdentity caller,
            Throwable connectErr) {
        if (sess == null || service == null || sess.eventBus() == null
                || service.getAuthConfig() == null || !service.getAuthConfig().isOAuth()
                || !isAuthorizationRequired(connectErr)) {
            return false;
        }
        if (waiter == null) {
            return false;
        }

        String userId = caller.userId();
        if (userId.isEmpty()) {
            userId = "";
        }
        String requestId = sess.requestId();
        if (requestId.isEmpty()) {
            requestId = caller.requestId();
        }

        // 非交互通道（如 IM bot）没有能点"授权"并调 resolve 端点的活客户端，
        // 阻塞等 OAuth 只会把 agent 挂到超时。发一条一次性提示让通道转达用户，
        // 然后不带工具继续（非交互模式）。
        if (caller.nonInteractive()) {
            emitMcpOauthRequiredNotice(sess, service, mcpToolName, toolCallId,
                    caller.tenantId(), requestId);
            return false;
        }

        Decision decision;
        try {
            decision = waiter.requestOAuthAndWait(Cancellation.none(), OAuthPendingRequest.builder()
                    .tenantId(caller.tenantId())
                    .userId(userId)
                    .sessionId(sess.sessionId())
                    .assistantMessageId(sess.assistantMessageId())
                    .requestId(requestId)
                    .eventBus(ApprovalBridge.toEventBus(sess.eventBus()))
                    .serviceId(service.getId())
                    .serviceName(service.getName())
                    .mcpToolName(mcpToolName)
                    .toolCallId(toolCallId)
                    .waitTimeout(oauthWaitTimeout(sess))
                    .build());
        } catch (Exception e) {
            return false;
        }
        if (decision == null || !decision.approved()) {
            return false;
        }
        return true;
    }

    /**
     * 连接 MCP 服务；需要 OAuth 时挂起等会话内提示后重试一次
     * （getOrCreateMCPClientWithOAuthRetry 语义）。失败抛运行时异常。
     */
    public static com.ragagent.mcp.protocol.McpClient getOrCreateMcpClientWithOAuthRetry(
            com.ragagent.mcp.protocol.McpClientManager manager,
            McpService service,
            OAuthWaiter waiter,
            McpOAuthSession oauthSess,
            String mcpToolName,
            String toolCallId,
            CallerIdentity caller) {
        com.ragagent.mcp.protocol.McpClient client;
        try {
            return manager.getOrCreateClient(com.ragagent.mcp.protocol.McpContext.none(), service);
        } catch (Exception connectErr) {
            if (oauthSess == null) {
                throw asRuntime(connectErr);
            }
            boolean ok = waitForMcpOauthAuthorization(waiter, oauthSess, service, mcpToolName,
                    toolCallId, caller, connectErr);
            if (!ok) {
                throw asRuntime(connectErr);
            }
            manager.closeClient(service.getId());
            return manager.getOrCreateClient(com.ragagent.mcp.protocol.McpContext.none(), service);
        }
    }

    private static RuntimeException asRuntime(Exception e) {
        return e instanceof RuntimeException re ? re : new IllegalStateException(e);
    }

    /**
     * 发布一次性 "MCP OAuth required" 事件，<b>不</b>注册待决 waiter
     * （emitMCPOAuthRequiredNotice 语义）。用于无法完成会话内授权的非交互通道：
     * 订阅方（如 IM 回复构造器）把提示转达用户，用户去 web 控制台授权。
     * TimeoutSeconds 为 0，区别于可解决的提示。
     */
    public static void emitMcpOauthRequiredNotice(
            McpOAuthSession sess,
            McpService service,
            String mcpToolName,
            String toolCallId,
            long tenantId,
            String requestId) {
        if (sess == null || sess.eventBus() == null || service == null) {
            return;
        }
        Event event = new Event(
                "mcp-oauth-notice-" + service.getId(),
                EventType.EVENT_MCP_OAUTH_REQUIRED,
                sess.sessionId(),
                new MCPOAuthRequiredData(
                        "", tenantId, sess.sessionId(), sess.assistantMessageId(),
                        service.getId(), service.getName(), mcpToolName, 0,
                        Instant.now().getEpochSecond(), toolCallId, requestId),
                mapOf("assistant_message_id", sess.assistantMessageId(), "notice_only", true),
                requestId);
        sess.eventBus().emit(event);
    }

    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * 把底层 connect/call 错误转成 agent（最终是用户）能行动的消息
     * （oauthAwareConnectError 语义）。
     */
    public static String oauthAwareConnectError(McpService service, Throwable err) {
        if (service.getAuthConfig() != null && service.getAuthConfig().isOAuth() && isAuthorizationRequired(err)) {
            return String.format(
                    "MCP service %s requires OAuth authorization. Please open the service settings "
                            + "and click \"Authorize\" to grant access, then retry.",
                    quoteGo(service.getName()));
        }
        return "Failed to connect to MCP service: " + (err == null ? "" : messageOf(err));
    }

    /** 是否为需授权错误（两异常类型 + 重授权类型 + 消息三串）。 */
    public static boolean isAuthorizationRequired(Throwable err) {
        if (err == null) {
            return false;
        }
        if (err instanceof McpOAuthRequiredException || err instanceof McpAuthorizationRequiredException) {
            return true;
        }
        if (err instanceof OAuthReauthorizationRequiredException) {
            return true;
        }
        String msg = err.getMessage() == null ? "" : err.getMessage();
        return msg.contains("authorization required")
                || msg.contains("no valid token")
                || msg.contains("401");
    }

    static String messageOf(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    /** 双引号字符串形态（经 {@link JsonQuoting}）。 */
    static String quoteGo(String s) {
        return JsonQuoting.quoteGo(s);
    }
}
