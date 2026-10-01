package com.ragagent.session.dto;

/**
 * {@code POST /api/v1/sessions} 的请求体。
 *
 * <p>两个字段都可缺省（缺省即零值）：标题空串、描述再做一次客户端标记清洗
 * （见 {@code Session.sanitizeClientSessionDescription}）。</p>
 */
public record CreateSessionRequest(String title, String description) {
}
