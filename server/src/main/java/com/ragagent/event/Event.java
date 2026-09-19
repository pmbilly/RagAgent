package com.ragagent.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 事件包络（对照 Go {@code event.Event}，internal/event/event.go:95-102）。
 *
 * <p>字段与 Go 一致：ID / Type / SessionID / Data / Metadata / RequestID。
 * Go 是结构体<b>值</b>语义，Java 是引用语义——两处刻意补偿：</p>
 * <ul>
 *   <li>{@link #shallowCopy()}：Go 把 Event 按值传进 Emit，Emit 内补的 UUID 不会写回
 *       调用方的结构体（/tmp 实录：{@code callerStillEmpty=true}）。
 *       {@link EventBus} 发射前先 {@code shallowCopy()} 复刻这一点。</li>
 *   <li>{@code metadata} 是<b>共享引用</b>：Go 拷贝结构体时 map 字段仍是同一个 map，
 *       所以 WithTiming 中间件写 {@code duration_ms} 后调用方能看到
 *       （/tmp 实录：{@code timingSharedMetadata => callerSees=5}）。浅拷贝保留同一引用。</li>
 * </ul>
 *
 * <p>便捷构造对照 Go 侧同型函数：{@link #newEvent(String, Object)} ↔ {@code NewEvent}
 * （metadata 建空 map）；{@link #withSessionID}/{@link #withRequestID}/{@link #withMetadata}
 * ↔ 同名 With* 方法（返回新 Event，metadata map 共享 + withMetadata 就地写入——Go 值接收者
 * 的行为）。</p>
 *
 * <p>注意：Go 的 {@code Event} 结构体<b>没有 json tag</b>，它本身不是线上 JSON 契约
 * （线上契约是 data 里装的 payload 结构体与 StreamEvent）。本类不参与序列化。</p>
 */
public class Event {

    /** 事件 ID（自动生成 UUID，用于流式更新追踪；同一 id 的 final_answer 分片在客户端重组） */
    private String id = "";

    /** 事件类型（取值见 {@link EventType}） */
    private String type = "";

    /** 会话 ID */
    private String sessionId = "";

    /** 事件数据（payload 结构体，见 event_data.go 的 Java 对应类） */
    private Object data;

    /** 事件元数据（Go map 语义：可空、可共享） */
    private Map<String, Object> metadata;

    /** 请求 ID */
    private String requestId = "";

    public Event() {
    }

    public Event(String id, String type, String sessionId, Object data,
                 Map<String, Object> metadata, String requestId) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.data = data;
        this.metadata = metadata;
        this.requestId = requestId == null ? "" : requestId;
    }

    /** 对照 Go {@code NewEvent(eventType, data)}：metadata 建空 map。 */
    public static Event newEvent(String eventType, Object data) {
        Event e = new Event();
        e.type = eventType;
        e.data = data;
        e.metadata = new LinkedHashMap<>();
        return e;
    }

    /** 对照 Go 结构体值拷贝：字段逐个复制，metadata map 保持<b>同一引用</b>。 */
    public Event shallowCopy() {
        return new Event(id, type, sessionId, data, metadata, requestId);
    }

    /** 对照 Go {@code WithSessionID}：返回新 Event（metadata 共享）。 */
    public Event withSessionId(String sessionId) {
        Event c = shallowCopy();
        c.sessionId = sessionId == null ? "" : sessionId;
        return c;
    }

    /** 对照 Go {@code WithRequestID}：返回新 Event（metadata 共享）。 */
    public Event withRequestId(String requestId) {
        Event c = shallowCopy();
        c.requestId = requestId == null ? "" : requestId;
        return c;
    }

    /**
     * 对照 Go {@code WithMetadata}：metadata 为 null 时先建 map，再<b>就地</b>写入键值，
     * 返回新 Event。因为 map 是共享引用，原 Event 也能看到这次写入（Go 同）。
     */
    public Event withMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        metadata.put(key, value);
        return shallowCopy();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null ? "" : type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId == null ? "" : sessionId;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId == null ? "" : requestId;
    }

    /**
     * 对照 Go Emit 里的 ID 自动生成：{@code uuid.New().String()}（v4，36 字符小写）。
     * 注意与 {@link EventIds#generateEventID} 不同——那个带类型后缀且只取前 8 位。
     */
    static String newUuid() {
        return UUID.randomUUID().toString();
    }
}
