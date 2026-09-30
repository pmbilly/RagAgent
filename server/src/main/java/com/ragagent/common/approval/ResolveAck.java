package com.ragagent.common.approval;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 持有 pending 的实例回给调用方的确认报文。
 *
 * <p>{@code status} 取值：{@code ok | not_found | tenant_mismatch | user_mismatch | already_resolved}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ResolveAck( String pendingId, String status, String originId, String requestNonce) {

    static final String STATUS_OK = "ok";
    static final String STATUS_NOT_FOUND = "not_found";
    static final String STATUS_TENANT_MISMATCH = "tenant_mismatch";
    static final String STATUS_USER_MISMATCH = "user_mismatch";
    static final String STATUS_ALREADY_RESOLVED = "already_resolved";

    static ResolveAck of(String pendingId, String status, String originId, String requestNonce) {
        return new ResolveAck(pendingId, status, originId, requestNonce);
    }
}
