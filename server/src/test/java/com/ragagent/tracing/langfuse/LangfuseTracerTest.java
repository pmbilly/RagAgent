package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ragagent.tracing.langfuse.LangfuseManager.GenerationOptions;
import com.ragagent.tracing.langfuse.LangfuseManager.SpanOptions;
import com.ragagent.tracing.langfuse.LangfuseManager.TraceOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 观测生命周期单测（对照 Go tracer_test.go 的核心用例，用记录出口替代
 * in-memory exporter；FlushAt=1 → 每次 Finish 立即同步导出，等价 Go 的
 * SimpleSpanProcessor 测试模式）。
 */
class LangfuseTracerTest {

    private final List<RecordedSpan> exported = new CopyOnWriteArrayList<>();

    private DefaultLangfuseManager manager;

    @BeforeEach
    void setUp() {
        exported.clear();
        LangfuseContext.clear();
        manager = new DefaultLangfuseManager(config(), exported::addAll);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        LangfuseContext.clear();
    }

    private static LangfuseConfig config() {
        return new LangfuseConfig(true, "http://localhost:1", "pk", "sk", 1, 100, 2048,
                1000, "v0.8.0", "dev", 1.0, false);
    }

    private static String type(RecordedSpan span) {
        return span.attributes.get(LangfuseAttributes.ATTR_OBS_TYPE);
    }

