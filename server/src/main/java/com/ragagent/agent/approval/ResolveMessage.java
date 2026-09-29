package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 跨实例广播的 Resolve 报文。
 *
 * <p>报文走 Redis 内部通道，JSON 键 = record 组件名（Java 字段名）。
 * {@code modifiedArgs} 是 {@link JsonNode}——序列化时内联成 JSON 对象
 * （而不是被引号包成字符串），对端可直接取字段。</p>
 *
 * <p>{@code NON_NULL} + 可空包装类型表达三态：
 * {@code timedOut}/{@code canceled} 为 false 时字段缺失，对端读到的默认值同样是 false。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ResolveMessage( long tenantId, String userId, String pendingId, boolean approved, JsonNode modifiedArgs, String reason, Boolean timedOut, Boolean canceled, String replyChannel, String originId, String requestNonce) {

    /** 由决策构造报文。 */
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

    /** 还原成本地决策。 */
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
