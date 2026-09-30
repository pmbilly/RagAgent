package com.ragagent.support;

import java.util.TreeMap;
import java.util.Map;
import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 契约测试的 JSON 语义比较器（阶段 1 收官批，PR4）。
 *
 * <p>迁移基线化后，fixture 锚定的是<b>本仓自己的行为</b>，与 Go 录制机再无字节契约；
 * 键序（Jackson LinkedHashMap vs 旧 golden 的写入顺序）与 HTML 转义（\\u003c vs 字面
 * 字符）不再构成断言目标。{@link #semantic(ObjectMapper, String)} 把任意 JSON 文本
 * 归一为「键排序 + 数字统一 + 紧凑分隔」的规范形态：解析失败的文本原样返回，
 * 字节断言对非 JSON 响应（错误串、纯文本）照旧成立。</p>
 *
 * <p>用法：契约测试的 {@code golden(name)} 与 {@code raw(result)} 出口各包一层
 * {@code ContractJson.semantic(MAPPER, ...)}——两侧同归一后，等值断言即语义断言。</p>
 */
public final class ContractJson {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private ContractJson() {
    }

    /** 便捷入口：内部默认 mapper。 */
    public static String semantic(String text) {
        return semantic(DEFAULT_MAPPER, text);
    }

    /** 归一化入口：可解析 → 键排序紧凑 JSON；不可解析 → 原样（响应体尾随换行是内容，禁 trim）。 */
    public static String semantic(ObjectMapper mapper, String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(text);
            if (root == null || root.isMissingNode()) {
                return text;
            }
            JsonNode normalized = normalize(root);
            return mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return text;
        }
    }

    /**
     * 深度语义归一：在 {@link #semantic} 的基础上再多两层归一。
     *
     * <p>Go 兼容序列化层退役（2026-09-30）后，两类差异属于"同一语义、不同写法"，
     * 不应构成断言目标：</p>
     * <ul>
     *   <li><b>时间写法</b>：同一瞬时此前按 JVM 默认时区输出（{@code +08:00}），
     *       现在按标准 ISO-8601 输出（{@code Z}）——本方法把两侧都归一到 UTC 比较；</li>
     *   <li><b>字符串里装的 JSON</b>：如 chatpipeline 录制的 {@code params} 字段，
     *       内层浮点/键序同样需要归一。</li>
     * </ul>
     */
    public static String deep(String text) {
        return deep(DEFAULT_MAPPER, text);
    }

    public static String deep(ObjectMapper mapper, String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(text);
            if (root == null || root.isMissingNode()) {
                return text;
            }
            return mapper.writeValueAsString(canonical(root, mapper));
        } catch (Exception e) {
            return text;
        }
    }

    private static JsonNode canonical(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonical(e.getValue(), mapper)));
            ObjectNode out = mapper.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(item -> out.add(canonical(item, mapper)));
            return out;
        }
        if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
                return mapper.getNodeFactory().numberNode((long) d);
            }
            return node;
        }
        if (node.isTextual()) {
            String raw = node.asText();
            JsonNode nested = tryNested(mapper, raw);
            if (nested != null) {
                try {
                    return mapper.getNodeFactory().textNode(mapper.writeValueAsString(canonical(nested, mapper)));
                } catch (Exception ignored) {
                    return node;
                }
            }
            String utc = toUtc(raw);
            return utc.equals(raw) ? node : mapper.getNodeFactory().textNode(utc);
        }
        return node;
    }

    private static JsonNode tryNested(ObjectMapper mapper, String raw) {
        String s = raw.trim();
        if (s.length() < 2 || (s.charAt(0) != '{' && s.charAt(0) != '[')) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(s);
            return n != null && (n.isObject() || n.isArray()) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** ISO-8601 带偏移/`Z` → 同一瞬时的 UTC 写法（保留原小数位数）。 */
    private static String toUtc(String raw) {
        java.util.regex.Matcher m = ISO.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        try {
            java.time.OffsetDateTime utc = java.time.OffsetDateTime.parse(raw)
                    .withOffsetSameInstant(java.time.ZoneOffset.UTC);
            String frac = m.group(1) == null ? "" : m.group(1);
            return String.format("%04d-%02d-%02dT%02d:%02d:%02d%sZ",
                    utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(),
                    utc.getHour(), utc.getMinute(), utc.getSecond(), frac);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static final java.util.regex.Pattern ISO = java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

    /** 递归归一：对象键排序（TreeMap），整值浮点折叠为整数，其余原样深拷贝。 */
    static JsonNode normalize(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), normalize(e.getValue()));
            }
            ObjectNode out = new ObjectMapper().createObjectNode();
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                out.set(e.getKey(), e.getValue());
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = new ObjectMapper().createArrayNode();
            for (JsonNode item : node) {
                out.add(normalize(item));
            }
            return out;
        }
        if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
                return new ObjectMapper().getNodeFactory().numberNode((long) d);
            }
        }
        return node;
    }
}
