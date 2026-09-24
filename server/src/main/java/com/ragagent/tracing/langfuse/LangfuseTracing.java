package com.ragagent.tracing.langfuse;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.context.TracingContext;

/**
 * 观测上下文的注入/续接门面（对照 Go internal/tracing/langfuse 的
 * {@code InjectTracing} / {@code TraceparentFromContext} / {@code AttachTraceparent}
 * 三函数，asynq.go L28-48 + context.go L49-85）。
 *
 * <p>用途一（入队侧）：{@link #inject()} 把当前 span 的 traceparent 打进
 * {@link TracingContext}，随任务负载落库/进队列。</p>
 * <p>用途二（worker 侧）：{@link #extract(TracingContext)} 续接上游 trace，
 * 或 {@link #attachTraceparent(String)} 在本进程内续接（如后续 HTTP 请求处理
 * 早先请求派生的活）。</p>
 *
 * <p><b>已知差异（备案）</b>：Go 的 traceparent 由 OTel propagator 从 ctx 注入，
 * 采样位反映实际采样决策（{@code LANGFUSE_SAMPLE_RATE}）；Java 侧渲染器未实现
 * 采样决策（恒采样），故 flag 恒为 {@code 01}。默认 sampleRate=1.0 时与 Go 一致。</p>
 */
public final class LangfuseTracing {

    private LangfuseTracing() {
    }

    /** 对照 InjectTracing：未启用/无活跃 span → 全空载体（照 Go 的零值语义，零成本）。 */
    public static TracingContext inject() {
        if (!LangfuseManager.get().enabled()) {
            return TracingContext.EMPTY;
        }
        LangfuseContext.Frame frame = LangfuseContext.current();
        return new TracingContext(
                frame == null ? "" : frame.traceIdHex(),
                "",
                traceparentOf(frame),
                userLabel(),
                sessionLabel());
    }

    /** 对照 TraceparentFromContext：当前 span 的 W3C traceparent（无 → ""）。 */
    public static String traceparentFromContext() {
        if (!LangfuseManager.get().enabled()) {
            return "";
        }
        return traceparentOf(LangfuseContext.current());
    }

    /**
     * 当前活跃 trace 的 32 位十六进制 id（无 → ""）。
     * 供落库型追踪（如 {@code knowledge_processing_spans.langfuse_trace_id}）对齐同一棵树。
     */
    public static String currentTraceId() {
        LangfuseContext.Frame frame = LangfuseContext.current();
        return frame == null ? "" : frame.traceIdHex();
    }

    /**
     * 对照 AttachTraceparent：本进程内续接（已有活跃 trace 时**不动**，
     * 不把本地活的父节点换成远端父）；traceparent 非法 → 静默忽略。
     */
    public static void attachTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isEmpty() || !LangfuseManager.get().enabled()) {
            return;
        }
        if (LangfuseContext.hasTrace()) {
            return;
        }
        resumeFromTraceparent(traceparent);
    }

    /**
     * worker 侧续接（对照 AsynqMiddleware 的 extract 路径）。
     *
     * @return 是否成功续接（false = 无上游/非法/未启用 → 调用方开独立根）
     */
    public static boolean extract(TracingContext tracing) {
        if (tracing == null || tracing.traceparent().isEmpty() || !LangfuseManager.get().enabled()) {
            return false;
        }
        return resumeFromTraceparent(tracing.traceparent());
    }

    /** 对照 userIDFromCtx：显式用户 id → {@code tenant:<id>} → ""。 */
    public static String userLabel() {
        String userId = TenantContext.currentUserId();
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId != null && tenantId != 0) {
            return "tenant:" + tenantId;
        }
        return "";
    }

    /** 对照 sessionIDFromCtx：request id 兜底（同一逻辑任务的重试归到一组）。 */
    public static String sessionLabel() {
        String requestId = TenantContext.currentRequestId();
        return requestId == null ? "" : requestId;
    }

    /** 拼 W3C traceparent（缺 id → ""，对照 OTel 的 IsValid 语义）。 */
    static String traceparentOf(LangfuseContext.Frame frame) {
        if (frame == null || frame.traceIdHex().isEmpty() || frame.spanIdHex().isEmpty()) {
            return "";
        }
        return "00-" + frame.traceIdHex() + "-" + frame.spanIdHex() + "-01";
    }

    private static boolean resumeFromTraceparent(String traceparent) {
        String[] ids = LangfuseHttpInterceptor.parseTraceparent(traceparent);
        if (ids == null) {
            return false;
        }
        LangfuseContext.push(new LangfuseContext.Frame(ids[0], ids[1]));
        LangfuseContext.markTrace();
        return true;
    }

    /** 首个非空（对照 Go 的 firstNonEmptyString）。 */
    static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
