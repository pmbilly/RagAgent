package com.ragagent.datasource.connector.notion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 属性抽取的四个纯函数（对照 Go connector.go L487-530 的 {@code extractTitle}
 * / {@code joinPlainText}，以及 L767-868 的 {@code propertyToString} /
 * {@code extractValue} / {@code extractLeafValue} / {@code extractPropertySchema}）。
 *
 * <h2>为什么它们"通用而不硬编码 22 种属性类型"</h2>
 * <p>Notion 的属性形状是"一个 {@code type} 字段 + 一个以 type 命名的内层对象"，
 * 所以 {@code propertyToString} 只需：取 {@code type} → 取 {@code value[type]} →
 * 递归地 {@code extractValue}。叶子值的抽取靠 {@link #extractLeafValue} 的
 * "按 known key 顺序试探"（{@code name} → {@code content} → {@code plain_text}
 * → {@code start/end} → {@code expression} → 再按 type 递归）。</p>
 *
 * <h2>输入为什么是 {@link JsonNode} 而不是 {@code Map}</h2>
 * <p>Go 的输入是 {@code map[string]interface{}}（{@code json.Unmarshal} 的产物）。
 * Java 侧直接在 Jackson 的树上做，等价且少一次转换；关键是
 * <b>数字一律按 Go 的 {@code float64} 处理</b>（见
 * {@link NotionValues#jsonNumberToString}）——Jackson 会按需给
 * {@code IntNode}/{@code DoubleNode}，不能直接 {@code asText()}。</p>
 *
 * <h2>两处刻意保留的差异（都是"Java 更确定"）</h2>
 * <ol>
 *   <li><b>{@code extractTitle} 的顺序</b>：Go 遍历
 *       {@code map[string]json.RawMessage} 取**第一个** {@code type=="title"}
 *       且有内容的属性——map 迭代顺序在 Go 里是随机的，所以"一个页面有多个 title
 *       属性"时 Go 每次调用可能给出不同标题。Java 用 Jackson 的
 *       {@code ObjectNode}（底层是 {@code LinkedHashMap}，**保持 JSON 文档序**），
 *       结果确定且与 Notion 返回的字段顺序一致。</li>
 *   <li><b>{@code extractPropertySchema} 的排序</b>：Go 用 {@code sort.Strings}
 *       （**UTF-8 字节序**），Java 用 {@code String.compareTo}（UTF-16 码元序）。
 *       两者对 ASCII 完全一致；只在"增补平面字符与 U+E000–U+FFFF 混排"时分叉
 *       （UTF-16 里 U+10000 是 D800 DC00，排在 U+E000 **之前**；而按码点/UTF-8
 *       字节序它排在**之后**）。Notion 的属性名几乎不可能是这种字符。</li>
 * </ol>
 */
final class NotionProperties {

    private NotionProperties() {
    }

    // ──────────────────────────────────────────────────────────────────────
    // 标题（Go client.go L487-530）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code extractTitle}：先按 {@code properties} 里第一个
     * {@code type=="title"} 的属性拼 plain_text，再回落到顶层的 {@code title} 数组
     * （数据库对象走这条）。
     *
     * <p>两个容易写错的地方：① Go 的判定是
     * {@code prop.Type == "title" && len(prop.Title) > 0}——一个 type 是 title
     * 但数组为**空**的属性**不会**让函数提前返回，循环继续往下找；
     * ② {@code properties} 解不成 map（例如是数组或字符串）时**整段跳过**、
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

    /** 对照 Go {@code joinPlainText}：把每个片段的 {@code plain_text} 直接拼接。 */
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
    // 属性 → 字符串（Go connector.go L767-844）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code propertyToString}：沿类型链通用地抽出一个字符串值。
     *
     * <pre>
     *   typeName := value["type"].(string)
     *   if typeName == "" { return extractLeafValue(value) }
     *   inner := value[typeName]
     *   if 不存在 || inner == nil { return "" }
     *   return extractValue(inner)
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
     * 对照 Go {@code extractValue}：按 JSON 值的**运行时类型**分派。
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
        // Go 的 default 分支是 fmt.Sprint(v)——json 解码只产出上面六种，
        // 这一支不可达（BinaryNode/PojoNode 等不会出现在 Notion 的响应里）。
        return "";
    }

    /**
     * 对照 Go {@code extractLeafValue}：按**固定顺序**试探已知的叶子键。
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
    // 属性名集合（Go connector.go L846-868）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code extractPropertySchema}：抽出**除 title 之外**的属性名并排序。
     *
     * <p>排序是刻意的（Go 的注释）：属性名来自 map 迭代，顺序随机，
     * 不排序会让"数据库表格列序"每次都不同，进而在增量同步时造成
     * **假的内容变更**。Java 侧同样排序。</p>
     *
     * <p>Go 对每个属性值单独 {@code json.Unmarshal} 进
     * {@code struct{Type string}} 并<b>忽略错误</b>：只有"解成功且
     * {@code type == "title"}"才被排除，其余（包括解失败的字符串/数字/数组、
     * 以及 JSON {@code null}）一律收进结果。probe 实录：
     * {@code {"A":"str","B":{"type":"title"}} → ["A"]}。</p>
     */
    static List<String> extractPropertySchema(NotionPage record) {
        if (record == null || record.rawProperties == null) {
            return null;
        }
        JsonNode props = record.rawProperties;
        if (!props.isObject()) {
            // Go: json.Unmarshal(record.RawProperties, &props) 失败 → return nil
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
            // ⚠️ Go 里 json.Unmarshal 的错误是**被忽略**的
            // （`json.Unmarshal(propRaw, &prop); if prop.Type != "title"`）：
            // 值是字符串/数字/数组时解失败、Type 留空串，**照样收进结果**；
            // 只有"解成功且 type == title"才被排除。probe 实录：
            // {"A":"str","B":{"type":"title"}} → ["A"]。
            if (!"title".equals(type)) {
                propNames.add(entry.getKey());
            }
        }
        Collections.sort(propNames);
        // Go 的具名返回值是 nil 起步的切片：**一个都没收到**时回 nil（probe 里
        // 序列化成 null），而不是空切片。range 之下两者等价，但契约上要一致。
        return propNames.isEmpty() ? null : propNames;
    }

    /**
     * Go 的 {@code nil} 切片与空切片在"range"下等价，Java 侧对 {@code null}
     * 不做隐式处理，故每个调用点显式走这里——保留 {@code extractPropertySchema}
     * 返回 {@code null} 的原始语义（probe 里它序列化成 {@code null}）。
     */
    static List<String> orEmpty(List<String> propNames) {
        return propNames == null ? List.of() : propNames;
    }
}
