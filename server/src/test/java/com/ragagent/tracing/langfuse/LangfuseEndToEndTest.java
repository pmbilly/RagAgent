package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.ragagent.common.context.TenantContext;
import com.ragagent.common.context.TracingContext;
import com.ragagent.memory.service.MemoryTrace;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C-5 端到端验收：真 HTTP POST → 本地 stub OTLP 收集器（解码 protobuf），
 * 走通「HTTP 根 trace → 入队注入 → worker 线程续接 → 任务 span → 子观测」全缝。
 *
 * <p>覆盖点：端点路径、Basic 认证、Langfuse 摄入头、resource（service.name /
 * langfuse.public.key）、scope（langfuse-sdk / public_key）、以及 <b>跨线程父子关系
 * 真的落在同一棵树</b>（asynq span 的 trace id = HTTP 根，父 span id = 根 span id）。</p>
 */
class LangfuseEndToEndTest {

    private record Received(String path, String auth, String ingestionVersion,
                            String sdkName, ExportTraceServiceRequest body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private DefaultLangfuseManager manager;

    @BeforeEach
    void setUp() throws Exception {
        received.clear();
        LangfuseContext.clear();
        TenantContext.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"),
                    exchange.getRequestHeaders().getFirst("x-langfuse-sdk-name"),
                    ExportTraceServiceRequest.parseFrom(body)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        LangfuseContext.clear();
        TenantContext.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("真 HTTP 上报：端点/认证/头 + resource/scope + 跨线程续接成一棵树")
    void exportsOverRealHttpAndStitchesAsyncWork() throws Exception {
        int port = server.getAddress().getPort();
        // flushAt=1 → 每个 span 立即触发一批，测试只需轮询收集器（无需等 3s 定时器）
        LangfuseConfig cfg = new LangfuseConfig(true, "http://127.0.0.1:" + port,
                "pk-e2e", "sk-e2e", 1, 100, 64, 3000, "v1.2.3", "e2e", 1.0, false);
        manager = new DefaultLangfuseManager(cfg);
        LangfuseRegistry.installForTest(manager);

        TenantContext.set(7L, TenantContext.webUserPrincipal("u-7"), "owner", false, "u-7", false);
        TenantContext.setRequestId("req-e2e");

        // ① HTTP 侧根 trace（模拟 GinMiddleware/拦截器开的那条）
        Trace root = manager.startTrace(LangfuseManager.TraceOptions.of("http.request"));
        String rootTraceId = root.getId();

        // ② 入队注入 → ③ worker 线程续接（新线程 = 无线程局部上下文）
        TracingContext tracing = LangfuseTracing.inject();
        assertEquals(rootTraceId, tracing.traceId());
        String rootSpanId = tracing.traceparent().split("-")[2];
        assertNotNull(rootSpanId);
        Thread worker = new Thread(() -> {
            LangfuseContext.clear();
            try (LangfuseTaskScope scope = LangfuseTaskScope.start("document:process", tracing,
                    Map.of("knowledge_id", "k-e2e"), Map.of("knowledge_id", "k-e2e"))) {
                MemoryTrace.start("memory.recall", Map.of("subject_id", "u-7"))
                        .finish(Map.of("recalled_items", 1), Map.of("count", 1), null);
            }
        }, "worker-e2e");
        worker.start();
        worker.join();

        root.finish(Map.of("status", 200), null);

        // ④ 收集器侧断言（三个 span 分属多批）
        List<io.opentelemetry.proto.trace.v1.Span> spans = awaitSpans(3);
        Received first = received.get(0);
        assertEquals("/api/public/otel/v1/traces", first.path());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "pk-e2e:sk-e2e".getBytes(StandardCharsets.UTF_8)), first.auth());
        assertEquals("4", first.ingestionVersion());
        assertEquals("python", first.sdkName());

        ResourceSpans rs = first.body().getResourceSpans(0);
        assertEquals("weknora", resourceAttr(rs, "service.name"));
        assertEquals("pk-e2e", resourceAttr(rs, "langfuse.public.key"));
        assertEquals("e2e", resourceAttr(rs, "langfuse.environment"));
        assertEquals("v1.2.3", resourceAttr(rs, "langfuse.release"));
        ScopeSpans ss = rs.getScopeSpans(0);
        assertEquals("langfuse-sdk", ss.getScope().getName());
        assertEquals(LangfuseAttributes.SCOPE_VERSION, ss.getScope().getVersion());
        assertEquals("pk-e2e", scopeAttr(ss, "public_key"));

        Map<String, io.opentelemetry.proto.trace.v1.Span> byName = new LinkedHashMap<>();
        for (io.opentelemetry.proto.trace.v1.Span s : spans) {
            byName.put(s.getName(), s);
        }
        io.opentelemetry.proto.trace.v1.Span rootSpan = byName.get("http.request");
        io.opentelemetry.proto.trace.v1.Span asynq = byName.get("asynq.document:process");
        io.opentelemetry.proto.trace.v1.Span recall = byName.get("memory.recall");
        assertNotNull(rootSpan);
        assertNotNull(asynq);
        assertNotNull(recall);

        // 跨线程续接：同一棵 trace，父链 HTTP 根 → asynq → memory.recall
        assertEquals(rootTraceId, hex(asynq.getTraceId()));
        assertEquals(rootSpanId, hex(asynq.getParentSpanId()));
        assertEquals(rootTraceId, hex(recall.getTraceId()));
        assertEquals(hex(asynq.getSpanId()), hex(recall.getParentSpanId()));
        assertTrue(attr(asynq, LangfuseAttributes.ATTR_OBS_METADATA).contains("k-e2e"));
        assertEquals("{\"recalled_items\":1}",
                attr(recall, LangfuseAttributes.ATTR_OBS_OUTPUT));
        assertEquals("trace", attr(byName.get("http.request"), LangfuseAttributes.ATTR_OBS_TYPE));
    }

    private List<io.opentelemetry.proto.trace.v1.Span> awaitSpans(int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            List<io.opentelemetry.proto.trace.v1.Span> all = collect();
            if (all.size() >= expected) {
                return all;
            }
            Thread.sleep(50);
        }
        return collect();
    }

    private List<io.opentelemetry.proto.trace.v1.Span> collect() {
        List<io.opentelemetry.proto.trace.v1.Span> all = new CopyOnWriteArrayList<>();
        for (Received r : received) {
            for (ResourceSpans rs : r.body().getResourceSpansList()) {
                for (ScopeSpans ss : rs.getScopeSpansList()) {
                    all.addAll(ss.getSpansList());
                }
            }
        }
        return all;
    }

    private static String resourceAttr(ResourceSpans rs, String key) {
        return firstString(rs.getResource().getAttributesList(), key);
    }

    private static String scopeAttr(ScopeSpans ss, String key) {
        return firstString(ss.getScope().getAttributesList(), key);
    }

    private static String attr(io.opentelemetry.proto.trace.v1.Span span, String key) {
        return firstString(span.getAttributesList(), key);
    }

    private static String firstString(List<KeyValue> attrs, String key) {
        for (KeyValue kv : attrs) {
            if (kv.getKey().equals(key)) {
                return kv.getValue().getStringValue();
            }
        }
        return null;
    }

    private static String hex(com.google.protobuf.ByteString bytes) {
        return java.util.HexFormat.of().formatHex(bytes.toByteArray());
    }
}
