package com.ragagent.tracing.langfuse;


/**
 * 一次模型调用（LLM / embedding / rerank / VLM / ASR）的观测。
 */
public interface Generation {

    /** generation id（= OTel span id；no-op 实现返回 ""）。 */
    String getId();

    /** 记终态。 */
    void finish(Object output, TokenUsage usage, String err);

    /** 记首个 token 到达时刻（TTFT 用）。 */
    void markCompletionStart();
}
