package com.ragagent.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * embedding/rerank 包共用的 provider 请求/响应 JSON 编解码。
 *
 * <p><b>2026-10-03 档 3 退役</b>：不再复刻 Go {@code encoding/json} 的 HTML 转义
 * （{@code < > &} → {@code \u003C} 等形态）——provider 接受标准 JSON，语义等价；
 * 字段序仍由 ObjectNode 插入序保证。</p>
 *
 * <p><b>响应侧</b>（解析 provider 回包）：忽略未知字段（Jackson 默认行为）。</p>
 */
public final class GoJson {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();

    private static final JsonMapper UNMARSHAL = JsonMapper.builder().build();

    private GoJson() {
    }

    /** Go json.Marshal 等价（HTML 转义 + struct 字段序）。 */
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

    /** Go json.Unmarshal 等价（容忍未知字段；失败返回 null，调用方按 err 分支）。 */
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

    /** 便捷：文本数组字段（Go 的 {@code []string}）。 */
    public static ArrayNode arrayOfStrings(java.util.List<String> texts) {
        ArrayNode arr = MARSHAL.createArrayNode();
        for (String t : texts) {
            arr.add(t == null ? "" : t);
        }
        return arr;
    }

    /** 解析 OpenAI 形响应的 embedding 数组：{@code data[i].embedding} 的 float 数组。 */
    public static float[] floatArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new float[0];
        }
        float[] out = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            out[i] = (float) node.get(i).asDouble();
        }
        return out;
    }
}
