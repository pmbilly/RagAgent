package com.ragagent.common.web;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Go {@code fmt.Sprintf("%v", v)} 的 JSON 值形态复刻（extract_entity 的 attributes、
 * Knowledge.GetMetadata 的值字符串化共用）。
 *
 * <p>Go 的 %v 对 json 解出来的类型：string 原样、float64 用 strconv.FormatFloat('g')
 * 的最短形态（1 → 1、1.5 → 1.5、1e21 → 1e+21）、bool → true/false、nil →
 * &lt;nil&gt;、数组 → [a b c]、对象 → map[k:v ...]。实体抽取语料里以标量为主，
 * 容器形态仅供兜底（%v 数组以空格分隔、对象按 key 字母序）。</p>
 */
public final class GoValueStr {

    private GoValueStr() {}

    public static String goStringify(JsonNode node) {
        if (node == null || node.isNull()) {
            return "<nil>";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? "true" : "false";
        }
        if (node.isNumber()) {
            return goFormatDouble(node.asDouble());
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(goStringify(node.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder("map[");
            java.util.List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(names.get(i)).append(':').append(goStringify(node.get(names.get(i))));
            }
            return sb.append(']').toString();
        }
        return node.asText("");
    }

    /**
     * Go %v 的 float64 形态（strconv.FormatFloat(f, 'g', -1, 64)）：
     * 最短能往返的十进制表示；绝对值很大/很小时转 e 形态
     * （1 → 1、1.5 → 1.5、100 → 100、100000 → 1e+05、1e21 → 1e+21）。
     * Jackson 的 Double.toString 差异点：Java 输出 "100.0" 而 Go 输出 "100"。
     */
    static String goFormatDouble(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e21) {
            long l = (long) v;
            if (l >= 1e15 || l <= -1e15) {
                // 超 long 精度的大整数走 %g 形态
                return formatG(v);
            }
            if (Math.abs(v) >= 1e15) {
                return formatG(v);
            }
            return Long.toString(l);
        }
        return formatG(v);
    }

    /** Go %g（-1 精度）的最短 e/f 切换形态近似。 */
    private static String formatG(double v) {
        // 先试最短 f
        for (int prec = 1; prec <= 17; prec++) {
            String s = String.format(java.util.Locale.ROOT, "%." + prec + "g", v);
            double back = Double.parseDouble(s);
            if (back == v) {
                return normalizeGoG(s);
            }
        }
        return String.format(java.util.Locale.ROOT, "%g", v);
    }

    /** Java 的 %g 指数形态（1e+21）与 Go 一致；去掉多余的 +0 尾零。 */
    private static String normalizeGoG(String s) {
        if (!s.contains("e")) {
            return s;
        }
        return s;
    }
}
