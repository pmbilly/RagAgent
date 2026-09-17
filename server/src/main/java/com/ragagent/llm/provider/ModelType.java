package com.ragagent.llm.provider;

/**
 * 对照 Go types.ModelType（internal/types/model.go）。
 *
 * Go 侧是 string 类型别名，字面量见 ModelTypeEmbedding/ModelTypeRerank/
 * ModelTypeKnowledgeQA/ModelTypeVLLM/ModelTypeASR。Java 用枚举承载同一组字面量，
 * {@link #value()} 与既有 model 模块的字符串（models.type、ModelParameters.provider 等，
 * 见 com.ragagent.model.service.ProviderRegistry 的 toFrontend/queryToBackend）逐字对应。
 *
 * 声明序 = Go 常量声明序（Embedding, Rerank, KnowledgeQA, VLLM, ASR）。
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

    /** Go 侧字符串字面量 */
    public String value() {
        return value;
    }

    /**
     * 字符串 → 枚举。未知值返回 null（Go 侧任意字符串都是合法 ModelType，
     * Java 枚举无法承载未知值，故未知一律归为 null）。
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
