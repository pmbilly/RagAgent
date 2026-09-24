package com.ragagent.tracing.langfuse;

import java.util.Map;

/**
 * langfuse 未启用时的 no-op 实现（对照 Go cfg.Enabled=false 时的 disabled
 * Manager：每个公共方法都是 no-op，返回的句柄非 null、调用方无需判空）。
 */
final class NoopLangfuseManager implements LangfuseManager {

    static final NoopLangfuseManager INSTANCE = new NoopLangfuseManager();

    static final Span NOOP_SPAN = new Span() {
        @Override
        public String getId() {
            return "";
        }

        @Override
        public void finish(Object output, Map<String, Object> metadata, String err) {
            // no-op
        }
    };

    static final Trace NOOP_TRACE = new Trace() {
        @Override
        public String getId() {
            return "";
        }

        @Override
        public void finish(Object output, Map<String, Object> metadata) {
            // no-op
        }
    };

    static final Generation NOOP_GENERATION = new Generation() {
        @Override
        public String getId() {
            return "";
        }

        @Override
        public void finish(Object output, TokenUsage usage, String err) {
            // no-op
        }

        @Override
        public void markCompletionStart() {
            // no-op
        }
    };

    private NoopLangfuseManager() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Trace startTrace(TraceOptions options) {
        return NOOP_TRACE;
    }

    @Override
    public Trace resumeTrace(String traceIdHex, String parentSpanIdHex) {
        return null;
    }

    @Override
    public Span startSpan(SpanOptions options) {
        return NOOP_SPAN;
    }

    @Override
    public Span startChildSpan(SpanOptions options) {
        return NOOP_SPAN;
    }

    @Override
    public Generation startGeneration(GenerationOptions options) {
        return NOOP_GENERATION;
    }
}
