package com.ragagent.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.GoJsonEscapes;

/**
 * embedding/rerank 包共用的 Go {@code encoding/json} 等价 JSON 编解码。
 *
 * <p><b>请求侧</b>（发给 provider 的字节流 = 契约）：Go 的 {@code json.Marshal} 默认
 * 开 HTML 转义（{@code < > &} 转成小写的 003C/003E/0026 形式转义），控制字符同样
 * 小写十六进制——由 {@link GoJsonEscapes} 复刻（§9「JSON 编码器系统性差分排查」）。
 * 字段序 = Go struct 声明序（ObjectNode 插入序），omitempty 零值在构造处显式省略。</p>
 *
 * <p><b>响应侧</b>（解析 provider 回包）：Go 的 {@code json.Unmarshal} 默认忽略未知
 * 字段 → Jackson 配 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}。</p>
 */
public final class GoJson {

    private static final JsonMapper MARSHAL = JsonMapper.builder().build();
    static {
        MARSHAL.getFactory().setCharacterEscapes(new GoJsonEscapes());
    }

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
