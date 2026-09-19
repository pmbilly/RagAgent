package com.ragagent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 用户决定（或超时/取消）的确认事件体
 * （对照 Go {@code event.ToolApprovalResolvedData}，internal/event/event_data.go:273-279）。
 * emit 点：agent/approval/gate.go:399（{@code <pendingID>-approval-resolved}），见包注释 emit 表 #11。
 *
 * <p>实录锚点：零值输出 {@code {"pending_id":"","approved":false}}；
 * {@code reason}/{@code timed_out}/{@code canceled} 带 omitempty。</p>
 */
@JsonPropertyOrder({"pending_id", "approved", "reason", "timed_out", "canceled"})
public class ToolApprovalResolvedData {

    @JsonProperty("pending_id")
    private String pendingId = "";

    /** 无 omitempty：false 恒输出 */
    @JsonProperty("approved")
    private boolean approved;

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

    public ToolApprovalResolvedData() {
    }

    public ToolApprovalResolvedData(String pendingId, boolean approved, String reason,
                                    boolean timedOut, boolean canceled) {
        this.pendingId = QueryData.orEmpty(pendingId);
        this.approved = approved;
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

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean v) {
        this.approved = v;
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
