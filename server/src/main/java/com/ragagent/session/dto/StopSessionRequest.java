package com.ragagent.session.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/sessions/{sessionId}/stop} 的请求体：要停的助手消息 id。
 */
public record StopSessionRequest(
        @NotBlank(message = "messageId: 不能为空") String messageId) {
}
