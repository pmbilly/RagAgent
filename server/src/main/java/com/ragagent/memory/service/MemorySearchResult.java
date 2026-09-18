package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.memory.domain.MemoryItem;

/**
 * 一次按需查找记忆库的返回（对照 Go {@code interfaces.MemorySearchResult}，
 * internal/types/interfaces/memory.go L257-272）。
 *
 * <p><b>为什么与 {@link MemoryRecall} 是两个类型</b>：两者的消费者不同。
 * 召回为某一轮产出提示词信封；查找为工具产出条目，而那个工具必须告诉模型
 * <b>为什么</b>它一无所获。"这个用户把记忆关掉了"与"没存过匹配的东西"
 * 需要不同的回答，把两者都塌成一个空切片，会让 agent 对一个只是禁用了记忆的人
 * 报告"记忆库是空的"。</p>
 *
 * @param available 记忆是否关闭：工作区、用户、当前请求的 agent 三层任一。
 * @param items     匹配结果，最相关在前。
 */
public record MemorySearchResult(boolean available, List<MemoryItem> items) {

    /** 对照 Go 的 {@code interfaces.MemorySearchResult{}} 零值：不可用 + nil 条目。 */
    public static final MemorySearchResult UNAVAILABLE = new MemorySearchResult(false, null);
}
