package com.ragagent.agent.approval;

import java.time.Duration;

/**
 * 一次 OAuth 授权等待所需的全部上下文（对照 Go approval.OAuthPendingRequest，gate.go:111-126）。
 *
 * <p>用于“会话中途调用了需 OAuth 的 MCP 服务，弹卡片让用户授权并阻塞等待”的场景。</p>
 *
 * <p>{@code waitTimeout}：&gt; 0 时覆盖 gate 默认等待时长。等待**总是**有界的
 * （该值 / gate 默认值 / ctx 取消三者之一），因此被阻塞的线程不会泄漏。</p>
 */
public record OAuthPendingRequest(
        long tenantId,
        String userId,
        String sessionId,
        String assistantMessageId,
        String requestId,
        EventBus eventBus,
        String serviceId,
        String serviceName,
        String mcpToolName,
        String toolCallId,
        Duration waitTimeout) {

    public OAuthPendingRequest {
        userId = nz(userId);
        sessionId = nz(sessionId);
        assistantMessageId = nz(assistantMessageId);
        requestId = nz(requestId);
        serviceId = nz(serviceId);
        serviceName = nz(serviceName);
        mcpToolName = nz(mcpToolName);
        toolCallId = nz(toolCallId);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 对照 Go 的结构体字面量构造（具名字段） */
    public static final class Builder {

        private long tenantId;
        private String userId = "";
        private String sessionId = "";
        private String assistantMessageId = "";
        private String requestId = "";
        private EventBus eventBus;
        private String serviceId = "";
        private String serviceName = "";
        private String mcpToolName = "";
        private String toolCallId = "";
        private Duration waitTimeout;

        public Builder tenantId(long v) {
            this.tenantId = v;
            return this;
        }

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder assistantMessageId(String v) {
            this.assistantMessageId = v;
            return this;
        }

        public Builder requestId(String v) {
            this.requestId = v;
            return this;
        }

        public Builder eventBus(EventBus v) {
            this.eventBus = v;
            return this;
        }

        public Builder serviceId(String v) {
            this.serviceId = v;
            return this;
        }

        public Builder serviceName(String v) {
            this.serviceName = v;
            return this;
        }

        public Builder mcpToolName(String v) {
            this.mcpToolName = v;
            return this;
        }

        public Builder toolCallId(String v) {
            this.toolCallId = v;
            return this;
        }

        /** 覆盖 gate 默认等待时长（Go: WaitTimeout time.Duration） */
        public Builder waitTimeout(Duration v) {
            this.waitTimeout = v;
            return this;
        }

        public OAuthPendingRequest build() {
            return new OAuthPendingRequest(tenantId, userId, sessionId, assistantMessageId, requestId, eventBus,
                    serviceId, serviceName, mcpToolName, toolCallId, waitTimeout);
        }
    }
}
