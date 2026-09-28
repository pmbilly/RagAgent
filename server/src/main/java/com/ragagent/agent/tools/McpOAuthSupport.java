package com.ragagent.agent.tools;

import java.time.Duration;
import java.time.Instant;

import com.ragagent.agent.approval.Cancellation;
import com.ragagent.agent.approval.Decision;
import com.ragagent.agent.approval.OAuthPendingRequest;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;
import com.ragagent.event.MCPOAuthRequiredData;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.oauth.OAuthReauthorizationRequiredException;
import com.ragagent.mcp.protocol.McpAuthorizationRequiredException;
import com.ragagent.mcp.protocol.McpOAuthRequiredException;

/**
 * MCP OAuth 的会话挂载与重试（对照 Go {@code mcp_oauth.go}，逐字移植）。
 *
 * <p>MCPOAuthSession 携带 chat/session 元数据，让 MCP connect 与工具注册能在会话内
 * 暂停等 OAuth。null = 不弹提示。等待<b>总是</b>有界——用户既不授权也不跳过时，
 * 被阻塞的线程不能泄漏（值 ≤0 时回落 gate 缺省超时）。</p>
 *
 * <p><b>Go ctx → 显式参数</b>：Go 的 waitForMCPOAuthAuthorization 从 ctx 派生
 * tenant/user/requestID 的回落值并返回 fresh ctx；Java 无 ctx——身份由调用方
 * （工具，引擎内嵌）显式传入，成功重试直接回到原调用点。</p>
 */
public final class McpOAuthSupport {

    /** 对照 defaultMCPToolExecTimeout。 */
    static final Duration DEFAULT_MCP_TOOL_EXEC_TIMEOUT = Duration.ofSeconds(60);

    private McpOAuthSupport() {
    }

    /**
     * 会话内 OAuth 等待的超时（对照 oauthWaitTimeout）：来自 agent 的用户配置（秒）。
     * ≤0 返回 0，告诉 gate 回落到它配置的缺省超时。
     */
    public static Duration oauthWaitTimeout(McpOAuthSession sess) {
        if (sess == null || sess.authWaitTimeoutSeconds() <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(sess.authWaitTimeoutSeconds());
    }

    /**
     * 携带 chat/session 元数据的 OAuth 会话（对照 MCPOAuthSession struct）。
     * record 表达 Go 的结构体字面量；approvalCancellation 是 Go 的 ApprovalCtx
     * （无每操作超时的取消源）；execTimeout 是授权成功后重试 ctx 的上限。
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

        /** 对照 withAuthWaitTimeout：安全作用于 null 会话。 */
        public McpOAuthSession withAuthWaitTimeout(int seconds) {
            return new McpOAuthSession(eventBus, sessionId, assistantMessageId, userId, requestId,
                    approvalCancellation, execTimeout, seconds);
        }
    }

    /** 对照 oauthSessionFromToolExec：meta 无 EventBus 时返回 null。 */
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
     * OAuth 等待门（对照 oauthWaiter 接口断言）。Gate.requestOAuthAndWait 的签名与本接口
     * 一致，装配层传 {@code gate::requestOAuthAndWait} 即可适配（4.1 的 Gate 不改）。
     */
    @FunctionalInterface
    public interface OAuthWaiter {
        Decision requestOAuthAndWait(Cancellation ctx, OAuthPendingRequest req);
    }

    /** 一次等待所需的调用方身份（Go 从 ctx 派生的回落值 + 非交互旗标）。 */
    public record CallerIdentity(long tenantId, String userId, String requestId, boolean nonInteractive) {
    }

    /**
     * 等待会话内 OAuth 授权的钩子（对照 waitForMCPOAuthAuthorization 的 ok 布尔）：
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
        // 然后不带工具继续（对照 types.WithMCPOAuthNonInteractive）。
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
     * （对照 getOrCreateMCPClientWithOAuthRetry）。失败抛运行时异常（Go error 通道）。
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
     * （对照 emitMCPOAuthRequiredNotice）。用于无法完成会话内授权的非交互通道：
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
     * （对照 oauthAwareConnectError）。
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

    /** 对照 isAuthorizationRequired（mcpclient 两断言 + mcp.Reauth 类型 + 消息三串）。 */
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

    /** Go %q 的普通串形态。 */
    static String quoteGo(String s) {
        return GoQuoting.quoteGo(s);
    }
}
