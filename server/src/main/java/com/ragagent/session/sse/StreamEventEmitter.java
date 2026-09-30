package com.ragagent.session.sse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.storage.support.Rewriter;
import com.ragagent.storage.support.StreamRewriter;
import com.ragagent.stream.StreamEvent;

/**
 * 把流事件重写后发到 SSE 线缆上（对照 Go {@code internal/handler/session/resource_urls.go}
 * L56-174）。
 *
 * <h2>§6 要求的 emit 表</h2>
 * <p>本类是 SSE 响应的**唯一写出点**。Go 侧的 emit 点与 Java 的对应关系：</p>
 * <table border="1">
 *   <caption>emit 点对照</caption>
 *   <tr><th>Go</th><th>Java</th><th>说明</th></tr>
 *   <tr><td>{@code buildStreamResponseFor}</td><td>{@link #buildStreamResponseFor}</td>
 *       <td>构载荷 + 重写（引用、data、正文扣留）</td></tr>
 *   <tr><td>{@code emitStreamEvent}</td><td>{@link #emitStreamEvent}</td>
 *       <td>终止型事件先冲掉扣留尾巴，再写一帧</td></tr>
 *   <tr><td>{@code flushHeldStreamContent}</td><td>{@link #flushHeldStreamContent}</td>
 *       <td>把扣留缓冲里剩下的尾巴作为**独立事件**补发</td></tr>
 *   <tr><td>{@code c.SSEvent("message", …)}</td><td>{@link SseFrameWriter#write}</td>
 *       <td>真正的字节写出（{@code event:message\ndata:…\n\n}）</td></tr>
 * </table>
 *
 * <h2>两类事件</h2>
 * <ul>
 *   <li><b>增量型</b>（answer / thinking / reflection）——{@code Content} 是可以累加的片段，
 *       一条存储引用可能横跨两片，所以走<b>扣留缓冲</b>。</li>
 *   <li><b>终止型</b>（complete / error）——在它们之前必须把扣留的尾巴冲出来，
 *       否则客户端一旦看到完成标记就认为消息结束了，尾巴会被丢掉。</li>
 * </ul>
 */
@Component
public class StreamEventEmitter {

    /** 对照 Go {@code deltaResponseTypes}：Content 是可累加片段的类型。 */
    private static final Set<ResponseType> DELTA_RESPONSE_TYPES = Set.of(
            ResponseType.ANSWER, ResponseType.THINKING, ResponseType.REFLECTION);

    /**
     * 对照 Go {@code terminalResponseTypes}：对客户端而言结束本条消息的类型。
     * 错误可能是一次运行的最后一条事件，其后未必跟完成事件；冲一个本来就空的缓冲是空操作，
     * 所以两者都覆盖是安全的。
     */
    private static final Set<ResponseType> TERMINAL_RESPONSE_TYPES = Set.of(
            ResponseType.COMPLETE, ResponseType.ERROR);

    /** 对照 Go {@code holdbackKey} 的分隔符（NUL，正文里不可能出现）。 */
    private static final char HOLDBACK_KEY_SEPARATOR = '\0';

    private final SseFrameWriter frameWriter;

    public StreamEventEmitter(SseFrameWriter frameWriter) {
        this.frameWriter = frameWriter;
    }

    /** 对照 Go {@code holdbackKey}：一个增量流的身份。 */
    static String holdbackKey(ResponseType responseType, String eventId) {
        return String.valueOf(responseType == null ? "" : responseType.value())
                + HOLDBACK_KEY_SEPARATOR + eventId;
    }

    /** 对照 Go {@code parseHoldbackKey}：切回 (类型, 事件 id)。 */
    static ParsedHoldbackKey parseHoldbackKey(String key) {
        int separator = key.indexOf(HOLDBACK_KEY_SEPARATOR);
        String type = separator < 0 ? key : key.substring(0, separator);
        String eventId = separator < 0 ? "" : key.substring(separator + 1);
        return new ParsedHoldbackKey(ResponseType.fromValue(type), eventId);
    }

