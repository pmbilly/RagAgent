package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 会话内 OAuth 提示结果（authorized / timeout / cancel）确认事件体
 * （对照 Go {@code event.MCPOAuthResolvedData}，internal/event/event_data.go:300-307）。
 * emit 点：agent/approval/gate.go:506（{@code <pendingID>-mcp-oauth-resolved}），见包注释 emit 表 #13。
 *
 * <p>实录锚点：零值输出 {@code {"pending_id":"","service_id":"","authorized":false}}；
 * {@code reason}/{@code timed_out}/{@code canceled} 带 omitempty。</p>
 */
@JsonPropertyOrder({"pending_id", "service_id", "authorized", "reason", "timed_out", "canceled"})
public class MCPOAuthResolvedData {

    @JsonProperty("pending_id")
    private String pendingId = "";

    @JsonProperty("service_id")
    private String serviceId = "";

    /** 无 omitempty：false 恒输出 */
    @JsonProperty("authorized")
    private boolean authorized;

    /** Go omitempty */
    @JsonProperty("reason")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String reason = "";

    /** Go omitempty */
    @JsonProperty("timed_out")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean timedOut;

    /** Go omitempty */
    @JsonProperty("canceled")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean canceled;

    public MCPOAuthResolvedData() {
    }

    public MCPOAuthResolvedData(String pendingId, String serviceId, boolean authorized,
                                String reason, boolean timedOut, boolean canceled) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.serviceId = QueryData.orEmpty(serviceId);
        this.authorized = authorized;
        this.reason = QueryData.orEmpty(reason);
        this.timedOut = timedOut;
        this.canceled = canceled;
    }

    public String getPendingId() {
        return pendingId;
    }

    public void setPendingId(String v) {
        this.pendingId = QueryData.orEmpty(v);
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String v) {
        this.serviceId = QueryData.orEmpty(v);
    }

    public boolean isAuthorized() {
        return authorized;
    }

    public void setAuthorized(boolean v) {
        this.authorized = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = QueryData.orEmpty(v);
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public void setTimedOut(boolean v) {
        this.timedOut = v;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean v) {
        this.canceled = v;
    }
}
