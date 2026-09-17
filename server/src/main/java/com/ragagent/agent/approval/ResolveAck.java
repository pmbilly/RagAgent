package com.ragagent.agent.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 持有 pending 的实例回给调用方的确认报文
 * （对照 Go approval.resolveAck，gate.go:55-60，unexported）。
 *
 * <p>{@code status} 取值与 Go 一致：{@code ok | not_found | tenant_mismatch | user_mismatch | already_resolved}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ResolveAck(
        @JsonProperty("pending_id") String pendingId,
        @JsonProperty("status") String status,
        @JsonProperty("origin_id") String originId,
        @JsonProperty("request_nonce") String requestNonce) {

    static final String STATUS_OK = "ok";
    static final String STATUS_NOT_FOUND = "not_found";
    static final String STATUS_TENANT_MISMATCH = "tenant_mismatch";
    static final String STATUS_USER_MISMATCH = "user_mismatch";
    static final String STATUS_ALREADY_RESOLVED = "already_resolved";

    static ResolveAck of(String pendingId, String status, String originId, String requestNonce) {
        return new ResolveAck(pendingId, status, originId, requestNonce);
    }
}
