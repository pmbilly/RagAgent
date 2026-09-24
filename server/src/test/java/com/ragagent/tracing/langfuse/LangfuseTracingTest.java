package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.context.TracingContext;
import com.ragagent.memory.service.MemoryTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 异步追踪接线测试（对照 Go asynq.go 的 InjectTracing/AsynqMiddleware 与
 * context.go 的 TraceparentFromContext/AttachTraceparent）：载体注入、跨线程续接、
 * 任务作用域（续接 vs 独立根）、MemoryTrace 门面转真。
 */
class LangfuseTracingTest {

    private static final String UPSTREAM_TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String UPSTREAM_SPAN = "b7ad6b7169203331";

    private final List<RecordedSpan> exported = new CopyOnWriteArrayList<>();
    private DefaultLangfuseManager manager;

    @BeforeEach
    void setUp() {
        exported.clear();
        LangfuseContext.clear();
        TenantContext.clear();
        manager = new DefaultLangfuseManager(config(), exported::addAll);
        LangfuseRegistry.installForTest(manager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        LangfuseContext.clear();
        TenantContext.clear();
    }

    private static LangfuseConfig config() {
        return new LangfuseConfig(true, "http://localhost:1", "pk", "sk", 1, 100, 2048,
                1000, "", "", 1.0, false);
    }

    private static TracingContext upstream() {
        return new TracingContext(UPSTREAM_TRACE, "",
                "00-" + UPSTREAM_TRACE + "-" + UPSTREAM_SPAN + "-01", "u-1", "r-1");
    }

    @Test
    @DisplayName("inject：从活跃 trace + 租户上下文渲染 traceparent 与用户/会话标签")
    void injectCarriesTraceparentAndLabels() {
        TenantContext.set(42L, TenantContext.webUserPrincipal("u-9"), "owner", false, "u-9", false);
        TenantContext.setRequestId("req-7");

        Trace trace = manager.startTrace(LangfuseManager.TraceOptions.of("http"));
        TracingContext tc = LangfuseTracing.inject();

        assertEquals(trace.getId(), tc.traceId());
        assertEquals("u-9", tc.userId());
        assertEquals("req-7", tc.sessionId());
        assertTrue(tc.traceparent().startsWith("00-" + trace.getId() + "-"), tc.traceparent());
        assertTrue(tc.traceparent().endsWith("-01"));
        assertEquals(tc.traceparent(), LangfuseTracing.traceparentFromContext());
        assertEquals(trace.getId(), LangfuseTracing.currentTraceId());

        trace.finish(null, null);
    }

    @Test
    @DisplayName("inject：未启用 → 空载体（照 Go 的零值语义）")
    void injectDisabledIsEmpty() {
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        assertEquals(TracingContext.EMPTY, LangfuseTracing.inject());
        assertEquals("", LangfuseTracing.traceparentFromContext());
        assertEquals("", LangfuseTracing.currentTraceId());
        assertFalse(LangfuseTracing.extract(upstream()));
    }

    @Test
    @DisplayName("extract：续接上游 trace（子 span 同 trace、父为上游 span，无独立根）")
    void extractResumesUpstreamTrace() {
        assertTrue(LangfuseTracing.extract(upstream()));

        Span span = manager.startSpan(LangfuseManager.SpanOptions.of("worker.step"));
        span.finish(null, null, null);

        assertEquals(1, exported.size());
        RecordedSpan recorded = exported.get(0);
        assertEquals(UPSTREAM_TRACE, recorded.traceIdHex);
        assertEquals(UPSTREAM_SPAN, recorded.parentSpanIdHex);
    }

    @Test
    @DisplayName("attachTraceparent：已有本地 trace 时不动；无 trace 时续接；非法输入忽略")
    void attachTraceparentSemantics() {
        Trace local = manager.startTrace(LangfuseManager.TraceOptions.of("local"));
        LangfuseTracing.attachTraceparent("00-" + UPSTREAM_TRACE + "-" + UPSTREAM_SPAN + "-01");
        assertEquals(local.getId(), LangfuseTracing.currentTraceId());
        local.finish(null, null);
        exported.clear();
        LangfuseContext.clear();

        LangfuseTracing.attachTraceparent("garbage");
        assertEquals("", LangfuseTracing.currentTraceId());

        LangfuseTracing.attachTraceparent("00-" + UPSTREAM_TRACE + "-" + UPSTREAM_SPAN + "-01");
        assertEquals(UPSTREAM_TRACE, LangfuseTracing.currentTraceId());
    }

    @Test
    @DisplayName("任务作用域：有上游 → 续接不生根；无上游 → 开独立根并收 outcome")
    void taskScopeResumesOrRoots() {
        LangfuseTaskScope resumed = LangfuseTaskScope.start("document:process", upstream(),
                Map.of("knowledge_id", "k-1"), Map.of("knowledge_id", "k-1"));
        resumed.finish("success", null);
        assertEquals(1, exported.size());
        RecordedSpan asynqSpan = exported.get(0);
        assertEquals("asynq.document:process", asynqSpan.name);
        assertEquals(UPSTREAM_TRACE, asynqSpan.traceIdHex);
        assertEquals(UPSTREAM_SPAN, asynqSpan.parentSpanIdHex);
        assertEquals("{\"outcome\":\"success\"}", asynqSpan.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertEquals("document:process", jsonField(asynqSpan.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA), "task_type"));

        exported.clear();
        LangfuseContext.clear();
        LangfuseTaskScope standalone = LangfuseTaskScope.start("memory:extract",
                TracingContext.EMPTY, Map.of("subject_id", "s-1"), null);
        standalone.finish("error", "boom");

        assertEquals(2, exported.size());
        RecordedSpan child = exported.get(0);
        RecordedSpan root = exported.get(1);
        assertEquals("asynq.memory:extract", root.name);
        assertEquals("trace", root.attributes.get(LangfuseAttributes.ATTR_OBS_TYPE));
        assertEquals(root.spanIdHex, child.parentSpanIdHex);
        assertEquals("boom", child.statusMessage);
        // 根是 trace 观测：output 走 langfuse.trace.output（非 observation.output）
        assertEquals("{\"outcome\":\"error\"}",
                root.attributes.get(LangfuseAttributes.ATTR_TRACE_OUTPUT));
        // 根是 trace 观测：metadata 走 langfuse.trace.metadata
        assertEquals("memory:extract", jsonField(
                root.attributes.get(LangfuseAttributes.ATTR_TRACE_METADATA), "task_type"));
    }

    @Test
    @DisplayName("任务作用域：close 幂等 + 清本线程上下文；payload 预览 ≤1KB 原文、超长给 preview+bytes")
    void taskScopeCloseAndPreview() {
        LangfuseTaskScope scope = LangfuseTaskScope.start("wiki:ingest",
                TracingContext.EMPTY, null, null);
        scope.close();
        scope.finish("error", "late");
        assertEquals(2, exported.size());
        assertEquals("{\"outcome\":\"success\"}",
                exported.get(0).attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertNull(LangfuseContext.current());

        assertEquals(null, LangfuseTaskScope.previewPayload(null));
        assertEquals("tiny", LangfuseTaskScope.previewPayload("tiny"));
        String big = "x".repeat(2000);
        Object preview = LangfuseTaskScope.previewPayload(big);
        assertTrue(preview instanceof Map);
        assertEquals(2000, ((Map<?, ?>) preview).get("bytes"));
        assertTrue(((Map<?, ?>) preview).get("preview").toString().length() > 1024);
    }

    @Test
    @DisplayName("载体 JSON：空值整键省略、非空按 lf_* 键名、未知键可忽略")
    void tracingContextJsonShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("{}", mapper.writeValueAsString(TracingContext.EMPTY));
        assertEquals("{\"lf_trace_id\":\"t\",\"lf_traceparent\":\"00-t-s-01\"}",
                mapper.writeValueAsString(new TracingContext("t", "", "00-t-s-01", "", "")));

        TracingContext parsed = mapper.readValue(
                "{\"lf_traceparent\":\"00-t-s-01\",\"lf_user_id\":\"u\",\"unknown\":1}",
                TracingContext.class);
        assertEquals("00-t-s-01", parsed.traceparent());
        assertEquals("u", parsed.userId());
        assertEquals("", parsed.traceId());
    }

    @Test
    @DisplayName("MemoryTrace 转真：span 与 summarize 产出真的上报（未启用时零成本）")
    void memoryTraceRoutesToManager() {
        MemoryTrace.Span span = MemoryTrace.start("memory.recall",
                Map.of("subject_id", "u-1", "disabled", false));
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("recalled_items", null);
        span.finish(output, Map.of("count", 0), null);

        assertEquals(2, exported.size()); // autoTrace 根 + span
        RecordedSpan recorded = exported.get(0);
        assertEquals("memory.recall", recorded.name);
        assertEquals("{\"count\":0}", recorded.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));
        assertEquals("{\"recalled_items\":null}", recorded.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));

        exported.clear();
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        MemoryTrace.start("memory.recall", null).finish(null, null, new IllegalStateException("x"));
        assertTrue(exported.isEmpty());
        assertNotNull(LangfuseTaskScope.noop());
    }

    private static String jsonField(String json, String key) {
        int idx = json == null ? -1 : json.indexOf("\"" + key + "\":\"");
        if (idx < 0) {
            return null;
        }
        int start = json.indexOf('"', idx + key.length() + 3) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
