package com.ragagent.tracing.langfuse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ragagent.common.context.TenantContext;
import com.ragagent.tracing.langfuse.LangfuseManager.TraceOptions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 请求级 trace 的 HTTP 中间件（对照 Go internal/tracing/langfuse/middleware.go 全文）：
 * 命中 {@link #shouldTrace} 的路径才开 trace（在线推理/摄取/批处理/诊断/评估），
 * 从 {@code traceparent} 继承上游 trace id（sop3 关联），handler 链返回后自动收尾。
 *
 * <p><b>与 Go 的两处实现差异（备案）</b>：
 * ① trace 名里的路径用 Spring 的路由模式（{@code /api/v1/kb/{id}}），Go 是 Gin 的
 * {@code :id} 形式——同一个网络语义、字符串形态不同；
 * ② {@code response.size} 取 Content-Length 头（未设置 → -1，对照 Go Writer.Size 的
 * -1 语义；SSE/分块响应在 Go 侧拿得到实际字节数，Java 拦截器拿不到）。</p>
 *
 * <p>注册顺序对照 Go 链序（Auth → langfuse → Audit）：排在 RBAC/API-Key 门禁之后
 * （见 WebConfig），被门禁拒绝的请求不产生 trace。</p>
 */
public class LangfuseHttpInterceptor implements HandlerInterceptor {

    private static final String ACTIVE_TRACE_ATTR =
            LangfuseHttpInterceptor.class.getName() + ".activeTrace";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                            Object handler) {
        LangfuseManager manager = LangfuseManager.get();
        if (!manager.enabled()) {
            return true;
        }
        String pattern = patternOf(request);
        if (!shouldTrace(pattern, request.getMethod())) {
            return true;
        }

        // 对照 propagator.Extract：traceparent 合法时先立远端帧（resume），
        // 随后 startTrace 的根 span 继承上游 trace id 并挂到上游 span 下。
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            String[] ids = parseTraceparent(traceparent);
            if (ids != null) {
                manager.resumeTrace(ids[0], ids[1]);
            }
        }

        Trace trace = manager.startTrace(buildOptions(request, pattern));
        request.setAttribute(ACTIVE_TRACE_ATTR, trace);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        Object active = request.getAttribute(ACTIVE_TRACE_ATTR);
        try {
            if (active instanceof Trace trace) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("status", response.getStatus());
                output.put("response.size", responseSize(response));
                trace.finish(output, null);
            }
        } finally {
            // §5：请求线程归还容器前清空观测上下文（Tomcat 线程复用）
            LangfuseContext.clear();
        }
    }

    /** 路由模式（Spring 的 HandlerMapping 属性）；无模式（静态资源/未匹配）→ 原始 URI。 */
    static String patternOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isEmpty()) {
            return s;
        }
        return request.getRequestURI() == null ? "" : request.getRequestURI();
    }

    private static TraceOptions buildOptions(HttpServletRequest request, String pattern) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("http.method", request.getMethod());
        metadata.put("http.path", pattern);
        metadata.put("http.query", request.getQueryString() == null ? "" : request.getQueryString());
        String requestId = TenantContext.currentRequestId();
        if (requestId != null && !requestId.isEmpty()) {
            metadata.put("request_id", requestId);
        }
        String method = request.getMethod() == null ? "" : request.getMethod();
        List<String> tags = new ArrayList<>(2);
        tags.add("http");
        tags.add(method.toLowerCase(Locale.ROOT));

        return new TraceOptions(method + " " + pattern, extractUserId(), extractSessionId(request, pattern),
                null, metadata, tags, null, null);
    }

    /** 对照 extractUserID：用户 id → "tenant:<id>" → ""。 */
    private static String extractUserId() {
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

    /** 对照 extractSessionID：session_id 路径参数 → （sessions 路由下的）id 路径参数 → ""。 */
    @SuppressWarnings("unchecked")
    private static String extractSessionId(HttpServletRequest request, String pattern) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) {
            return "";
        }
        Object sessionId = map.get("session_id");
        if (sessionId instanceof String s && !s.isEmpty()) {
            return s;
        }
        Object id = map.get("id");
        if (id instanceof String s && !s.isEmpty() && pattern.contains("/sessions/")) {
            return s;
        }
        return "";
    }

    /** 对照 response.size 的取值（Content-Length 头；缺失 -1）。 */
    private static int responseSize(HttpServletResponse response) {
        String contentLength = response.getHeader("Content-Length");
        if (contentLength == null || contentLength.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(contentLength);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 解析 W3C traceparent（{@code 00-<32hex>-<16hex>-<2hex>}）。
     * 非法/全零/大写 → null（对照 Go TraceContext propagator 的严格解析）。
     */
    static String[] parseTraceparent(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-", -1);
        if (parts.length < 4) {
            return null;
        }
        String version = parts[0];
        if (version.length() != 2 || "ff".equalsIgnoreCase(version) || !isLowerHex(version)) {
            return null;
        }
        String traceId = parts[1];
        String spanId = parts[2];
        if (traceId.length() != 32 || !isLowerHex(traceId) || isAllZero(traceId)) {
            return null;
        }
        if (spanId.length() != 16 || !isLowerHex(spanId) || isAllZero(spanId)) {
            return null;
        }
        String flags = parts[3];
        if (flags.length() != 2 || !isLowerHex(flags)) {
            return null;
        }
        return new String[] {traceId, spanId};
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * 对照 shouldTrace：只跟踪会引发 LLM 工作的端点（在线推理/摄取/批处理/诊断/评估），
     * 只读列表与静态资源不跟踪。路径为 Spring 路由模式形态（见类注释差异 ①）。
     */
    static boolean shouldTrace(String path, String method) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String m = method == null ? "" : method;
        // 在线推理
        if (path.startsWith("/api/v1/knowledge-chat")
                || path.startsWith("/api/v1/agent-chat")
                || path.startsWith("/api/v1/knowledge-search")
                || (path.startsWith("/api/v1/sessions") && path.contains("generate_title"))
                || path.startsWith("/api/v1/initialization/remote/check")
                || path.startsWith("/api/v1/initialization/embedding/test")
                || path.startsWith("/api/v1/initialization/rerank/check")
                || path.startsWith("/api/v1/initialization/asr/check")
                || path.startsWith("/api/v1/initialization/multimodal/test")
                || path.startsWith("/api/v1/initialization/extract/")
                || path.startsWith("/api/v1/evaluation")) {
            return true;
        }
        // 摄取（会入队 LLM 异步工作的 POST/PUT）
        if ("POST".equals(m) || "PUT".equals(m)) {
            if (path.contains("/knowledge-bases/") && path.contains("/knowledge/")) {
                return true;
            }
            if (path.startsWith("/api/v1/knowledge/")
                    && (path.endsWith("/reparse") || path.endsWith("/move")
                            || path.contains("/manual/"))) {
                return true;
            }
            if ("/api/v1/knowledge-bases/copy".equals(path)) {
                return true;
            }
            if (path.contains("/faq/entries") || path.contains("/faq/entry")
                    || path.contains("/faq/import")) {
                return true;
            }
            if (path.contains("/wiki/auto-fix") || path.contains("/wiki/rebuild-links")) {
                return true;
            }
            if (path.startsWith("/api/v1/chunks/") && "PUT".equals(m)) {
                return true;
            }
            if (path.contains("/datasource/") && path.endsWith("/sync")) {
                return true;
            }
        }
        return false;
    }
}
