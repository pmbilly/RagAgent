package com.ragagent.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.GoJsonEscapes;

/**
 * rerank 包共用的 Go {@code encoding/json} 等价编解码（与
 * {@code com.ragagent.embedding.GoJson} 同一份语义的两份包内副本——跨包公共化
 * 待主会话收敛）。请求侧 Go json.Marshal 的 HTML/控制字符转义由
 * {@link GoJsonEscapes} 复刻；响应侧容忍未知字段（Go json.Unmarshal 语义）。
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

    public static JsonNode parse(String body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode parse(byte[] body) {
        try {
            return UNMARSHAL.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    public static ArrayNode arrayOfStrings(java.util.List<String> texts) {
        ArrayNode arr = MARSHAL.createArrayNode();
        for (String t : texts) {
            arr.add(t == null ? "" : t);
        }
        return arr;
    }
}
