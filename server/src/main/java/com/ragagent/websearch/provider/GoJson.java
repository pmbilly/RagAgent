package com.ragagent.websearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * websearch provider 包内的 provider 请求/响应 JSON 编解码。
 *
 * <p><b>2026-10-03 档 3 退役</b>：不再复刻 Go {@code encoding/json} 的 HTML 转义——
 * provider 接受标准 JSON，语义等价。第三份包内副本（收敛点与 embedding/rerank 同案）。</p>
 */
public final class GoJson {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();

    private static final JsonMapper UNMARSHAL = JsonMapper.builder().build();

    private GoJson() {
    }

    public static byte[] marshal(ObjectNode node) {
        try {
            return MARSHAL.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode object() {
        return MARSHAL.createObjectNode();
    }

    public static ArrayNode array() {
        return MARSHAL.createArrayNode();
    }

    public static JsonNode parse(byte[] body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode parse(String body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}
