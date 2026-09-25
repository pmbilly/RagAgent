package com.ragagent.retrieval.engine.milvus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Milvus 过滤表达式构造——对照 Go {@code milvus/filter.go}（算子表 / 逻辑括号形状 /
 * 字面量转义）。
 *
 * <h2>与 Go 的差异（备案）</h2>
 * Go 走 SDK 的<b>模板参数</b>（`field == {field_1}` + {@code WithTemplateParam}）；Milvus
 * <b>REST v2 不支持模板参数</b>（实测：{@code filterParams} 被忽略 → "the value of expression
 * template variable name {ids} is not found"），故本仓把值<b>按 Go 的字面量规则内联</b>
 * （{@code formatValue}/{@code escapeDoubleQuotes} 同款：字符串加双引号并转义 {@code "}、
 * 布尔 true/false、数值原样）。表达式的算子、括号与 and/or 结合形状与 Go 逐字一致。
 */
public final class MilvusFilter {

    // ── 算子（照 Go 常量） ─────────────────────────────────────────────────

    public static final String OP_AND = "and";
    public static final String OP_OR = "or";
    public static final String OP_EQ = "eq";
    public static final String OP_NE = "ne";
    public static final String OP_GT = "gt";
    public static final String OP_GTE = "gte";
    public static final String OP_LT = "lt";
    public static final String OP_LTE = "lte";
    public static final String OP_IN = "in";
    public static final String OP_NOT_IN = "not in";
    public static final String OP_LIKE = "like";
    public static final String OP_NOT_LIKE = "not like";
    public static final String OP_BETWEEN = "between";

    /** 对照 {@code comparisonOperators}。 */
    static final Map<String, String> COMPARISON_OPERATORS = Map.of(
            OP_EQ, "==",
            OP_NE, "!=",
            OP_GT, ">",
            OP_GTE, ">=",
            OP_LT, "<",
            OP_LTE, "<=",
            OP_LIKE, "like",
            OP_NOT_LIKE, "not like");

    private MilvusFilter() {
    }

    /** 一条过滤条件（比较 / 逻辑 / in / between 统一载体，照 {@code universalFilterCondition}）。 */
    public static final class Condition {

        final String field;
        final String operator;
        final Object value;

        private Condition(String field, String operator, Object value) {
            this.field = field;
            this.operator = operator;
            this.value = value;
        }

        public static Condition comparison(String field, String operator, Object value) {
            return new Condition(field, operator, value);
        }

        public static Condition equal(String field, Object value) {
            return new Condition(field, OP_EQ, value);
        }

        public static Condition notEqual(String field, Object value) {
            return new Condition(field, OP_NE, value);
        }

        public static Condition in(String field, List<String> values) {
            return new Condition(field, OP_IN, new ArrayList<>(values));
        }

        public static Condition notIn(String field, List<String> values) {
            return new Condition(field, OP_NOT_IN, new ArrayList<>(values));
        }

        public static Condition and(List<Condition> children) {
            return new Condition("", OP_AND, new ArrayList<>(children));
        }
    }

    /**
     * 对照 {@code filter.Convert}：返回 Milvus 表达式串（值已内联）。失败文案照 Go
     * （{@code milvus filter condition is nil} 等）——它们是调用方与测试的契约。
     */
    public static String expr(Condition condition) {
        return convert(condition);
    }

    private static String convert(Condition cond) {
        if (cond == null) {
            throw new IllegalArgumentException("milvus filter condition is nil");
        }
        return switch (cond.operator) {
            case OP_EQ, OP_NE, OP_GT, OP_GTE, OP_LT, OP_LTE, OP_LIKE, OP_NOT_LIKE ->
                    convertComparison(cond);
            case OP_AND, OP_OR -> convertLogical(cond);
            case OP_IN, OP_NOT_IN -> convertIn(cond);
            case OP_BETWEEN -> convertBetween(cond);
            default -> throw new IllegalArgumentException(
                    "unsupported operator: " + cond.operator);
        };
    }

    private static String convertComparison(Condition cond) {
        if (cond.field == null || cond.field.isEmpty() || cond.value == null) {
            throw new IllegalArgumentException("milvus filter condition is nil");
        }
        String operator = COMPARISON_OPERATORS.get(cond.operator);
        if (operator == null) {
            throw new IllegalArgumentException(
                    "unsupported comparison operator: " + cond.operator);
        }
        return cond.field + " " + operator + " " + formatValue(cond.value);
    }

    /** 对照 {@code convertLogicalCondition}：(a) and (b)，左结合嵌套括号。 */
    private static String convertLogical(Condition cond) {
        if (cond.value == null) {
            throw new IllegalArgumentException("milvus filter condition is nil");
        }
        if (!(cond.value instanceof List<?> conds)) {
            throw new IllegalArgumentException("invalid logical condition value type");
        }
        String joined = null;
        for (Object child : conds) {
            if (!(child instanceof Condition c)) {
                throw new IllegalArgumentException("invalid logical condition value type");
            }
            String childExpr = convert(c);
            if (childExpr.isEmpty()) {
                continue;
            }
            joined = joined == null
                    ? childExpr
                    : "(" + joined + ") " + cond.operator.toLowerCase()
                            + " (" + childExpr + ")";
        }
        if (joined == null) {
            throw new IllegalArgumentException("empty logical condition");
        }
        return joined;
    }

    private static String convertIn(Condition cond) {
        if (cond.field == null || cond.field.isEmpty() || cond.value == null) {
            throw new IllegalArgumentException("milvus filter condition is nil");
        }
        if (!(cond.value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "in operator value must be a slice with at least one value: " + cond.value);
        }
        List<String> rendered = new ArrayList<>(values.size());
        for (Object v : values) {
            rendered.add(formatValue(v));
        }
        return cond.field + " " + cond.operator.toLowerCase() + " ["
                + String.join(",", rendered) + "]";
    }

    private static String convertBetween(Condition cond) {
        if (cond.field == null || cond.field.isEmpty() || cond.value == null) {
            throw new IllegalArgumentException("milvus filter condition is nil");
        }
        if (!(cond.value instanceof List<?> values) || values.size() != 2) {
            throw new IllegalArgumentException(
                    "between operator value must be a slice with two elements: " + cond.value);
        }
        return cond.field + " >= " + formatValue(values.get(0)) + " and " + cond.field
                + " <= " + formatValue(values.get(1));
    }

    /**
     * 对照 {@code formatValue}：字符串 → {@code "…"}（转义 {@code "}）；布尔 → true/false；
     * 数值 → {@code %d}/{@code %v} 形态；其它 → {@code "%v"} 引号包裹。
     */
    public static String formatValue(Object value) {
        if (value == null) {
            return "\"<nil>\"";
        }
        if (value instanceof String s) {
            return "\"" + escapeDoubleQuotes(s) + "\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return String.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return MilvusNumbers.floatGo(((Number) value).doubleValue());
        }
        return "\"" + value + "\"";
    }

    /** 对照 {@code escapeDoubleQuotes}：只转义双引号（照 Go，不碰反斜杠）。 */
    static String escapeDoubleQuotes(String s) {
        return s.replace("\"", "\\\"");
    }
}
