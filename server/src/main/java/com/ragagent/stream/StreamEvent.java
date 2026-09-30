package com.ragagent.stream;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.TokenUsage;

/**
 * 流中的单个事件（对照 Go {@code interfaces.StreamEvent}，internal/types/interfaces/stream_manager.go:11-19）。
 *
 * <p>字段序 = Go struct 声明序；{@code data}/{@code usage} 带 omitempty → NON_EMPTY，
 * 其余四个恒输出（Go 的 string 零值是 {@code ""}、bool 零值是 {@code false}，不带 omitempty）。</p>
 *
 * <p><b>这是跨语言的存储契约，不只是内部类型</b>：事件经 {@link StreamJson} 序列化后
 * 落进 Redis List，Go 与 Java 两个实现共享同一批键。因此
 * <ul>
 *   <li>键名必须逐字对齐 Go 的 json tag；</li>
 *   <li>map 的键**按字母序**输出（Go 的 {@code json.Marshal} 对 map 如此，Java 侧由
 *       {@code ORDER_MAP_ENTRIES_BY_KEYS} 对齐），否则 {@code steer} 子列表里
 *       {@code LSET} 的 CAS 比对会跨语言失败；</li>
 *   <li>timestamp 用 RFC3339Nano（服务器本地时区偏移），与 Go 的 {@code time.Time} 一致。</li>
 * </ul></p>
 *
 * <p>已知差异：Go 的 {@code Type} 是 string 类型，零值序列化为 {@code ""}；Java 用枚举，
 * 未设置时为 {@code null}。真实产出方（agent 引擎 / QA 主链路）恒会赋值，故未复刻该零值。</p>
 */
@JsonPropertyOrder({"id", "type", "content", "done", "timestamp", "data", "usage"})
public class StreamEvent {

    /** 唯一事件 ID；steer 子列表靠它去重。 */
    @JsonProperty("id")
    private String id = "";

    @JsonProperty("type")
    private ResponseType type;

    /** 事件内容（流式事件里是一段增量）。 */
    @JsonProperty("content")
    private String content = "";

    @JsonProperty("done")
    private boolean done;

    @JsonProperty("timestamp")
    private OffsetDateTime timestamp;

    /** 附加数据（引用、元信息等）。Go 是 omitempty：null 时整键省略。 */
    @JsonProperty("data")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> data;

    /** 整轮聚合的 token 用量（complete 事件）。Go 是 omitempty。 */
    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private TokenUsage usage;

    public StreamEvent() {
    }

    public StreamEvent(String id, ResponseType type, String content, boolean done) {
        this.id = id == null ? "" : id;
        this.type = type;
        this.content = content == null ? "" : content;
        this.done = done;
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v == null ? "" : v;
    }

    public ResponseType getType() {
        return type;
    }

    public void setType(ResponseType v) {
        this.type = v;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = v == null ? "" : v;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean v) {
        this.done = v;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime v) {
        this.timestamp = v;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> v) {
        this.data = v;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public void setUsage(TokenUsage v) {
        this.usage = v;
    }

    /**
     * 浅拷贝。
     *
     * <p>Go 的 {@code StreamManager.AppendEvent} 收的是 StreamEvent <b>值</b>，
     * 方法内补时间戳不会影响调用方；Java 的对象是引用语义，故在两个
     * manager 的写路径上显式拷贝，复刻"调用方的对象不被改写"这一行为。</p>
     *
     * <p>{@code data} 仍是共享引用——与 Go 的浅拷贝一致（Go 里也是同一个 map）。</p>
     */
    public StreamEvent copy() {
        StreamEvent c = new StreamEvent();
        c.id = id;
        c.type = type;
        c.content = content;
        c.done = done;
        c.timestamp = timestamp;
        c.data = data;
        c.usage = usage;
        return c;
    }

    /** 就地合并 data（对照 Go 里对 {@code event.Data[k] = v} 的写入）。 */
    public void mergeData(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        Map<String, Object> merged = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
        merged.putAll(extra);
        this.data = merged;
    }
}
