package com.ragagent.common.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 紧凑 JSON 编码器（参数转型后重新序列化整棵 args 用），输出遵循四条规则：
 * <ol>
 *   <li><b>map 键按字节序排序</b>（所有层级）；</li>
 *   <li><b>标准 JSON 转义</b>（2026-10-03 B42：Go 版下线后不再复刻 {@code < > &} 的
 *       {@code \u003c} 形态，控制字符用大写十六进制）；</li>
 *   <li><b>数字按浮点形态编码</b>（{@link GoDoubleSerializer}；例：{@code 1e21}）；</li>
 *   <li><b>紧凑输出</b>（无空格无换行）。</li>
 * </ol>
 *
 * <p>整型（cast 出的 int64）按十进制直写；U+2028/29 转义为 \u2028/\u2029
 * （本 writer 是手写的，不受 Jackson 对非 ASCII 的默认处理限制）。</p>
 *
 * <p>与同包的 {@link GoJsonMarshal} 同源：后者是宽松版（可读缩进/转义开关），
 * 本类是紧凑版（键序 + 浮点形态 + HTML 转义恒开），被工具参数重编码与
 * modelcontext 复用——原先落在 {@code agent.tools} 时被 modelcontext 反向依赖。</p>
 */
public final class ToolJson {

    private ToolJson() {
    }

    /** 按类注释的四条规则把 args 树编码为紧凑 JSON。 */
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

    /** JSON 解析出的数字一律按浮点形态编码；cast 产出的整型按十进制直写。 */
    private static void writeNumber(JsonNode node, StringBuilder sb) {
        if (node.isFloatingPointNumber()) {
            sb.append(GoDoubleSerializer.format(node.doubleValue()));
        } else {
            // 整型：cast 出的 int64，十进制直写
            sb.append(node.longValue());
        }
    }

    /**
     * 字符串字面量编码（连引号一起写）：标准 JSON 转义（大写十六进制控制字符）+ U+2028/29。
     *
     * <p>公开给"自行拼装 JSON"的调用方（如 agent 的 issue 视图）；
     * 整棵树编码请直接用 {@link #write(JsonNode)}。</p>
     */
    public static void writeString(String s, StringBuilder sb) {
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
                case 0x2028 -> sb.append("\\u2028");
                case 0x2029 -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
