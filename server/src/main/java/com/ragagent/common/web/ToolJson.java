package com.ragagent.common.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具参数/输出的 JSON 编码（标准 Jackson 实现）。
 *
 * <p><b>2026-10-03（B43）</b>：手写 writer 退役——原实现（135 行）为逐字节对齐 Go
 * {@code encoding/json} 而写（紧凑输出 / map 键字节序 / HTML 转义 / Go 浮点形态）。
 * Go 版下线后改回 Java 生态原生做法：标准 Jackson 序列化；仅保留<b>递归键排序</b>
 * 一条（不是 Go 复刻，是 LLM 载荷的字节稳定性前提——同一参数两次编码需同字节）。</p>
 */
public final class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private ToolJson() {
    }

    /** 标准 Jackson 紧凑 JSON（Java 原生做法，替代手写 writer）。 */
    public static String compactJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    /** 标准 Jackson 缩进 JSON（替代原 GoJsonMarshal 的手写缩进器）。 */
    public static String prettyJson(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    /**
     * JSON 字符串字面量（连引号）：标准 Jackson 转义——Java 生态原生做法。
     *
     * <p>替代原 {@code GoQuoting.quoteGo}、{@code WeaviateGql.quoteGo} 与手写
     * {@code writeString}（三份复刻，Go 版已下线）。</p>
     */
    public static String quoted(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("String 序列化不应失败", e);
        }
    }

    /** 工具参数编码：递归键排序（确定性）+ 标准 Jackson 紧凑输出。 */
    public static String write(JsonNode node) {
        return compactJson(sorted(node));
    }

    /** 递归按键字母序重建（数组保序、标量原样）；入参不被修改。 */
    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            obj.fields().forEachRemaining(entries::add);
            entries.sort(Map.Entry.comparingByKey());
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> e : entries) {
                out.set(e.getKey(), sorted(e.getValue()));
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : arr) {
                out.add(sorted(item));
            }
            return out;
        }
        return node;
    }
}
