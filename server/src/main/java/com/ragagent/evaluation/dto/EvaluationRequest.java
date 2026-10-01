package com.ragagent.evaluation.dto;

/**
 * 评估运行请求（JSON 字段名即 Java 字段名）。
 *
 * <p>四个字段全部可选：缺省/空串由服务层的缺省解析链补齐
 * （KB 为空 -> 取模型表默认 embedding/KnowledgeQA，chat 无默认则报错）。
 * 字面量 {@code null} 请求体按压全空处理。</p>
 */
public record EvaluationRequest(
        String datasetId,
        String knowledgeBaseId,
        String chatId,
        String rerankId) {

    /** 空体（字面量 null）的零值请求。 */
    public static EvaluationRequest empty() {
        return new EvaluationRequest(null, null, null, null);
    }
}