    record ParsedHoldbackKey(ResponseType responseType, String eventId) {
    }

    /**
     * 对照 Go {@code buildStreamResponseFor}：构出载荷，并在 public 模式下把存储引用
     * 换成客户端能直接加载的 URL。
     */
    public static StreamResponse buildStreamResponseFor(
            StreamEvent evt, String requestId, StreamRewriter rewriter) {
        StreamResponse response = StreamResponseBuilder.build(evt, requestId);
        if (!rewriter.enabled()) {
            return response;
        }

        Rewriter r = rewriter.rewriter();
        response.setKnowledgeReferences(r.copyReferences(response.getKnowledgeReferences()));
        response.setData(r.copyData(response.getData()));
        if (DELTA_RESPONSE_TYPES.contains(evt.getType())) {
            // 重写后的 Data 跟着被扣住的尾巴一起走，这样延迟释放时能带上与
            // 它被切下来那个事件相同的元数据。
            response.setContent(rewriter.push(
                    holdbackKey(evt.getType(), evt.getId()),
                    response.getContent(),
                    evt.isDone(),
                    response.getData()));
        } else {
            response.setContent(r.rewrite(response.getContent()));
        }
        return response;
    }

    /**
     * 对照 Go {@code emitStreamEvent}：写出一个 SSE 载荷。
     * 若 {@code evt} 会终止流，先把扣留缓冲里还剩的内容冲出去，
     * 因为客户端把完成标记当作消息结束。
     */
    public void emitStreamEvent(
            HttpServletResponse out, StreamEvent evt, String requestId,
            StreamRewriter rewriter, ClientState client) throws IOException {
        StreamResponse response = buildStreamResponseFor(evt, requestId, rewriter);
        if (TERMINAL_RESPONSE_TYPES.contains(evt.getType())) {
            flushHeldStreamContent(out, requestId, rewriter, client);
        }
        frameWriter.write(out, response);
    }

    /**
     * 对照 Go {@code flushHeldStreamContent}：把扣留缓冲里剩下的内容发出去，
     * 免得一条尾部引用在增量流没有终止分片时被悄悄丢掉。
     *
     * <p>凡是"客户端还在连着、但流停下来"的路径都必须调它——完成、用户请求停止、
     * 或者放弃事件存储。客户端已经走了就没人收了。</p>
     */
    public void flushHeldStreamContent(
            HttpServletResponse out, String requestId, StreamRewriter rewriter,
            ClientState client) throws IOException {
        Map<String, StreamRewriter.Held> held = rewriter.flushAll();
        if (held == null || held.isEmpty() || client.isGone()) {
            return;
        }
        for (Map.Entry<String, StreamRewriter.Held> entry : held.entrySet()) {
            StreamRewriter.Held fragment = entry.getValue();
            if (fragment.content().isEmpty()) {
                continue;
            }
            ParsedHoldbackKey parsed = parseHoldbackKey(entry.getKey());
            StreamResponse response = new StreamResponse();
            response.setId(requestId);
            response.setResponseType(parsed.responseType());
            response.setContent(fragment.content());
            response.setData(heldFragmentData(fragment.meta(), parsed.eventId()));
            frameWriter.write(out, response);
        }
    }

    /**
     * 对照 Go {@code heldFragmentData}：用被切下来的那个事件的元数据重建尾巴的元信息，
     * 这样按 {@code event_id}（或它携带的别的键，比如 {@code is_fallback}）取值的客户端
     * 看到的是同一个形状。
     *
     * <p>拷贝是必须的：没改写过的情况下返回的是流缓冲自己的那个 map，就地改会污染它。</p>
     */
    static Map<String, Object> heldFragmentData(Object meta, String eventId) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (meta instanceof Map<?, ?> original) {
            for (Map.Entry<?, ?> entry : original.entrySet()) {
                data.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        data.putIfAbsent("event_id", eventId);
        return data;
    }

    /** 客户端是否已经断开（对照 Go 的 {@code c.Request.Context().Err() != nil}）。 */
    public interface ClientState {
        boolean isGone();
    }
}
