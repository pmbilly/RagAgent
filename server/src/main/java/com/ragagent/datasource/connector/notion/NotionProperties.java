package com.ragagent.datasource.connector.notion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 属性抽取的四个纯函数（{@code extractTitle} / {@code joinPlainText} /
 * {@code propertyToString} / {@code extractValue} / {@code extractLeafValue} /
 * {@code extractPropertySchema}）。
 *
 * <h2>为什么它们"通用而不硬编码 22 种属性类型"</h2>
 * <p>Notion 的属性形状是"一个 {@code type} 字段 + 一个以 type 命名的内层对象"，
 * 所以 {@code propertyToString} 只需：取 {@code type} → 取 {@code value[type]} →
 * 递归地 {@code extractValue}。叶子值的抽取靠 {@link #extractLeafValue} 的
 * "按 known key 顺序试探"（{@code name} → {@code content} → {@code plain_text}
 * → {@code start/end} → {@code expression} → 再按 type 递归）。</p>
 *
 * <h2>输入为什么是 {@link JsonNode} 而不是 {@code Map}</h2>
 * <p>直接在 Jackson 的树上做，少一次转换；关键是
 * <b>数字一律按双精度处理</b>（见
 * {@link NotionValues#jsonNumberToString}）——Jackson 会按需给
 * {@code IntNode}/{@code DoubleNode}，不能直接 {@code asText()}。</p>
 *
 * <h2>两处刻意保留的确定性（都是"结果更确定"）</h2>
 * <ol>
 *   <li><b>{@code extractTitle} 的顺序</b>：取**文档序第一个** {@code type=="title"}
 *       且有内容的属性。结果确定且与 Notion 返回的字段顺序一致。</li>
 *   <li><b>{@code extractPropertySchema} 的排序</b>：用 {@code String.compareTo}
 *       （UTF-16 码元序）。对 ASCII 属性名与码点序完全一致。</li>
 * </ol>
 */
final class NotionProperties {

    private NotionProperties() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // 标题
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 先按 {@code properties} 里第一个
     * {@code type=="title"} 的属性拼 plain_text，再回落到顶层的 {@code title} 数组
     * （数据库对象走这条）。
     *
     * <p>两个容易写错的地方：① 判定是
     * {@code type == "title" && title 数组非空}——一个 type 是 title
     * 但数组为**空**的属性**不会**让函数提前返回，循环继续往下找；
     * ② {@code properties} 不是对象（例如是数组或字符串）时**整段跳过**、
     * 直接走顶层 title 回落。</p>
     */
    static String extractTitle(NotionPage page) {
        if (page == null) {
            return "";
        }
        JsonNode props = page.rawProperties;
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                JsonNode propRaw = fields.next().getValue();
                if (propRaw == null || !propRaw.isObject()) {
                    continue;
                }
                JsonNode typeNode = propRaw.get("type");
                if (typeNode == null || !typeNode.isTextual()
                        || !"title".equals(typeNode.textValue())) {
                    continue;
                }
                JsonNode titleNode = propRaw.get("title");
                if (titleNode == null || !titleNode.isArray() || titleNode.isEmpty()) {
                    continue;
                }
                return joinPlainText(titleNode);
            }
        }

        JsonNode rawTitle = page.rawTitle;
        if (rawTitle != null && rawTitle.isArray() && !rawTitle.isEmpty()) {
            return joinPlainText(rawTitle);
        }
        return "";
    }

    /** 把每个片段的 {@code plain_text} 直接拼接。 */
    static String joinPlainText(JsonNode segments) {
        if (segments == null || !segments.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : segments) {
            if (segment == null || !segment.isObject()) {
                continue;
            }
            JsonNode pt = segment.get("plain_text");
            if (pt != null && pt.isTextual()) {
                sb.append(pt.textValue());
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 属性 → 字符串
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 沿类型链通用地抽出一个字符串值。
     *
     * <pre>
     *   typeName = value["type"]（字符串）
     *   typeName 为空        → extractLeafValue(value)
     *   value[typeName] 缺席或为 null → ""
     *   否则                  → extractValue(inner)
     * </pre>
     */
    static String propertyToString(JsonNode value) {
        if (value == null) {
            return "";
        }
        String typeName = "";
        JsonNode typeNode = value.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            typeName = typeNode.textValue();
        }
        if (typeName.isEmpty()) {
            return extractLeafValue(value);
        }
        JsonNode inner = value.get(typeName);
        if (inner == null || inner.isNull()) {
            return "";
        }
        return extractValue(inner);
    }

    /**
     * 按 JSON 值的**运行时类型**分派。
     *
     * <p>顺序要点：数字走 {@link NotionValues#jsonNumberToString}（先试 {@code %d}
     * 再 {@code %g}）；数组是"逐个取、**丢掉空串**、用 {@code ", "} 连接"
     * ——所以 {@code [{"name":1},{"name":"x"}]} 得到 {@code "x"}（数字没有
     * {@code name} 字符串）。</p>
     */
    static String extractValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        if (v.isTextual()) {
            return v.textValue();
        }
        if (v.isNumber()) {
            return NotionValues.jsonNumberToString(v.doubleValue());
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "true" : "false";
        }
        if (v.isObject()) {
            return extractLeafValue(v);
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : v) {
                String s = extractValue(item);
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            return String.join(", ", parts);
        }
        // 其余节点类型不会出现在 Notion 的响应里，不可达。
        return "";
    }

    /**
     * 按**固定顺序**试探已知的叶子键。
     *
     * <p>顺序有语义：{@code name} 先于 {@code content} 先于 {@code plain_text}
     * 先于 {@code start}/{@code end} 先于 {@code expression}，最后才按
     * {@code type} 递归。{@code start} 命中后，只有 {@code end} **是非空字符串**
     * 才拼 {@code "start ~ end"}（{@code end:""} 与 {@code end:null} 都只回 start
     * ——实测 {@code {"start":"2026-01-15","end":""} → "2026-01-15"}）。</p>
     */
    static String extractLeafValue(JsonNode m) {
        if (m == null || !m.isObject()) {
            return "";
        }
        JsonNode name = m.get("name");
        if (name != null && name.isTextual()) {
            return name.textValue();
        }
        JsonNode content = m.get("content");
        if (content != null && content.isTextual()) {
            return content.textValue();
        }
        JsonNode plainText = m.get("plain_text");
        if (plainText != null && plainText.isTextual()) {
            return plainText.textValue();
        }
        JsonNode start = m.get("start");
        if (start != null && start.isTextual()) {
            JsonNode end = m.get("end");
            if (end != null && end.isTextual() && !end.textValue().isEmpty()) {
                return start.textValue() + " ~ " + end.textValue();
            }
            return start.textValue();
        }
        JsonNode expression = m.get("expression");
        if (expression != null && expression.isTextual()) {
            return expression.textValue();
        }
        JsonNode typeNode = m.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            JsonNode inner = m.get(typeNode.textValue());
            if (inner != null) {
                return extractValue(inner);
            }
        }
        return "";
    }

    // ──────────────────────────────────────────────────────────────────────
    // 属性名集合
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 抽出**除 title 之外**的属性名并排序。
     *
     * <p>排序是刻意的：不排序会让"数据库表格列序"每次都不同，进而在增量同步时造成
     * **假的内容变更**。</p>
     *
     * <p>只有"是对象且 {@code type == "title"}"的属性才被排除，其余
     * （包括字符串/数字/数组值、以及 JSON {@code null}）一律收进结果。
     * {@code {"A":"str","B":{"type":"title"}} → ["A"]}。</p>
     */
    static List<String> extractPropertySchema(NotionPage record) {
        if (record == null || record.rawProperties == null) {
            return null;
        }
        JsonNode props = record.rawProperties;
        if (!props.isObject()) {
            // 整段不是对象 → 按"没有属性"处理
            return null;
        }
        List<String> propNames = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode propRaw = entry.getValue();
            String type = "";
            if (propRaw != null && propRaw.isObject()) {
                JsonNode typeNode = propRaw.get("type");
                if (typeNode != null && typeNode.isTextual()) {
                    type = typeNode.textValue();
                }
            }
            // ⚠️ 值不是对象时（字符串/数字/数组）type 视为空串，
            // **照样收进结果**；
            // 只有"是对象且 type == title"才被排除。
            // {"A":"str","B":{"type":"title"}} → ["A"]。
            if (!"title".equals(type)) {
                propNames.add(entry.getKey());
            }
        }
        Collections.sort(propNames);
        // **一个都没收到**时回 null（序列化成 null），而不是空列表——调用方靠它区分
        // "没有属性"与"属性全为 title"。
        return propNames.isEmpty() ? null : propNames;
    }

    /**
     * {@code null} 的兜底视图：每个调用点显式走这里——保留
     * {@link #extractPropertySchema} 返回 {@code null} 的语义
     * （它序列化成 {@code null}）。
     */
    static List<String> orEmpty(List<String> propNames) {
        return propNames == null ? List.of() : propNames;
    }
}
