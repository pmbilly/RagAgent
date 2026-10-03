package com.ragagent.event.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 用户决定（或超时/取消）的确认事件体。
 * emit 点：common/approval/Gate（{@code <pendingID>-approval-resolved}），见包注释 emit 表 #11。
 *
 * <p>零值输出 {@code {"pending_id":"","approved":false}}；
 * {@code reason}/{@code timed_out}/{@code canceled} 空则省略。</p>
 */
@JsonPropertyOrder({"pending_id", "approved", "reason", "timed_out", "canceled"})
public class ToolApprovalResolvedData {

    @JsonProperty("pending_id")
    private String pendingId = "";

    /** false 恒输出 */
    @JsonProperty("approved")
    private boolean approved;

    /** 空串省略 */
    @JsonProperty("reason")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String reason = "";

    /** false 省略 */
    @JsonProperty("timed_out")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean timedOut;

    /** false 省略 */
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
