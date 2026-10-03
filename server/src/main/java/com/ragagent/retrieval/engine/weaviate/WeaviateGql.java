package com.ragagent.retrieval.engine.weaviate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.web.ToolJson;
import com.ragagent.retrieval.engine.weaviate.WeaviateRestClient.Json;

/**
 * Weaviate GraphQL 查询串构造——逐字节对齐既有客户端的 wire 形态
 * （{@code graphql.GetBuilder#build} / {@code filters.WhereBuilder#String} 的实测常量见
 * {@code WeaviateGqlTest}）。
 *
 * <h2>查询串形态（别改）</h2>
 * <pre>
 * {Get {Class (where:{operator: And operands:[{operator: Equal path: ["is_enabled"] valueBoolean: true}]},
 *        nearVector:{certainty: 0.7 vector: [0.1,0.2,0.3]}, limit: 10)
 *   {content source_id ... _additional{id certainty}}}}
 * </pre>
 * 要点：参数按 where → （bm25/nearVector）→ limit → offset → after 的固定序、逗号+空格分隔；
 * where 内部的段用<b>单空格</b>连接、operands 用<b>逗号无空格</b>、`operands:`/`nearVector:`/
 * `bm25:`/`where:` 后<b>无空格</b>；字符串一律双引号 + 转义（见 {@link #quote}）；数组在
 * {@code len>1 || operator∈{ContainsAny,ContainsAll,ContainsNone}} 时加方括号。
 */
public final class WeaviateGql {

    private WeaviateGql() {
    }

    // ── 字段集 ─────────────────────────────────────────────────────────────

    static final String FIELDS_EMBEDDING =
            "content source_id source_type chunk_id knowledge_id knowledge_base_id tag_id "
                    + "_additional{id certainty}";
    /** 关键词检索的字段集（_additional 取 score）。 */
    static final String FIELDS_KEYWORDS =
            "content source_id source_type chunk_id knowledge_id knowledge_base_id tag_id "
                    + "_additional{id score}";
    static final String FIELDS_VECTOR_WITH_NAMED =
            "content source_id source_type chunk_id knowledge_id knowledge_base_id tag_id "
                    + "_additional{id vectors{embedding}}";
    static final String FIELDS_ID_ONLY = "_additional{id}";

    // ── where 构造 ─────────────────────────────────────────────────────────

    /** where 操作符常量。 */
    public static final String OP_AND = "And";
    public static final String OP_EQUAL = "Equal";
    public static final String OP_NOT_EQUAL = "NotEqual";
    public static final String OP_CONTAINS_ANY = "ContainsAny";

    /** 一条过滤（或一组 operands）。 */
    public static final class Where {

        private final String operator;
        private final List<String> path = new ArrayList<>();
        private final List<Boolean> valueBooleans = new ArrayList<>();
        private final List<String> valueStrings = new ArrayList<>();
        private final List<String> valueTexts = new ArrayList<>();
        private final List<Where> operands = new ArrayList<>();
        private boolean withValueBoolean;
        private boolean withValueString;
        private boolean withValueText;

        private Where(String operator) {
            this.operator = operator;
        }

        public static Where and(List<Where> operands) {
            Where w = new Where(OP_AND);
            w.operands.addAll(operands);
            return w;
        }

        public static Where equal(String field) {
            Where w = new Where(OP_EQUAL);
            w.path.add(field);
            return w;
        }

        public static Where notEqual(String field) {
            Where w = new Where(OP_NOT_EQUAL);
            w.path.add(field);
            return w;
        }

        public static Where containsAny(String field) {
            Where w = new Where(OP_CONTAINS_ANY);
            w.path.add(field);
            return w;
        }

        public Where valueBoolean(boolean value) {
            withValueBoolean = true;
            valueBooleans.add(value);
            return this;
        }

        public Where valueString(String value) {
            withValueString = true;
            valueStrings.add(value);
            return this;
        }

        public Where valueText(String... values) {
            withValueText = true;
            valueTexts.addAll(List.of(values));
            return this;
        }

        /** 整个 where 子句：{@code where:{...}}。 */
        public String gql() {
            return "where:{" + body() + "}";
        }

