package com.ragagent.tracing.langfuse;

import java.time.Instant;
import java.util.Map;

/**
 * langfuse 真实实现。
 *
 * <p>生命周期：构造即建导出链路（{@link OtlpHttpExporter} +
 * {@link BatchSpanProcessor}）；{@code shutdown} 终刷并停用（重复调用幂等）。</p>
 *
 * <p>句柄语义：trace 的 Finish 合并开启期 metadata；span/generation 的
 * Finish 追加 output/usage、非空 err 记 ERROR（exception 事件 + Status）；span 若
 * 隐式开过自动根（autoTrace），Finish 时连根一起结束（否则根永不出导）。</p>
 */
final class DefaultLangfuseManager implements LangfuseManager {

    private final LangfuseConfig cfg;
    private final BatchSpanProcessor processor;

    private volatile boolean closed;

    DefaultLangfuseManager(LangfuseConfig cfg) {
        this(cfg, new OtlpHttpExporter(cfg)::export);
    }

    /** 测试专用：注入记录出口（同步导出语义）。 */
    DefaultLangfuseManager(LangfuseConfig cfg, BatchSpanProcessor.SpanSink sink) {
        this.cfg = cfg;
        // 同步导出：测试断言依赖 "Finish 即已导出" 的确定性（生产构造器走后台导出线程）
        this.processor = new BatchSpanProcessor(cfg, sink, true);
    }

    @Override
    public boolean enabled() {
        return !closed;
    }

