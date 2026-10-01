package com.ragagent.model.dto;

import java.util.Map;

/**
 * 模型调试结果（运行时错误不是 HTTP 错误：{@code ok=false} + {@code error} 原文，
 * HTTP 恒 200）。
 *
 * <p>{@code rawResponse} 多态：KnowledgeQA = {@link ModelDebugChatResponse}、
 * Embedding = 浮点数组、Rerank = 排序结果数组、VLLM = 文本、
 * ASR = {@link ModelDebugAsrResponse}；模型调用未发生（运行时装不上/报错）时为 null。</p>
 */
public record ModelDebugResult(
        boolean ok,
        long elapsedMs,
        String error,
        Map<String, Object> request,
        Object rawResponse,
        Map<String, Object> observations) {
}