        /** 不含 where: 前缀，供 operands 内嵌。 */
        String body() {
            List<String> clause = new ArrayList<>();
            if (!operator.isEmpty()) {
                clause.add("operator: " + operator);
            }
            if (!path.isEmpty()) {
                List<String> quoted = new ArrayList<>(path.size());
                for (String p : path) {
                    quoted.add("\"" + p + "\"");
                }
                clause.add("path: [" + String.join(",", quoted) + "]");
            }
            if (withValueBoolean) {
                clause.add("valueBoolean: " + formatValues(valueBooleans, operator));
            }
            if (withValueString) {
                clause.add("valueString: " + formatValues(valueStrings, operator));
            }
            if (withValueText) {
                clause.add("valueText: " + formatValues(valueTexts, operator));
            }
            if (!operands.isEmpty()) {
                List<String> rendered = new ArrayList<>(operands.size());
                for (Where operand : operands) {
                    rendered.add("{" + operand.body() + "}");
                }
                clause.add("operands:[" + String.join(",", rendered) + "]");
            }
            return String.join(" ", clause);
        }

        /**
         * 批量删除用的 JSON 形态：单值且非 Contains*
         * 用标量键（{@code valueText}/{@code valueString}），否则用 {@code valueTextArray} 等。
         */
        public ObjectNode json() {
            ObjectNode node = Json.object();
            if (!operator.isEmpty()) {
                node.put("operator", operator);
            }
            if (!path.isEmpty()) {
                ArrayNode p = node.putArray("path");
                path.forEach(p::add);
            }
            if (withValueBoolean) {
                putJsonValues(node, "valueBoolean", "valueBooleanArray", valueBooleans);
            }
            if (withValueString) {
                putJsonValues(node, "valueString", "valueStringArray", valueStrings);
            }
            if (withValueText) {
                putJsonValues(node, "valueText", "valueTextArray", valueTexts);
            }
            if (!operands.isEmpty()) {
                ArrayNode array = node.putArray("operands");
                operands.forEach(op -> array.add(op.json()));
            }
            return node;
        }

        private <T> void putJsonValues(ObjectNode node, String scalarKey, String arrayKey,
                                       List<T> values) {
            if (values.size() == 1 && !isContainsOperator(operator)) {
                T v = values.get(0);
                if (v instanceof Boolean b) {
                    node.put(scalarKey, b.booleanValue());
                } else {
                    node.put(scalarKey, String.valueOf(v));
                }
                return;
            }
            ArrayNode array = node.putArray(arrayKey);
            for (T v : values) {
                if (v instanceof Boolean b) {
                    array.add(b.booleanValue());
                } else {
                    array.add(String.valueOf(v));
                }
            }
        }
    }

    /** Contains* 操作符判定。 */
    static boolean isContainsOperator(String operator) {
        return OP_CONTAINS_ANY.equals(operator)
                || "ContainsAll".equals(operator)
                || "ContainsNone".equals(operator);
    }

