package com.ragagent.common.web;

/**
 * 把「非法 JSON 请求体」的解析错误翻译成 Go {@code encoding/json} 的措辞。
 *
 * <h2>为什么需要它</h2>
 * <p>Go handler 用 {@code c.ShouldBindJSON}，解析失败时把
 * {@code err.Error()} 原样放进 AppError 的 {@code message}
 * （例如 session 的 400：{@code invalid character 'o' in literal null (expecting 'u')}）。
 * Jackson 的措辞完全不同，golden 锁的是 Go 的字节——所以顶层形态的错误要仿真。</p>
 *
 * <h2>覆盖范围（与 golden 录制的用例对齐）</h2>
 * <ul>
 *   <li>空 body → {@code EOF}；</li>
 *   <li>以 {@code n}/{@code t}/{@code f} 开头的字面量（null/true/false）中间坏掉 →
 *       {@code invalid character 'x' in literal <名> (expecting '<期望字符>')}；
 *       例如 {@code not-json} → {@code invalid character 'o' in literal null (expecting 'u')}；</li>
 *   <li>字面量完整但后面还有内容 →
 *       {@code invalid character 'x' after top-level value}；</li>
 *   <li>其余顶层起始字符（数字/引号/{/[ 之外的可打印符）→
 *       {@code invalid character 'x' looking for beginning of value}。</li>
 * </ul>
 *
 * <p>⚠️ <b>已知差异（刻意保留）</b>：body 以 {@code { [ " 数字 - 开头但深层结构坏掉时，
 * Go 与 Jackson 的措辞差异很大，这里回落到 Jackson 的消息（调用方传入）。
 * 前端正常请求不会触发该分支；若未来 golden 录到此类用例，再逐字补齐。</p>
 */
public final class GoJsonBindError {

    private GoJsonBindError() {
    }

    /**
     * @param rawBody        原始请求体（可能为 null/空）
     * @param jacksonMessage Jackson 解析失败时的消息（深结构错误的回落值）
     */
    public static String message(String rawBody, String jacksonMessage) {
        if (rawBody == null || rawBody.isEmpty()) {
            return "EOF";
        }
        int i = 0;
        while (i < rawBody.length() && Character.isWhitespace(rawBody.charAt(i))) {
            i++;
        }
        if (i >= rawBody.length()) {
            return "EOF";
        }
        char c = rawBody.charAt(i);
        return switch (c) {
            case 'n' -> literalError(rawBody, i, "null", jacksonMessage);
            case 't' -> literalError(rawBody, i, "true", jacksonMessage);
            case 'f' -> literalError(rawBody, i, "false", jacksonMessage);
            case '{', '[', '"', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                    jacksonMessage;
            default -> "invalid character '" + c + "' looking for beginning of value";
        };
    }

    /**
     * 仿真 Go 对 null/true/false 字面量的逐字符扫描：
     * 第 k 个字符不匹配 → {@code invalid character '<got>' in literal <名> (expecting '<期望>')}。
     * 字面量完整匹配则返回 null（回落 Jackson——例如 body 就是 {@code null}，
     * Go 会零值绑定不报错，Jackson 返回 null，由调用方按零值处理）。
     */
    private static String literalError(String body, int start, String literal, String jacksonMessage) {
        for (int k = 1; k < literal.length(); k++) {
            int idx = start + k;
            char expected = literal.charAt(k);
            if (idx >= body.length()) {
                return "unexpected end of JSON input";
            }
            char got = body.charAt(idx);
            if (got != expected) {
                return "invalid character '" + got + "' in literal " + literal
                        + " (expecting '" + expected + "')";
            }
        }
        int after = start + literal.length();
        if (after < body.length()) {
            return "invalid character '" + body.charAt(after) + "' after top-level value";
        }
        return jacksonMessage;
    }

    // ── 字段级类型错误（W5α2 起，golden 驱动登记） ─────────────────────────

    /**
     * 已登记的 (Go 结构体.字段 json 名) → Go 类型 三元组——只登记 golden 实录钉住的
     * 条目（Go uint64/int64/float64/[]string 的区分不能从 Java 类型推断，逐条录）。
     */
    private static final java.util.Map<String, String> FIELD_GO_TYPES = java.util.Map.of(
            "CreateKnowledgeQARequest.agent_source_tenant_id", "uint64");

    /**
     * 仿真 Go 的字段级类型错误：{@code json: cannot unmarshal <kind> into Go struct
     * field <Struct>.<jsonField> of type <goType>}。未登记的字段返回 null（调用方回落
     * Jackson 措辞——与深结构错误的既定处理一致）。
     *
     * @param structName Go 结构体名（Java 类 simpleName 与 Go 同名时直取）
     * @param jsonField  出错字段的 json 名（Jackson path 首段）
     * @param valueKind  实际值的 JSON 种类：string/number/bool/object/array
     */
    public static String fieldTypeError(String structName, String jsonField, String valueKind) {
        String goType = FIELD_GO_TYPES.get(structName + "." + jsonField);
        if (goType == null) {
            return null;
        }
        return "json: cannot unmarshal " + valueKind + " into Go struct field "
                + structName + "." + jsonField + " of type " + goType;
    }

    /** Jackson 树节点 → Go 措辞的值种类（unmarshal 错误的第一个词）。 */
    public static String valueKind(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "bool";
        }
        if (node.isArray()) {
            return "array";
        }
        return "object";
    }
}
