package com.ragagent.session.dto;

import java.util.List;

/**
 * {@code DELETE /api/v1/sessions/batch} 的请求体。
 *
 * <p>{@code deleteAll=true} 走全量删除；否则要求非空的 {@code ids}。</p>
 */
public record BatchDeleteSessionsRequest(List<String> ids, Boolean deleteAll) {
}
