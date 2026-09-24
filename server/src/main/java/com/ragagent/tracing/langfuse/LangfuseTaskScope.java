package com.ragagent.tracing.langfuse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.common.context.TracingContext;

/**
 * 异步任务侧的观测作用域（对照 Go internal/tracing/langfuse 的 {@code AsynqMiddleware}，
 * asynq.go L82-156）：
 *
 * <ol>
 *   <li>有上游 traceparent → 续接（worker span 成为 HTTP trace 的子节点），
 *       否则以任务类型开一条<b>独立根</b>（{@code asynq.<task_type>}）；</li>
 *   <li>两种情况都开一个 {@code asynq.<task_type>} span 包住处理体，
 *       子观测（embedding / chat / rerank…）自动挂上去；</li>
 *   <li>{@link #finish} 记 outcome（success/error），自开的根同时收尾。</li>
 * </ol>
 *
 * <p>用法（worker 线程，try-with-resources）：{@code close()} = 成功收尾 + 清理
 * 本线程观测上下文（§5：线程池复用前必须清）。失败路径显式
 * {@link #finish(String, String)} 后再抛出，避免被 close 记成 success。</p>
 */
public final class LangfuseTaskScope implements AutoCloseable {

    private static final LangfuseTaskScope NOOP = new LangfuseTaskScope(null, null, "");

    private final Span span;
    private final Trace trace;
    private final String taskType;

    private boolean finished;

    private LangfuseTaskScope(Span span, Trace trace, String taskType) {
        this.span = span;
        this.trace = trace;
        this.taskType = taskType;
    }

    /**
     * 开启任务作用域（在 worker 线程调用；内部完成 extract/开根/开 span 三步）。
     *
     * @param taskType 任务类型串（如 {@code document:process}、{@code wiki:ingest}）
     * @param tracing  入队侧 {@link LangfuseTracing#inject()} 的载体（可 null）
     * @param metadata span/根的元数据（如 knowledge_id、task_id、queue）
     * @param input    span 的 Input（建议用 {@link #previewPayload} 收敛大负载）
     */
    public static LangfuseTaskScope start(String taskType, TracingContext tracing,
                                          Map<String, Object> metadata, Object input) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return NOOP;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) {
            meta.putAll(metadata);
        }
        meta.putIfAbsent("task_type", taskType);

        boolean resumed = LangfuseTracing.extract(tracing);
        Trace trace = null;
        if (!resumed) {
            trace = manager.startTrace(new LangfuseManager.TraceOptions(
                    "asynq." + taskType,
                    LangfuseTracing.firstNonEmpty(
                            tracing == null ? "" : tracing.userId(), LangfuseTracing.userLabel()),
                    LangfuseTracing.firstNonEmpty(
                            tracing == null ? "" : tracing.sessionId(), LangfuseTracing.sessionLabel()),
                    null, meta, List.of("asynq", taskType), null, null));
        }
        Span span = manager.startSpan(new LangfuseManager.SpanOptions(
                "asynq." + taskType, input, meta));
        return new LangfuseTaskScope(span, trace, taskType);
    }

    /** 未启用时的空作用域（无状态单例）。 */
    public static LangfuseTaskScope noop() {
        return NOOP;
    }

    /** 对照 spanInputFromPayload：≤1KB 原文；超长 → {@code {preview(1KB)+"...", bytes}}。 */
    public static Object previewPayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        if (payload.length() <= 1024) {
            return payload;
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("preview", payload.substring(0, 1024) + "...");
        preview.put("bytes", payload.length());
        return preview;
    }

    /** 对照 AsynqMiddleware 收尾段：span 先收，自开的根随后收（outcome 进两者）。 */
    public void finish(String outcome, String err) {
        if (finished) {
            return;
        }
        finished = true;
        if (span == null) {
            return;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("outcome", outcome);
        span.finish(output, output, err);
        if (trace != null) {
            Map<String, Object> traceMeta = new LinkedHashMap<>();
            traceMeta.put("task_type", taskType);
            traceMeta.put("outcome", outcome);
            trace.finish(output, traceMeta);
        }
    }

    /** try-with-resources 成功路径：outcome=success + 清本线程上下文（§5）。 */
    @Override
    public void close() {
        finish("success", null);
        LangfuseContext.clear();
    }
}
