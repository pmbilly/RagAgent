package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * {@code json.Marshal(map[string]interface{})} 的等价编码器（本批专用，对照
 * Go {@code param_cast.go} 末尾的 re-marshal）。
 *
 * <p>CastParams 发生转型后会把整棵 args 重新序列化，Go 的 map 编码有四条铁律，
 * 这里逐条复刻：</p>
 * <ol>
 *   <li><b>map 键按字节序排序</b>（所有层级）；</li>
 *   <li><b>HTML 转义恒开</b>：{@code < > &} → {@code < > &}（实录 CAST 18：
 *       {@code {"s":"<b>&"}} → {"s":"\u003cb\u003e\u0026"}）；</li>
 *   <li><b>数字全是 float64</b>（Go 解析进 map[string]interface{} 后无 int），
 *       按 Go 浮点编码器输出（{@link GoDoubleSerializer}；实录 CAST 19：{@code 1e21}）；</li>
 *   <li><b>紧凑输出</b>（无空格无换行）。</li>
 * </ol>
 *
 * <p>整型（cast 出的 int64）按十进制直写；U+2028/29 按 Go 转义（本 writer 是手写的，
 * 不受 Jackson CharacterEscapes 够不到非 ASCII 的限制）。</p>
 */
public final class GoJsonCodec {

    private GoJsonCodec() {
    }

    /** 把 args 树编码为 Go json.Marshal 的字节形态。 */
    public static String write(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeNode(node, sb);
        return sb.toString();
    }

    private static void writeNode(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            writeNumber(node, sb);
            return;
        }
        if (node.isTextual() || !node.isContainerNode()) {
            writeString(node.isTextual() ? node.textValue() : node.asText(), sb);
            return;
        }
        if (node instanceof ArrayNode array) {
            sb.append('[');
            boolean first = true;
            for (JsonNode item : array) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeNode(item, sb);
            }
            sb.append(']');
            return;
        }
        if (node instanceof ObjectNode obj) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            obj.fields().forEachRemaining(entries::add);
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : entries) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeNode(e.getValue(), sb);
            }
            sb.append('}');
        }
    }

    /** Go 的 map[string]interface{} 里 JSON 数字反序列化后都是 float64；cast 产物才有整型。 */
    private static void writeNumber(JsonNode node, StringBuilder sb) {
        if (node.isFloatingPointNumber()) {
            sb.append(GoDoubleSerializer.format(node.doubleValue()));
        } else {
            // 整型：cast 出的 int64（对照 Go 的 strconv.FormatInt / json.Marshal(int64)）
            sb.append(node.longValue());
        }
    }

    /** Go encoding/json 的字符串编码（HTML 转义 + 小写十六进制控制字符 + U+2028/29）。 */
    static void writeString(String s, StringBuilder sb) {
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
                case 0x2028 -> sb.append("\\u2028");
                case 0x2029 -> sb.append("\\u2029");
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
