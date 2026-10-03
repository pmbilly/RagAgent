package com.ragagent.memory.dto;

/**
 * {@code PUT /api/v1/memory/items/{id}} 的请求体。
 *
 * <p>只有 {@code content} 与 {@code importance}——<b>没有 {@code kind}</b>：类型由创建时定，
 * 编辑不改变它（前端带了 {@code kind} 也读不到）。</p>
 */
public record UpdateMemoryItemRequest(String content, Integer importance) {
}
