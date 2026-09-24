package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * 一次模型调用（LLM / embedding / rerank / VLM / ASR）的观测
 * （对照 Go internal/tracing/langfuse/tracer.go 的 Generation）。
 */
public interface Generation {

    /** generation id（= OTel span id；no-op 实现返回 ""）。 */
    String getId();

    /** 记终态（对照 Generation.Finish(output, usage, err)）。 */
    void finish(Object output, TokenUsage usage, String err);

    /** 记首个 token 到达时刻（对照 MarkCompletionStart；TTFT 用）。 */
    void markCompletionStart();
}
