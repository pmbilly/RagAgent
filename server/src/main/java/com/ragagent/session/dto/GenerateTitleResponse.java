package com.ragagent.session.dto;

/**
 * {@code POST /api/v1/sessions/{sessionId}/generate_title} 的响应：
 * 落库后的会话标题（{@code title} 恒输出，模型失败时是空串）。
 */
public record GenerateTitleResponse(String title) {
}
