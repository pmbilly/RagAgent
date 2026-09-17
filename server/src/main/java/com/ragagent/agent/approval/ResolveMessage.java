package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 跨实例广播的 Resolve 报文（对照 Go approval.resolveMessage，gate.go:32-51，unexported）。
 *
 * <p>JSON 字段名与 Go 逐字一致（线上契约：滚动升级期间 Java 与 Go 实例可能共享同一 Redis）。
 * Go 的 {@code json.RawMessage ModifiedArgs} 在 Java 侧是 {@link JsonNode}——
 * 序列化时内联成 JSON 对象（而不是被引号包成字符串），与 Go 的 RawMessage 行为一致。</p>
 *
 * <p>Go 的 {@code omitempty} 在 Java 侧用 {@code NON_NULL} + 可空包装类型表达：
 * {@code timed_out}/{@code canceled} 为 false 时字段缺失，对端读到的默认值同样是 false。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ResolveMessage(
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("approved") boolean approved,
        @JsonProperty("modified_args") JsonNode modifiedArgs,
        @JsonProperty("reason") String reason,
        @JsonProperty("timed_out") Boolean timedOut,
        @JsonProperty("canceled") Boolean canceled,
        @JsonProperty("reply_channel") String replyChannel,
        @JsonProperty("origin_id") String originId,
        @JsonProperty("request_nonce") String requestNonce) {

    /** 由决策构造报文（对照 Go resolveCrossInstance 里的结构体字面量） */
    static ResolveMessage of(long tenantId, String userId, String pendingId, Decision d,
                             String replyChannel, String originId, String requestNonce) {
        return new ResolveMessage(
                tenantId,
                blankToNull(userId),
                pendingId,
                d.approved(),
                ApprovalJson.rawNode(d.modifiedArgs()),
                blankToNull(d.reason()),
                d.timedOut() ? Boolean.TRUE : null,
                d.contextCanceled() ? Boolean.TRUE : null,
                blankToNull(replyChannel),
                blankToNull(originId),
                blankToNull(requestNonce));
    }

    /** 还原成本地决策（对照 Go runSubscriber 里的 {@code Decision{...}} 组装） */
    Decision toDecision() {
        return new Decision(
                approved,
                modifiedArgs == null ? null : modifiedArgs.toString(),
                reason == null ? "" : reason,
                Boolean.TRUE.equals(timedOut),
                Boolean.TRUE.equals(canceled));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }
}
