package com.ragagent.llm.provider;

/**
 * 模型类型枚举。
 *
 * 字面量与既有 model 模块的字符串（models.type、ModelParameters.provider 等，
 * 见 com.ragagent.model.service.ProviderRegistry 的 toFrontend/queryToBackend）逐字对应。
 *
 * 声明序固定（Embedding, Rerank, KnowledgeQA, VLLM, ASR）。
 */
public enum ModelType {

    /** 对照 types.ModelTypeEmbedding */
    EMBEDDING("Embedding"),
    /** 对照 types.ModelTypeRerank */
    RERANK("Rerank"),
    /** 对照 types.ModelTypeKnowledgeQA（Chat） */
    KNOWLEDGE_QA("KnowledgeQA"),
    /** 对照 types.ModelTypeVLLM */
    VLLM("VLLM"),
    /** 对照 types.ModelTypeASR */
    ASR("ASR");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    /** 字符串字面量 */
    public String value() {
        return value;
    }

    /**
     * 字符串 → 枚举。未知值返回 null（调用方需处理 null）。
     */
    public static ModelType fromValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (ModelType t : values()) {
            if (t.value.equals(value)) {
                return t;
            }
        }
        return null;
    }
}
