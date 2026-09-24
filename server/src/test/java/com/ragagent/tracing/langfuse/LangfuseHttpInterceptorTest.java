package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP 中间件测试（对照 Go middleware.go 的 shouldTrace 规则、traceparent 继承
 * 与请求级 trace 生命周期；导出用记录出口观察）。
 */
class LangfuseHttpInterceptorTest {

    private final List<RecordedSpan> exported = new CopyOnWriteArrayList<>();
    private final LangfuseHttpInterceptor interceptor = new LangfuseHttpInterceptor();

    private DefaultLangfuseManager manager;

    @BeforeEach
    void setUp() {
        exported.clear();
        LangfuseContext.clear();
        manager = new DefaultLangfuseManager(config(), exported::addAll);
        LangfuseRegistry.installForTest(manager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        LangfuseRegistry.installForTest(NoopLangfuseManager.INSTANCE);
        LangfuseContext.clear();
    }

    private static LangfuseConfig config() {
        return new LangfuseConfig(true, "http://localhost:1", "pk", "sk", 1, 100, 2048,
                1000, "", "", 1.0, false);
    }

    @Test
    @DisplayName("shouldTrace：在线推理/摄取/批处理/诊断/评估命中，只读列表不命中")
    void shouldTraceMatchesGoRules() {
        // 在线推理
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/knowledge-chat", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/agent-chat/{session_id}", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/knowledge-search", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace(
                "/api/v1/sessions/{id}/generate_title", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/evaluation", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace(
                "/api/v1/initialization/remote/check", "POST"));
        // 摄取（POST/PUT）
        assertTrue(LangfuseHttpInterceptor.shouldTrace(
                "/api/v1/knowledge-bases/{id}/knowledge/file", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/knowledge/{id}/reparse", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/knowledge-bases/copy", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/faq/import", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace(
                "/api/v1/knowledge-bases/{id}/wiki/auto-fix", "POST"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/chunks/{id}", "PUT"));
        assertTrue(LangfuseHttpInterceptor.shouldTrace("/api/v1/datasource/{id}/sync", "POST"));
        // 不命中：只读、GET 摄取、空路径、健康检查
        assertFalse(LangfuseHttpInterceptor.shouldTrace("/api/v1/knowledge-bases", "GET"));
        assertFalse(LangfuseHttpInterceptor.shouldTrace(
                "/api/v1/knowledge-bases/{id}/knowledge/{kid}", "GET"));
        assertFalse(LangfuseHttpInterceptor.shouldTrace("/api/v1/chunks/{id}", "GET"));
        assertFalse(LangfuseHttpInterceptor.shouldTrace("/api/v1/health", "GET"));
        assertFalse(LangfuseHttpInterceptor.shouldTrace("", "POST"));
        assertFalse(LangfuseHttpInterceptor.shouldTrace(null, "POST"));
    }

    @Test
    @DisplayName("traceparent：合法 00 格式解析；缺字段/全零/大写 → null")
    void traceparentParsing() {
        String[] ok = LangfuseHttpInterceptor.parseTraceparent(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertNotNull(ok);
        assertEquals("0af7651916cd43dd8448eb211c80319c", ok[0]);
        assertEquals("b7ad6b7169203331", ok[1]);

        assertNull(LangfuseHttpInterceptor.parseTraceparent(null));
        assertNull(LangfuseHttpInterceptor.parseTraceparent(""));
        assertNull(LangfuseHttpInterceptor.parseTraceparent(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331"));
        assertNull(LangfuseHttpInterceptor.parseTraceparent(
                "00-00000000000000000000000000000000-b7ad6b7169203331-01"));
        assertNull(LangfuseHttpInterceptor.parseTraceparent(
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"));
        assertNull(LangfuseHttpInterceptor.parseTraceparent(
                "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01"));
        assertNull(LangfuseHttpInterceptor.parseTraceparent("zz-abc-def-01"));
    }

    @Test
    @DisplayName("生命周期：命中路径开 trace → 收尾导出 output(status/size) + 清上下文")
    void startsAndFinishesTrace() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/evaluation");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/evaluation");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNotNull(LangfuseContext.current());

        response.setStatus(200);
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, exported.size());
        RecordedSpan root = exported.get(0);
        assertEquals("trace", root.attributes.get(LangfuseAttributes.ATTR_OBS_TYPE));
        assertEquals("POST /api/v1/evaluation", root.attributes.get(LangfuseAttributes.ATTR_TRACE_NAME));
        assertEquals("{\"http.method\":\"POST\",\"http.path\":\"/api/v1/evaluation\",\"http.query\":\"\"}",
                root.attributes.get(LangfuseAttributes.ATTR_TRACE_METADATA));
        assertEquals("{\"response.size\":-1,\"status\":200}",
                root.attributes.get(LangfuseAttributes.ATTR_TRACE_OUTPUT));
        assertEquals("[\"http\",\"post\"]", root.attributes.get("langfuse.trace.tags"));
        assertNull(LangfuseContext.current());
    }

    @Test
    @DisplayName("traceparent 继承：根 span 继承上游 trace id 并挂到上游 span 下")
    void inheritsUpstreamTraceparent() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/evaluation");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/evaluation");
        request.addHeader("traceparent",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, exported.size());
        RecordedSpan root = exported.get(0);
        assertEquals("0af7651916cd43dd8448eb211c80319c", root.traceIdHex);
        assertEquals("b7ad6b7169203331", root.parentSpanIdHex);
    }

    @Test
    @DisplayName("不命中路径：不开 trace、不导出、上下文不残留")
    void untracedPathIsNoop() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledge-bases");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/knowledge-bases");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(LangfuseContext.current());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertTrue(exported.isEmpty());
    }
}