    @Test
    @DisplayName("trace→span→generation：同一 trace、父链正确、obs 类型与模型名落属性")
    void parentChainAndAttributes() {
        Trace trace = manager.startTrace(new TraceOptions("chat turn", "u-1", "s-1", null,
                Map.of("request_id", "r-1"), null, null, null));
        Span span = manager.startSpan(SpanOptions.of("rerank"));
        Generation gen = manager.startGeneration(GenerationOptions.of("chat.completion", "qwen"));

        gen.finish(Map.of("content", "hi"), TokenUsage.of(10, 5, 15), null);
        span.finish(Map.of("kept", 1), null, null);
        trace.finish(null, null);

        // Finish 顺序 = 子先父后（FlushAt=1 每次 Finish 即导出）
        assertEquals(3, exported.size());
        RecordedSpan chat = exported.get(0);
        RecordedSpan rerank = exported.get(1);
        RecordedSpan root = exported.get(2);

        assertEquals(root.traceIdHex, rerank.traceIdHex);
        assertEquals(root.traceIdHex, chat.traceIdHex);
        assertNull(root.parentSpanIdHex);
        assertEquals(root.spanIdHex, rerank.parentSpanIdHex);
        assertEquals(rerank.spanIdHex, chat.parentSpanIdHex);

        assertEquals("trace", type(root));
        assertEquals("span", type(rerank));
        assertEquals("generation", type(chat));
        assertEquals("chat turn", root.attributes.get(LangfuseAttributes.ATTR_TRACE_NAME));
        assertEquals("u-1", root.attributes.get(LangfuseAttributes.ATTR_USER_ID));
        assertEquals("s-1", root.attributes.get(LangfuseAttributes.ATTR_SESSION_ID));
        assertEquals("dev", root.attributes.get(LangfuseAttributes.ATTR_ENVIRONMENT));
        assertEquals("v0.8.0", root.attributes.get(LangfuseAttributes.ATTR_RELEASE));
        assertEquals("{\"requestId\":\"r-1\"}",
                root.attributes.get(LangfuseAttributes.ATTR_TRACE_METADATA));

        assertEquals("qwen", chat.attributes.get(LangfuseAttributes.ATTR_OBS_MODEL));
        assertEquals("{\"content\":\"hi\"}", chat.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertEquals("{\"input\":10,\"output\":5,\"total\":15}",
                chat.attributes.get(LangfuseAttributes.ATTR_OBS_USAGE_DETAILS));
        assertEquals("{\"kept\":1}", rerank.attributes.get(LangfuseAttributes.ATTR_OBS_OUTPUT));

        assertTrue(chat.startNanos > 0 && chat.endNanos >= chat.startNanos);
        assertNotEquals("", trace.getId());
        assertEquals(trace.getId(), root.traceIdHex);
    }

    @Test
    @DisplayName("metadata 合并：finish 覆盖同名键、保留开启期键")
    void metadataMergeFinishWins() {
        Span span = manager.startSpan(new SpanOptions("op", null, Map.of("a", 1, "b", "start")));
        span.finish(null, Map.of("b", "finish", "c", true), null);

        RecordedSpan recorded = exported.get(0);
        assertEquals("{\"a\":1,\"b\":\"finish\",\"c\":true}",
                recorded.attributes.get(LangfuseAttributes.ATTR_OBS_METADATA));
    }

    @Test
    @DisplayName("错误：非空 err → Status(ERROR) + exception 事件")
    void errorMarksStatusAndEvent() {
        Span span = manager.startSpan(SpanOptions.of("op"));
        span.finish(null, null, "boom");

        RecordedSpan recorded = exported.get(0);
        assertEquals("boom", recorded.statusMessage);
        assertEquals("boom", recorded.exceptionMessage);
    }

    @Test
    @DisplayName("autoTrace：无活跃 trace 时 startSpan 自动开根，Finish 连根一起导出")
    void autoTraceWrapsOrphanSpan() {
        Span span = manager.startSpan(SpanOptions.of("orphan"));
        span.finish(null, null, null);

        // span 先 Finish（含 autoTrace 的根收尾），导出序 = [child, root]
        assertEquals(2, exported.size());
        RecordedSpan child = exported.get(0);
        RecordedSpan root = exported.get(1);
        assertEquals("trace", type(root));
        assertEquals("span", type(child));
        assertEquals(root.traceIdHex, child.traceIdHex);
        assertEquals(root.spanIdHex, child.parentSpanIdHex);
    }

    @Test
    @DisplayName("startChildSpan：无活跃 trace → no-op；有 trace → 记录子 span")
    void childSpanRequiresTrace() {
        Span none = manager.startChildSpan(SpanOptions.of("poll"));
        assertEquals("", none.getId());
        assertTrue(exported.isEmpty());

        Trace trace = manager.startTrace(TraceOptions.of("t"));
        Span child = manager.startChildSpan(SpanOptions.of("poll"));
        assertFalse(child.getId().isEmpty());
        child.finish(null, null, null);
        trace.finish(null, null);

        // 导出序 = [child, root]；child.parent == root.spanId
        assertEquals(2, exported.size());
        RecordedSpan childRecorded = exported.get(0);
        RecordedSpan rootRecorded = exported.get(1);
        assertEquals("trace", type(rootRecorded));
        assertEquals(rootRecorded.spanIdHex, childRecorded.parentSpanIdHex);
    }

    @Test
    @DisplayName("resumeTrace：合法 32-hex 继承 trace id；非法 → null")
    void resumeTraceInheritsId() {
        assertNull(manager.resumeTrace("not-a-hex-id", null));
        assertNull(manager.resumeTrace("", null));

        String traceId = "0af7651916cd43dd8448eb211c80319c";
        Trace resumed = manager.resumeTrace(traceId, null);
        assertEquals(traceId, resumed.getId());

        Span span = manager.startSpan(SpanOptions.of("child"));
        span.finish(null, null, null);
        resumed.finish(null, null); // resume 句柄无自有 span → no-op

        assertEquals(1, exported.size());
        assertEquals(traceId, exported.get(0).traceIdHex);
        assertEquals("", exported.get(0).parentSpanIdHex == null ? "" : exported.get(0).parentSpanIdHex);
    }

    @Test
    @DisplayName("未启用/关闭：句柄恒 no-op、不导出")
    void disabledIsNoop() {
        NoopLangfuseManager noop = NoopLangfuseManager.INSTANCE;
        assertFalse(noop.enabled());
        assertEquals("", noop.startSpan(SpanOptions.of("x")).getId());
        noop.startSpan(SpanOptions.of("x")).finish(null, null, null);
        assertEquals("", noop.startTrace(TraceOptions.of("x")).getId());
        assertEquals("", noop.startGeneration(GenerationOptions.of("x", "m")).getId());
        assertTrue(exported.isEmpty());

        manager.shutdown();
        Span afterClose = manager.startSpan(SpanOptions.of("after"));
        afterClose.finish(null, null, null);
        assertTrue(exported.isEmpty());
    }
}
