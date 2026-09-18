package com.ragagent.session.sse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.stream.StreamJson;

/**
 * 把一个 {@link StreamResponse} 写成 gin 那条 SSE 帧——**逐字节**。
 *
 * <h2>为什么不能直接用 Spring 的 {@code SseEmitter}</h2>
 * <p>{@code SseEmitter} 自己拼帧，格式与 gin 不同（它在冒号后加空格、不做 HTML 转义）。
 * 本项目要求响应逐字节一致，所以帧由本类手工拼。</p>
 *
 * <h2>gin 的帧长什么样</h2>
 * <p>{@code c.SSEvent("message", resp)} 走的是 {@code gin-contrib/sse} 的
 * {@code Encode}，对 {@code StreamResponse}（指针 → 结构体）这一支：</p>
 * <pre>
 *   writeEvent(w, "message") → "event:message\n"        ← 冒号后**没有空格**
 *   writeData(w, resp):
 *       w.WriteString("data:")
 *       json.NewEncoder(w).Encode(resp)                 ← JSON + '\n'
 *       w.WriteString("\n")
 * </pre>
 * <p>合起来就是 {@code event:message\ndata:<json>\n\n}。</p>
 *
 * <h2>⚠️ 两个只看 Java 直觉会写错的地方</h2>
 * <ol>
 *   <li><b>JSON 用 Go 的转义规则</b>：gin 用 {@code json.NewEncoder}，它默认开
 *       HTML 转义——{@code < > &} 会被写成 {@code \u003c} / {@code \u003e} / {@code \u0026}（小写十六进制）。
 *       Spring 那个 mapper 不转义，直接用就会在含 {@code &} 的正文上分叉。
 *       所以这里复用 {@link StreamJson#mapper()}：它已经把「HTML 转义 + map 按键排序 +
 *       时间用本地时区 RFC3339Nano」配齐了——那份配置本来就是"Go 兼容 JSON"，
 *       Redis 与 SSE 两条路径共用同一份定义。</li>
 *   <li><b>Content-Type 会被覆盖</b>：{@code sse.Event.Render} 里的
 *       {@code WriteContentType} 会<b>无条件</b>把 Content-Type 改写成
 *       {@code text/event-stream;charset=utf-8}——即 {@code setSSEHeaders} 设的
 *       {@code text/event-stream} <b>不是</b>线上的最终值。
 *       本类照做（{@link #applyRenderedContentType}），否则头就不一致了。
 *       注意 {@code Cache-Control} 是"没有才设"，所以 {@code no-cache} 保持不变。</li>
 * </ol>
 */
public final class SseFrameWriter {

    /**
     * gin 的 SSE renderer 最终写出的 Content-Type
     * （{@code gin-contrib/sse.WriteContentType} 无条件覆盖）。
     */
    public static final String RENDERED_CONTENT_TYPE = "text/event-stream;charset=utf-8";

    /** 事件名固定为 {@code message}（gin 的 {@code c.SSEvent("message", …)}）。 */
    public static final String EVENT_NAME = "message";

    private SseFrameWriter() {
    }

    /**
     * 对照 gin 的 {@code WriteContentType}：把 Content-Type 覆盖成渲染器的值。
     *
     * <p>必须在**任何正文写出之前**调用。</p>
     */
    public static void applyRenderedContentType(HttpServletResponse response) {
        response.setHeader("Content-Type", RENDERED_CONTENT_TYPE);
    }

    /**
     * 写一帧并 flush（对照 {@code c.SSEvent("message", response)} + {@code c.Writer.Flush()}）。
     *
     * <p>序列化用的是 {@link StreamJson#mapper()}，理由见类注释。序列化失败按 Go 的
     * {@code json.Encoder} 行为是写一个零长度输出并返回错误——这里直接抛出，
     * 由调用方按"写失败"处理（关流）。</p>
     */
    public static void write(HttpServletResponse response, StreamResponse payload) throws IOException {
        String json;
        try {
            json = StreamJson.mapper().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IOException("failed to marshal stream response: " + e.getMessage(), e);
        }

        StringBuilder frame = new StringBuilder(json.length() + 32);
        frame.append("event:").append(EVENT_NAME).append('\n');
        frame.append("data:").append(json).append('\n').append('\n');

        response.getOutputStream().write(frame.toString().getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }
}
