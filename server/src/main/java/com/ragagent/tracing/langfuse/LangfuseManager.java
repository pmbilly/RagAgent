package com.ragagent.tracing.langfuse;

import java.util.List;
import java.util.Map;

/**
 * langfuse 观测门面。
 *
 * <p>完整观测面：{@link #startTrace} / {@link #resumeTrace} / {@link #startSpan} /
 * {@link #startChildSpan} / {@link #startGeneration} + 三个句柄，以及 OTLP/HTTP
 * 导出（{@link OtlpHttpExporter} + {@link BatchSpanProcessor}）。未 init 或
 * {@code enabled=false} 时恒返回 no-op 句柄（调用方无需判空）。</p>
 *
 * <p><b>无上下文参数的落点</b>：父观测由线程内的 {@link LangfuseContext} 帧栈解析
 * （跨线程显式 capture/replay）。调用折叠成单返回 handle。</p>
 */
public interface LangfuseManager {

    /** 安装的单例（未 init 时恒返回 no-op 单例）。 */
    static LangfuseManager get() {
        return LangfuseRegistry.get();
    }

    /** 安装单例（cfg.Enabled 时建导出链路，失败抛异常）。 */
    static LangfuseManager init(LangfuseConfig cfg) {
        return LangfuseRegistry.init(cfg);
    }

    /** 终刷并卸载。 */
    static void shutdown() {
        LangfuseRegistry.shutdown();
    }

    /** 是否启用。 */
    boolean enabled();

    /** 开根观测；已有远端 traceparent 时用 {@link #resumeTrace}。 */
    Trace startTrace(TraceOptions options);

    /**
     * 以外部传入的 W3C trace id（可选父 span id）重建句柄、<b>不建新根</b>
     * （根归上游所有，子观测继承 trace id）。
     * traceID 非法 → 返回 null，调用方回落 {@link #startTrace}。
     */
    Trace resumeTrace(String traceIdHex, String parentSpanIdHex);

    /** 开子 SPAN（无活跃 trace 时自动开根，句柄 Finish 时连根一起结束）。 */
    Span startSpan(SpanOptions options);

    /** 只在已有活跃 trace 时记录的低层 SPAN（轮询/保洁类调用用）。 */
    Span startChildSpan(SpanOptions options);

    /** 开 generation（父观测同 startSpan 语义）。 */
    Generation startGeneration(GenerationOptions options);

    /** SPAN 观测配置。 */
    record SpanOptions(String name, Object input, Map<String, Object> metadata) {
        public static SpanOptions of(String name) {
            return new SpanOptions(name, null, null);
        }
    }

    /** TRACE 观测配置。 */
    record TraceOptions(String name, String userId, String sessionId, Object input,
                        Map<String, Object> metadata, List<String> tags,
                        String environment, String release) {

        public static TraceOptions of(String name) {
            return new TraceOptions(name, null, null, null, null, null, null, null);
        }
    }

    /** GENERATION 观测配置。 */
    record GenerationOptions(String name, String model, Object input,
                             Map<String, Object> metadata, Map<String, Object> modelParameters) {

        public static GenerationOptions of(String name, String model) {
            return new GenerationOptions(name, model, null, null, null);
        }
    }
}
