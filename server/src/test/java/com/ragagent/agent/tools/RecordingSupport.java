package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.ToolJson;

/** 4.5a 实录测试的共享小工具。 */
public final class RecordingSupport {

    public static final ObjectMapper PLAIN = new ObjectMapper();

    /**
     * B50：数字文本归一——把文本里的数字 token 统一成 Java {@code Double.toString} 形态
     * （Go 侧 {@code 1 / 1e+21 / 1e-7} ↔ Java 侧 {@code 1.0 / 1.0E21 / 1.0E-7}；
     * 大整数两侧同转）。两侧同归一后比较；非数字文本不受影响。
     */
    public static String normalizeNumberText(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = NUMBER_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            try {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                        Double.toString(Double.parseDouble(token))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(token));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 数字 token：前后不得是单词字符/点/反斜杠（避免误伤 `\\u003c` 的转义序列与标识符）。 */
    private static final java.util.regex.Pattern NUMBER_TOKEN =
            java.util.regex.Pattern.compile("(?<![\\w.\\\\])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![\\w.])");

    /**
     * 实录文本里的 Go 形态 HTML 转义还原（B42 基线收编）：{@code \u003c/\u003e/\u0026} →
     * {@code < > &}。Go 版已下线，转义形态不再构成断言目标；键序/结构/数字形态仍被钉住。
     */
    public static String normalizeEscapes(String s) {
        return s == null ? null
                : s.replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&");
    }

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

    /** 用 ToolJson 把 data map 编成 Go json.Marshal 字节形态（对照录制时的 mustJSON(res.Data)）。 */
    public static String goJsonOfData(java.util.Map<String, Object> data) {
        return ToolJson.write(PLAIN.valueToTree(data));
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
        // 标量借道 ToolJson 的编码（数字/字符串/布尔与 Go 一致）
        return ToolJson.write(node);
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
