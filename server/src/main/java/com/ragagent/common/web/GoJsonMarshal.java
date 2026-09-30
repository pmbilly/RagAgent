package com.ragagent.common.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Go {@code json.MarshalIndent(v, "", "  ")} 的字节形态复刻（extract_entity 的
 * formatExtraction 输出即契约——示例答案进 LLM 提示词，实测对拍）。
 *
 * <p>键序：Go 的 map[string]interface{} 序列化按 key 字母序（encoding/json 排序）；
 * 缩进：空对象 {@code {}} / 空数组 {@code []}，非空容器每个元素独立一行按深度缩进，
 * {@code "key": value} 冒号后带一个空格；HTML 转义（&lt; &gt; &amp; →
 * \\u003c \\u003e \\u0026）与小写十六进制控制字符与 Go 一致。
 * 写法与 agent.tools.GoJsonCodec 同源（那里是紧凑版且包私有细节不可复用）。</p>
 */
public final class GoJsonMarshal {

    private GoJsonMarshal() {}

    /** 对照 json.MarshalIndent(v, "", "  ")（值按 JsonNode 表达）。 */
    public static String indent(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb, 0);
        return sb.toString();
    }

    /** 便捷入口：map 值先转树（键按字母序输出）。 */
    public static String indentListOfMaps(List<Map<String, Object>> items) {
        var arr = new com.fasterxml.jackson.databind.node.ArrayNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        for (Map<String, Object> item : items) {
            var obj = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            List<String> keys = new ArrayList<>(item.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) {
                obj.set(k, JSON_TREES.valueToTree(item.get(k)));
            }
            arr.add(obj);
        }
        return indent(arr);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static final class JSON_TREES {
        static com.fasterxml.jackson.databind.JsonNode valueToTree(Object v) {
            return MAPPER.valueToTree(v);
        }
    }

    private static void write(JsonNode node, StringBuilder sb, int depth) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            sb.append(com.ragagent.common.web.GoDoubleSerializer.format(node.doubleValue()));
            return;
        }
        if (node.isTextual() || !node.isContainerNode()) {
            writeString(node.isTextual() ? node.textValue() : node.asText(), sb);
            return;
        }
        if (node instanceof ArrayNode array) {
            if (array.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < array.size(); i++) {
                indentBy(sb, depth + 1);
                write(array.get(i), sb, depth + 1);
                if (i < array.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indentBy(sb, depth);
            sb.append(']');
            return;
        }
        if (node instanceof ObjectNode obj) {
            if (obj.isEmpty()) {
                sb.append("{}");
                return;
            }
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            obj.fields().forEachRemaining(entries::add);
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            sb.append("{\n");
            for (int i = 0; i < entries.size(); i++) {
                indentBy(sb, depth + 1);
                writeString(entries.get(i).getKey(), sb);
                sb.append(": ");
                write(entries.get(i).getValue(), sb, depth + 1);
                if (i < entries.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indentBy(sb, depth);
            sb.append('}');
            return;
        }
    }

    private static void indentBy(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    /** Go encoding/json 的字符串编码（HTML 转义 + 小写十六进制控制字符）。 */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
