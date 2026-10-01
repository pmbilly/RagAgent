package com.ragagent.session.dto;

/**
 * 置顶/取消置顶 {@code POST|DELETE /api/v1/sessions/{id}/pin} 的响应：
 * 动作之后的**线上值**（前端据此更新本地列表，不必重拉）。
 */
public record SessionPinResponse(boolean pinned) {
}
