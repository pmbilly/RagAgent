package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.GoJsonCodec;

/** 4.5a 实录测试的共享小工具。 */
public final class RecordingSupport {

    public static final ObjectMapper PLAIN = new ObjectMapper();

    /** JSON mapper（map 键序由类型上的 serializer 负责；2026-10-03 起标准转义）。 */
    public static final ObjectMapper GO_MAPPER = new ObjectMapper();

    private RecordingSupport() {
    }

    public static JsonNode rec(String constant) {
        return GoRecording45A.rec(constant);
    }

    public static String text(JsonNode rec, String field) {
        JsonNode n = rec.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public static JsonNode readTree(String json) {
        try {
            return PLAIN.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 用 GoJsonCodec 把 data map 编成 Go json.Marshal 字节形态（对照录制时的 mustJSON(res.Data)）。 */
    public static String goJsonOfData(java.util.Map<String, Object> data) {
        return GoJsonCodec.write(PLAIN.valueToTree(data));
    }

    /**
     * 键序无关的规范化 JSON：递归排序对象键后序列化。用于 data map 比较——
     * Go 只排 map 键序、struct 键序按声明（{@code steps} 是 struct 数组），
     * Java 经 valueToTree 的键序取决于序列化层；两边都规范化后比较值本身，
     * 键序契约由 steps_json 字符串与 output 文本的字节断言承担。
     */
    public static String canonicalJson(JsonNode node) {
        return writeCanonical(node);
    }

    private static String writeCanonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            java.util.List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String k = keys.get(i);
                sb.append('"').append(k).append("\":");
                sb.append(writeCanonical(node.get(k)));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(writeCanonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        // 标量借道 GoJsonCodec 的编码（数字/字符串/布尔与 Go 一致）
        return GoJsonCodec.write(node);
    }

    /** 重建 trunc 语料的输入串（对照探针的 pieces/repeats 拼接）。 */
    public static String buildTruncInput(JsonNode rec) {
        JsonNode pieces = rec.get("pieces");
        JsonNode repeats = rec.get("repeats");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            sb.append(pieces.get(i).asText().repeat(repeats.get(i).asInt()));
        }
        return sb.toString();
    }
}
