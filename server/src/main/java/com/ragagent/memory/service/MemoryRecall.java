package com.ragagent.memory.service;

import java.util.List;

import com.ragagent.memory.domain.MemoryItem;

/**
 * 一轮拉进来的东西：常驻块 + 与查询匹配上的情境条目。
 *
 * @param prompt 可直接追加的信封；什么都没召回到时为空串（**不是 null**——
 *               调用方无条件追加它）。
 * @param items  情境条目，加上产出 {@code prompt} 的那些常驻条目，**按它们在提示词里出现的顺序**。
 *               面向聊天界面展示。
 */
public record MemoryRecall(String prompt, List<MemoryItem> items) {

    /** 零值：空提示 + null 条目。 */
    public static final MemoryRecall EMPTY = new MemoryRecall("", null);
}
