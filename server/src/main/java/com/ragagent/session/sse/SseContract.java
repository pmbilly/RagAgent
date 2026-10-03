package com.ragagent.session.sse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE 响应的头部契约。
 *
 * <p>四个头的值必须逐字保持——前端（尤其是嵌在别家页面里的 embed widget）
 * 与中间的 Nginx 都按它们判断"这是不是一条不该被缓冲的事件流"。</p>
 *
 * <table border="1">
 *   <caption>Go {@code setSSEHeaders} 的逐条对照</caption>
 *   <tr><th>头</th><th>值</th><th>为什么</th></tr>
 *   <tr><td>{@code Content-Type}</td><td>{@code text/event-stream}</td><td>SSE 的 MIME 类型</td></tr>
 *   <tr><td>{@code Cache-Control}</td><td>{@code no-cache}</td><td>事件流不可缓存</td></tr>
 *   <tr><td>{@code Connection}</td><td>{@code keep-alive}</td><td>长连接</td></tr>
 *   <tr><td>{@code X-Accel-Buffering}</td><td>{@code no}</td><td>关掉 Nginx 的响应缓冲，否则 token 会被攒包</td></tr>
 * </table>
 *
 * <p><b>时机</b>：这些头必须在**任何**正文写出之前设置——
 * 一旦 SSE 头先发了，参数校验失败就再也退不回
 * <b>普通 400 JSON</b> 了。</p>
 */
public final class SseContract {

    public static final String CONTENT_TYPE = "text/event-stream";
    public static final String CACHE_CONTROL = "no-cache";
    public static final String CONNECTION = "keep-alive";
    public static final String X_ACCEL_BUFFERING = "no";

    private SseContract() {
    }

    /**
     * 写四个 SSE 头。
     *
     * <p>用 {@code setHeader} 而非 {@code addHeader}：覆盖语义，
     * 重复调用不应产生第二个同名头。</p>
     */
    public static void setSSEHeaders(HttpServletResponse response) {
        response.setHeader("Content-Type", CONTENT_TYPE);
        response.setHeader("Cache-Control", CACHE_CONTROL);
        response.setHeader("Connection", CONNECTION);
        response.setHeader("X-Accel-Buffering", X_ACCEL_BUFFERING);
    }

    /**
     * **刻意的空实现**。
     *
     * <p>为什么是空操作（记录一个踩过的坑）：</p>
     * <blockquote>
     *   这现在是空操作，因为：(1) {@code handleComplete} 发出的 {@code complete} 事件
     *   已经标志流结束；(2) 额外再发一个 {@code done:true} 的空 {@code answer} 事件会
     *   引起前端问题（多个 done 事件会扰乱状态机）。
     *   前端应当用 {@code complete} 这个 response_type 判断流已结束。
     * </blockquote>
     *
 * <p>保留这个具名方法而不是删掉调用点，让调用序列保持显式——
 * 将来完成信号的语义若有变化，这里有一个明确的落点。</p>
     */
    public static void sendCompletionEvent(HttpServletResponse response, String requestId) {
        // Intentionally empty - completion is signaled by the 'complete' event
        // which is already sent before this function is called
    }
}
