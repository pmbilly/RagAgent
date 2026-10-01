package com.ragagent.session.dto;

/**
 * {@code PUT /api/v1/sessions/{id}} 的请求体。
 *
 * <p>只有 {@code title} 与 {@code description}——仓储的 UPDATE 白名单也只写这两列，
 * 所以请求体不再直接绑定数据库实体（§1.10）。</p>
 */
public record UpdateSessionRequest(String title, String description) {
}
