package com.ragagent.memory.dto;

import java.util.List;

/**
 * 记忆管理器三类列表（条目 / 主题 / 文档亲和）共用的分页响应。
 *
 * <p>形态照 {@code docs/knowledge-api-contract-v1.md} §2.1 的约定：
 * {@code {items, page, pageSize, total}}——旧信封
 * {@code {"data":[…],"success":true,"total":N}} 已退役。</p>
 *
 * <p>{@code page}/{@code pageSize} 由 controller 从 offset/limit 换算：
 * {@code pageSize = limit}、{@code page = offset / limit + 1}（整数除法，
 * 非整页偏移也归到所在页）。请求侧仍收 {@code limit}/{@code offset}，
 * controller 侧的容错语义不变。</p>
 */
public record MemoryListResponse<T>(List<T> items, long page, long pageSize, long total) {
}
