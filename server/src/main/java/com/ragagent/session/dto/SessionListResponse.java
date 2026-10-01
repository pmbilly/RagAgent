package com.ragagent.session.dto;

import java.util.List;

import com.ragagent.session.domain.SessionListItem;

/**
 * {@code GET /api/v1/sessions} 的分页响应（§2.1 的标准形状）。
 *
 * <p>与内部的 {@code SessionPage} 分开：那个是"仓储/服务"的返回值（{@code items,total,page,pageSize}），
 * 这个是**线格式**（{@code items,page,pageSize,total}）——两个关注点各留各的，
 * 以后动线格式不会牵到仓储签名。</p>
 */
public record SessionListResponse(List<SessionListItem> items, int page, int pageSize, long total) {
}
