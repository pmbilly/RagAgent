package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * 一个非 LLM 调用的逻辑工作单元的活跃观测（对照 Go internal/tracing/langfuse
 * tracer.go 的 Span：ID + name + metadata，方法容忍 nil receiver）。
 */
public interface Span {

    /** Span ID（Go 的 OTel span id；no-op 实现返回 ""）。 */
    String getId();

    /**
     * 记录一次观测的终态（对照 Span.Finish(output, metadata, err)）。finish 期的
     * metadata 与 StartSpan 期的合并（不是覆写）——语义随 Go 注释。
     */
    void finish(Object output, Map<String, Object> metadata, String err);
}
