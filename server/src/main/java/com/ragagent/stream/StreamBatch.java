package com.ragagent.stream;

import java.util.List;

/**
 * 一次增量读的结果（对照 Go 里 {@code GetEvents}/{@code GetSteerEvents} 的
 * {@code ([]StreamEvent, int, error)} 三元返回）。
 *
 * @param events     从 fromOffset 起的事件；无新事件时是**空列表**（Go 的 nil slice 亦为空语义）
 * @param nextOffset 下次调用应传入的 offset。注意：解析失败被跳过的事件**仍计入** offset
 *                   （Go 用 {@code fromOffset + len(results)} 算的是 Redis 原始条数）
 */
public record StreamBatch(List<StreamEvent> events, int nextOffset) {

    public static StreamBatch empty(int fromOffset) {
        return new StreamBatch(List.of(), fromOffset);
    }
}