    /** 值格式化：字符串走 {@link #quoteGo} 引号；多值或 Contains* → 方括号数组。 */
    static String formatValues(List<?> values, String operator) {
        List<String> clause = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof String s) {
                clause.add(quoteGo(s));
            } else {
                clause.add(String.valueOf(value));
            }
        }
        String joined = String.join(",", clause);
        if (values.size() > 1 || isContainsOperator(operator)) {
            return "[" + joined + "]";
        }
        return joined;
    }

    // ── Get 查询构造（固定参数序，见类注释要点） ────────────────────────────

    /** 向量检索：{@code (where, nearVector: {certainty, vector}, limit)}。 */
    public static String vectorQuery(String className, Where where, int limit, float[] vector,
                                     float certainty) {
        List<String> args = new ArrayList<>();
        if (where != null) {
            args.add(where.gql());
        }
        args.add("nearVector:{certainty: " + floatGo(certainty) + " vector: "
                + vectorLiteral(vector) + "}");
        args.add("limit: " + limit);
        return getQuery(className, args, FIELDS_EMBEDDING);
    }

    /** 关键词检索：{@code (where, bm25: {query, properties}, limit)}（空 query 省略 query 段）。 */
    public static String bm25Query(String className, Where where, int limit, String query,
                                   List<String> properties) {
        List<String> args = new ArrayList<>();
        if (where != null) {
            args.add(where.gql());
        }
        args.add(bm25Arg(query, properties));
        args.add("limit: " + limit);
        return getQuery(className, args, FIELDS_KEYWORDS);
    }

    /** bm25 参数：{@code bm25:{query: "…", properties: ["…"]}}。 */
    static String bm25Arg(String query, List<String> properties) {
        List<String> clause = new ArrayList<>();
        if (query != null && !query.isEmpty()) {
            clause.add("query: " + quoteGo(query));
        }
        if (properties != null && !properties.isEmpty()) {
            List<String> quoted = new ArrayList<>(properties.size());
            for (String p : properties) {
                quoted.add(quoteGo(p));
            }
            clause.add("properties: [" + String.join(",", quoted) + "]");
        }
        return "bm25:{" + String.join(", ", clause) + "}";
    }

    /**
     * 拷贝分页：{@code (where, limit, offset)} + {@code _additional{id vectors{embedding}}}。
     *
     * <p><b>与旧客户端的有意修正（见 known-issues）</b>：旧客户端用
     * {@code where + limit + after}——服务端直接拒绝（{@code cursor api: invalid 'after'
     * parameter: where cannot be set with after and limit parameters}），且命名向量类下
     * {@code _additional{vector}} 恒空。本仓改为 {@code where + limit + offset} +
     * {@code vectors{embedding}}（服务端实测可用）。</p>
     */
    public static String copyPageQuery(String className, Where where, int limit, int offset) {
        List<String> args = new ArrayList<>();
        if (where != null) {
            args.add(where.gql());
        }
        args.add("limit: " + limit);
        args.add("offset: " + offset);
        return getQuery(className, args, FIELDS_VECTOR_WITH_NAMED);
    }

    /** move 列举：{@code (where, limit)} + {@code _additional{id}}。 */
    public static String moveListQuery(String className, Where where, int limit) {
        List<String> args = new ArrayList<>();
        if (where != null) {
            args.add(where.gql());
        }
        args.add("limit: " + limit);
        return getQuery(className, args, FIELDS_ID_ONLY);
    }

    private static String getQuery(String className, List<String> args, String fields) {
        if (args.isEmpty()) {
            return "{Get {" + className + " {" + fields + "}}}";
        }
        return "{Get {" + className + " (" + String.join(", ", args) + ") {" + fields + "}}}";
    }

    // ── 字面量 ─────────────────────────────────────────────────────────────

    /**
     * 字符串引号：委托 {@link ToolJson#quoted}（标准 Jackson 转义）。
     *
     * <p><b>2026-10-03（B43）</b>：手写复刻已退役（Go 版下线）——原实现与
     * {@code GoQuoting.quoteGo} 同源拷贝，属第 5 份重复；现收敛为 Java 原生做法。</p>
     */
    static String quoteGo(String s) {
        return ToolJson.quoted(s);
    }

    /** 向量字面量（最短表示、整数值不带小数）。 */
    static String vectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        List<String> parts = new ArrayList<>(vector.length);
        for (float v : vector) {
            parts.add(floatGo(v));
        }
        return "[" + String.join(",", parts) + "]";
    }

    /**
     * 浮点字面量：JSON 最短表示（{@code 1} 不带 {@code .0}；极小/极大值走
     * {@code 1e-07} 形态）。Weaviate 的 certainty/向量元素都在这个可读区间内。
     */
    static String floatGo(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            throw new IllegalArgumentException("non-finite float in GraphQL literal: " + v);
        }
        if (v == 0f) {
            return Float.floatToRawIntBits(v) < 0 ? "-0" : "0";
        }
        String sign = v < 0 ? "-" : "";
        BigDecimal decimal = new BigDecimal(Float.toString(Math.abs(v)));
        String digits = decimal.unscaledValue().toString();
        int dp = digits.length() - decimal.scale();
        while (digits.length() > 1 && digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        int exp = dp - 1;
        if (exp < -6 || exp >= 21) {
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            String expSign = exp < 0 ? "-" : "+";
            return sign + mantissa + "e" + expSign + String.format("%02d", Math.abs(exp));
        }
        if (dp <= 0) {
            return sign + "0." + "0".repeat(-dp) + digits;
        }
        if (dp >= digits.length()) {
            return sign + digits + "0".repeat(dp - digits.length());
        }
        return sign + digits.substring(0, dp) + "." + digits.substring(dp);
    }
}
