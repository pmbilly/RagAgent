package com.ragagent.agent.approval;

/**
 * 一次待决审批所需的全部上下文（对照 Go approval.PendingRequest，gate.go:85-100）。
 *
 * <p>字段顺序与 Go 声明序一致。{@code args} 是原始 JSON 字符串（Go: {@code json.RawMessage}）。
 * Go 用结构体字面量构造；Java 用 {@link #builder()}（项目约定不使用 Lombok）。</p>
 *
 * <p><b>userId</b>：发起调用的会话属主，用于 {@code Resolve} 鉴权；为空表示跳过用户校验
 * （与 Go 注释 “empty disables user check” 一致）。</p>
 */
public record PendingRequest(
        long tenantId,
        String userId,
        String sessionId,
        String assistantMessageId,
        String requestId,
        EventBus eventBus,
        String serviceId,
        String serviceName,
        String mcpToolName,
        String registeredToolName,
        String description,
        String args,
        String toolCallId) {

    public PendingRequest {
        userId = nz(userId);
        sessionId = nz(sessionId);
        assistantMessageId = nz(assistantMessageId);
        requestId = nz(requestId);
        serviceId = nz(serviceId);
        serviceName = nz(serviceName);
        mcpToolName = nz(mcpToolName);
        registeredToolName = nz(registeredToolName);
        description = nz(description);
        args = nz(args);
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
        private String registeredToolName = "";
        private String description = "";
        private String args = "";
        private String toolCallId = "";

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

        public Builder registeredToolName(String v) {
            this.registeredToolName = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        /** 原始 JSON（Go: json.RawMessage） */
        public Builder args(String v) {
            this.args = v;
            return this;
        }

        public Builder toolCallId(String v) {
            this.toolCallId = v;
            return this;
        }

        public PendingRequest build() {
            return new PendingRequest(tenantId, userId, sessionId, assistantMessageId, requestId, eventBus,
                    serviceId, serviceName, mcpToolName, registeredToolName, description, args, toolCallId);
        }
    }
}