    /** 由 LangfuseRegistry 调用。 */
    void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        processor.shutdown();
    }

    // ── StartTrace / ResumeTrace ──

    @Override
    public Trace startTrace(TraceOptions opts) {
        if (!enabled()) {
            return NoopLangfuseManager.NOOP_TRACE;
        }
        // 父继承：线程上下文携带远端 span context（traceparent 提取）或活跃
        // span 时，根 span 继承其 trace id / 父 span id；否则开新 trace。
        RecordedSpan span = newSpan(opts.name(), LangfuseContext.current());
        span.putAttribute(LangfuseAttributes.ATTR_OBS_TYPE, LangfuseAttributes.OBS_TYPE_TRACE);
        if (!LangfuseAttributes.isEmpty(opts.name())) {
            span.putAttribute(LangfuseAttributes.ATTR_TRACE_NAME, opts.name());
        }
        if (!LangfuseAttributes.isEmpty(opts.userId())) {
            span.putAttribute(LangfuseAttributes.ATTR_USER_ID, opts.userId());
        }
        if (!LangfuseAttributes.isEmpty(opts.sessionId())) {
            span.putAttribute(LangfuseAttributes.ATTR_SESSION_ID, opts.sessionId());
        }
        // environment/release：观测级覆盖配置级（非空优先）
        String environment = LangfuseAttributes.isEmpty(opts.environment())
                ? cfg.environment() : opts.environment();
        if (!LangfuseAttributes.isEmpty(environment)) {
            span.putAttribute(LangfuseAttributes.ATTR_ENVIRONMENT, environment);
        }
        String release = LangfuseAttributes.isEmpty(opts.release()) ? cfg.release() : opts.release();
        if (!LangfuseAttributes.isEmpty(release)) {
            span.putAttribute(LangfuseAttributes.ATTR_RELEASE, release);
        }
        span.putAttribute(LangfuseAttributes.ATTR_TRACE_INPUT,
                LangfuseAttributes.jsonAttrValue(opts.input()));
        span.putAttribute(LangfuseAttributes.ATTR_TRACE_METADATA,
                LangfuseAttributes.jsonAttrValue(opts.metadata()));
        if (opts.tags() != null && !opts.tags().isEmpty()) {
            span.putAttribute(LangfuseAttributes.ATTR_TRACE_TAGS,
                    LangfuseAttributes.jsonAttrValue(opts.tags()));
        }
        LangfuseContext.push(frameOf(span));
        LangfuseContext.markTrace();
        return new TraceHandle(span, opts.metadata(), null);
    }

    @Override
    public Trace resumeTrace(String traceIdHex, String parentSpanIdHex) {
        if (!enabled() || LangfuseAttributes.isEmpty(traceIdHex)) {
            return null;
        }
        byte[] traceId = LangfuseAttributes.hexToBytes(traceIdHex);
        if (traceId == null || traceId.length != 16) {
            // 非 W3C 32-hex（legacy UUID 等），无法 resume——调用方回落 StartTrace
            return null;
        }
        String parentSpan = "";
        if (!LangfuseAttributes.isEmpty(parentSpanIdHex)) {
            byte[] spanId = LangfuseAttributes.hexToBytes(parentSpanIdHex);
            if (spanId != null && spanId.length == 8) {
                parentSpan = parentSpanIdHex;
            }
        }
        LangfuseContext.push(new LangfuseContext.Frame(traceIdHex, parentSpan));
        LangfuseContext.markTrace();
        // 根归上游所有：句柄无自有 span（Finish 为 no-op）
        return new TraceHandle(null, null, traceIdHex);
    }

    // ── StartSpan / StartChildSpan / StartGeneration ──

    @Override
    public Span startSpan(SpanOptions opts) {
        if (!enabled()) {
            return NoopLangfuseManager.NOOP_SPAN;
        }
        TraceHandle autoTrace = null;
        if (LangfuseContext.current() == null) {
            // 无活跃 trace → 开浅根（否则子 span 成孤儿）并持有句柄，Finish 时连根收
            autoTrace = (TraceHandle) startTrace(TraceOptions.of(opts.name()));
        }
        RecordedSpan span = newSpan(opts.name(), LangfuseContext.current());
        span.putAttribute(LangfuseAttributes.ATTR_OBS_TYPE, LangfuseAttributes.OBS_TYPE_SPAN);
        span.putAttribute(LangfuseAttributes.ATTR_OBS_INPUT, LangfuseAttributes.jsonAttrValue(opts.input()));
        span.putAttribute(LangfuseAttributes.ATTR_OBS_METADATA, LangfuseAttributes.jsonAttrValue(opts.metadata()));
        LangfuseContext.push(frameOf(span));
        return new SpanHandle(span, opts.metadata(), autoTrace);
    }

    @Override
    public Span startChildSpan(SpanOptions opts) {
        if (!enabled() || !LangfuseContext.hasTrace() || LangfuseContext.current() == null) {
            return NoopLangfuseManager.NOOP_SPAN;
        }
        RecordedSpan span = newSpan(opts.name(), LangfuseContext.current());
        span.putAttribute(LangfuseAttributes.ATTR_OBS_TYPE, LangfuseAttributes.OBS_TYPE_SPAN);
        span.putAttribute(LangfuseAttributes.ATTR_OBS_INPUT, LangfuseAttributes.jsonAttrValue(opts.input()));
        span.putAttribute(LangfuseAttributes.ATTR_OBS_METADATA, LangfuseAttributes.jsonAttrValue(opts.metadata()));
        LangfuseContext.push(frameOf(span));
        return new SpanHandle(span, opts.metadata(), null);
    }

    @Override
    public Generation startGeneration(GenerationOptions opts) {
        if (!enabled()) {
            return NoopLangfuseManager.NOOP_GENERATION;
        }
        TraceHandle autoTrace = null;
        if (LangfuseContext.current() == null) {
            autoTrace = (TraceHandle) startTrace(TraceOptions.of(opts.name()));
        }
        RecordedSpan span = newSpan(opts.name(), LangfuseContext.current());
        span.putAttribute(LangfuseAttributes.ATTR_OBS_TYPE, LangfuseAttributes.OBS_TYPE_GENERATION);
        span.putAttribute(LangfuseAttributes.ATTR_OBS_MODEL, opts.model());
        span.putAttribute(LangfuseAttributes.ATTR_OBS_INPUT, LangfuseAttributes.jsonAttrValue(opts.input()));
        span.putAttribute(LangfuseAttributes.ATTR_OBS_METADATA, LangfuseAttributes.jsonAttrValue(opts.metadata()));
        span.putAttribute(LangfuseAttributes.ATTR_OBS_MODEL_PARAMS,
                LangfuseAttributes.jsonAttrValue(opts.modelParameters()));
        // generation 是叶子观测，不入帧栈：本项目调用点均把生成结果直接消费，
        // 不在其上下文里派生兄弟观测。
        return new GenerationHandle(span, autoTrace);
    }

    // ── 内部 ──

    private RecordedSpan newSpan(String name, LangfuseContext.Frame parent) {
        String traceId = parent != null
                ? parent.traceIdHex()
                : LangfuseAttributes.randomTraceIdHex();
        String parentSpanId = parent == null ? null : parent.spanIdHex();
        return new RecordedSpan(traceId, LangfuseAttributes.randomSpanIdHex(), parentSpanId,
                name, epochNanos());
    }

    private static LangfuseContext.Frame frameOf(RecordedSpan span) {
        return new LangfuseContext.Frame(span.traceIdHex, span.spanIdHex);
    }

    /** OTLP 的 unix 纳秒（epoch 壁钟；{@code System.nanoTime} 是单调钟，不能上 wire）。 */
    private static long epochNanos() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /** 收口：定稿结束时刻 → 出栈 → 入队（对应 OTel 的 End → OnEnd 时序）。 */
    private void record(RecordedSpan span) {
        span.endNanos = epochNanos();
        LangfuseContext.pop(frameOf(span));
        processor.enqueue(span);
    }

    /** Trace 句柄（resume 场景 span==null，Finish 为 no-op）。 */
    private final class TraceHandle implements Trace {

        private final RecordedSpan span;
        private final Map<String, Object> metadata;
        private final String resumedTraceId;

        TraceHandle(RecordedSpan span, Map<String, Object> metadata, String resumedTraceId) {
            this.span = span;
            this.metadata = metadata;
            this.resumedTraceId = resumedTraceId;
        }

        @Override
        public String getId() {
            if (span != null) {
                return span.traceIdHex;
            }
            return resumedTraceId == null ? "" : resumedTraceId;
        }

        @Override
        public void finish(Object output, Map<String, Object> finishMetadata) {
            if (!enabled() || span == null) {
                return;
            }
            span.putAttribute(LangfuseAttributes.ATTR_TRACE_OUTPUT,
                    LangfuseAttributes.jsonAttrValue(output));
            Map<String, Object> merged = LangfuseAttributes.mergeMetadata(metadata, finishMetadata);
            if (merged != null) {
                span.putAttribute(LangfuseAttributes.ATTR_TRACE_METADATA,
                        LangfuseAttributes.jsonAttrValue(merged));
            }
            record(span);
        }
    }

    /** Span 句柄。 */
    private final class SpanHandle implements Span {

        private final RecordedSpan span;
        private final Map<String, Object> metadata;
        private final TraceHandle autoTrace;

        SpanHandle(RecordedSpan span, Map<String, Object> metadata, TraceHandle autoTrace) {
            this.span = span;
            this.metadata = metadata;
            this.autoTrace = autoTrace;
        }

        @Override
        public String getId() {
            return span.spanIdHex;
        }

        @Override
        public void finish(Object output, Map<String, Object> finishMetadata, String err) {
            if (!enabled()) {
                return;
            }
            span.putAttribute(LangfuseAttributes.ATTR_OBS_OUTPUT,
                    LangfuseAttributes.jsonAttrValue(output));
            Map<String, Object> merged = LangfuseAttributes.mergeMetadata(metadata, finishMetadata);
            if (merged != null) {
                span.putAttribute(LangfuseAttributes.ATTR_OBS_METADATA,
                        LangfuseAttributes.jsonAttrValue(merged));
            }
            if (err != null) {
                span.recordError("", err);
                span.setErrorStatus(err);
            }
            record(span);
            if (autoTrace != null) {
                autoTrace.finish(null, null);
            }
        }
    }

    /** Generation 句柄。 */
    private final class GenerationHandle implements Generation {

        private final RecordedSpan span;
        private final TraceHandle autoTrace;

        GenerationHandle(RecordedSpan span, TraceHandle autoTrace) {
            this.span = span;
            this.autoTrace = autoTrace;
        }

        @Override
        public String getId() {
            return span.spanIdHex;
        }

        @Override
        public void finish(Object output, TokenUsage usage, String err) {
            if (!enabled()) {
                return;
            }
            span.putAttribute(LangfuseAttributes.ATTR_OBS_OUTPUT,
                    LangfuseAttributes.jsonAttrValue(output));
            if (usage != null) {
                span.putAttribute(LangfuseAttributes.ATTR_OBS_USAGE_DETAILS,
                        LangfuseAttributes.jsonAttrValue(usage));
            }
            if (err != null) {
                span.recordError("", err);
                span.setErrorStatus(err);
            }
            record(span);
            if (autoTrace != null) {
                autoTrace.finish(null, null);
            }
        }

        @Override
        public void markCompletionStart() {
            if (!enabled()) {
                return;
            }
            span.putAttribute(LangfuseAttributes.ATTR_OBS_COMPLETION_START,
                    LangfuseAttributes.isoTime(System.currentTimeMillis()));
        }
    }
}
