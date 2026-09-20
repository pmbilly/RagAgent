package com.ragagent.websearch.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.GoJsonEscapes;

/**
 * websearch provider 包内的 Go {@code encoding/json} 等价编解码（第三份包内副本，
 * 收敛点与 embedding/rerank 两份同案——请求侧 HTML/控制字符转义走
 * {@link GoJsonEscapes}，响应侧容忍未知字段）。
 */
public final class GoJson {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();
    static {
        MARSHAL.getFactory().setCharacterEscapes(new GoJsonEscapes());
    }

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
