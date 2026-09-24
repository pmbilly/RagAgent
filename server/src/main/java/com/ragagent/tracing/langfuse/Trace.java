package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * 一次请求级观测的根（对照 Go internal/tracing/langfuse/tracer.go 的 Trace）：
 * 概念上是一条 trace（例如一轮对话），其 ID 即 OTel trace id（32 位十六进制）。
 */
public interface Trace {

    /** trace id（no-op 实现返回 ""）。 */
    String getId();

    /** 记终态（对照 Trace.Finish(output, metadata)）；finish 期 metadata 与开启期合并。 */
    void finish(Object output, Map<String, Object> metadata);
}
