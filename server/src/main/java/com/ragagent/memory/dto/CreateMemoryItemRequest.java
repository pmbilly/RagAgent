package com.ragagent.memory.dto;

/**
 * {@code POST /api/v1/memory/items} 的请求体（手工创建一条记忆）。
 *
 * <p>三个字段都允许缺省：缺省/显式 null 都按零值处理（{@code kind}/{@code content} 为空串、
 * {@code importance} 为 0），"内容是否为空"由服务层判定并给出它自己的错误。</p>
 */
public record CreateMemoryItemRequest(String kind, String content, Integer importance) {
}
