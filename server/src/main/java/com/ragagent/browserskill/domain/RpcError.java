package com.ragagent.browserskill.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对照 Go {@code browserskill.RPCError}（errors.go）：把 BrowserSkill 守护进程的
 * 错误分类跨本地与集群传输保留。{@link #errorString()} 保持旧字符串契约
 * （code + ": " + message）供老对端与调用方使用。
 *
 * <p>集群 internal 响应里的 rpc_error 键形状 = {code, message, data?}
 * （data omitempty——JsonNode 空缺时省略；对照 Go 的 json.RawMessage omitempty）。
 * 序列化走 {@link #toNode(ObjectMapper)}（显式组形状），不走 Jackson bean
 * 反射——RuntimeException 的 fillInStackTrace/getMessage 会被 Jackson 误当属性。</p>
 */
public class RpcError extends RuntimeException {

    private final String code;
    private JsonNode data;

    public RpcError(String code, String message) {
        this(code, message, null);
    }

    public RpcError(String code, String message, JsonNode data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public String getCode() { return code; }

    public JsonNode getData() { return data; }

    public void setData(JsonNode data) { this.data = data; }

    /** 对照 RPCError.Error()：code + ": " + message */
    @JsonIgnore
    public String errorString() {
        return code + ": " + super.getMessage();
    }

    /** 响应形状：{code, message, data?}（data 为空省略，对照 RawMessage omitempty） */
    public ObjectNode toNode(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", code);
        node.put("message", super.getMessage());
        if (data != null && !data.isNull() && !data.isEmpty()) {
            node.set("data", data);
        }
        return node;
    }

    /**
     * 对照 RPCError.BoundDetails（errors.go L18-32）：把超出 8KB 的页控载荷裁剪到
     * 只保留协议的恢复判别字段（reason/effect_state，各 ≤128 字符）。
     */
    public void boundDetails(ObjectMapper mapper) {
        if (data == null || data.size() <= 8192) {
            return;
        }
        ObjectNode bounded = mapper.createObjectNode();
        bounded.put("truncated", true);
        for (String key : new String[]{"reason", "effect_state"}) {
            JsonNode v = data.get(key);
            if (v != null && v.isTextual() && v.textValue().length() <= 128) {
                bounded.put(key, v.textValue());
            }
        }
        this.data = bounded;
    }
}
