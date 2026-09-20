package com.ragagent.agent.tools;

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 参数类型矫正（对照 Go {@code param_cast.go} 的 {@code CastParams}，逐字移植）。
 *
 * <p>LLM 有时返回错误类型（{@code "true"} 代替 true、{@code "123"} 代替 123）。
 * 本类按工具参数的 JSON Schema 做安全转型：</p>
 * <ul>
 *   <li>array ← 字符串：先试 JSON 解析（{@code "[{...}]"} → 数组），失败退化为单元素字符串数组；</li>
 *   <li>boolean ← "true"/"1"/"yes"→true、"false"/"0"/"no"→false（大小写不敏感）；数字 0/1 → false/true；</li>
 *   <li>integer ← 整数字符串；整数值浮点（42.0 → 42）；</li>
 *   <li>number ← 可解析字符串（实录：{@code "1e21"} → 1e+21）；</li>
 *   <li>string ← bool / 数字（数字按 strconv 'f' -1：{@code 123.5} → "123.5"）。</li>
 * </ul>
 *
 * <p>schema 缺失/不可解析/args 不可解析时原样返回。<b>发生任一转型后整棵 args 按
 * {@link GoJsonCodec}（Go json.Marshal 语义：键序重排 + HTML 转义）重新序列化</b>——
 * 这是 Go 的既有行为（map 重排），不是本类的选择（实录 CAST 17/18 钉死）。</p>
 */
public final class ParamCaster {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ParamCaster() {
    }

    public static JsonNode castParams(JsonNode args, JsonNode schema) {
        if (schema == null || schema.isEmpty() || args == null || args.isEmpty()) {
            return args;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return args;
        }
        if (!args.isObject()) {
            return args;
        }

        ObjectNode argsMap = ((ObjectNode) args).deepCopy();
        boolean changed = false;

        Iterator<Map.Entry<String, JsonNode>> it = argsMap.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            JsonNode propDef = properties.get(key);
            if (propDef == null) {
                continue;
            }
            if (!propDef.isObject()) {
                continue;
            }
            JsonNode typeNode = propDef.get("type");
            String targetType = typeNode != null && typeNode.isTextual() ? typeNode.textValue() : "";
            if (targetType.isEmpty()) {
                continue;
            }

            JsonNode newVal = castValue(entry.getValue(), targetType);
            if (newVal != null) {
                argsMap.set(key, newVal);
                changed = true;
            }
        }

        if (!changed) {
            return args;
        }
        // 重新序列化走 Go 的编码器（键序重排 + HTML 转义），解析失败原样返回。
        try {
            String encoded = GoJsonCodec.write(argsMap);
            return ObjectMapperHolder.MAPPER.readTree(encoded);
        } catch (Exception e) {
            return args;
        }
    }

    /**
     * 尝试把 val 转成 targetType；返回 null 表示不转（对照 Go 的 (newValue, bool) 双返回）。
     */
    static JsonNode castValue(JsonNode val, String targetType) {
        switch (targetType) {
            case "array": {
                if (val.isTextual()) {
                    String s = val.textValue();
                    // 先试 JSON 解析（处理 "[{...}]" → 数组）
                    try {
                        JsonNode parsed = ObjectMapperHolder.MAPPER.readTree(s);
                        if (parsed != null && parsed.isArray()) {
                            return parsed;
                        }
                    } catch (Exception ignored) {
                        // fall through
                    }
                    // 退化：单字符串 → 字符串数组
                    ArrayNode arr = F.arrayNode();
                    arr.add(s);
                    return arr;
                }
                break;
            }
            case "boolean": {
                if (val.isTextual()) {
                    String lower = val.textValue().toLowerCase();
                    switch (lower) {
                        case "true", "1", "yes":
                            return F.booleanNode(true);
                        case "false", "0", "no":
                            return F.booleanNode(false);
                        default:
                            return null;
                    }
                }
                // JSON 数字 0/1 → bool
                if (val.isNumber()) {
                    double n = val.doubleValue();
                    if (n == 0) {
                        return F.booleanNode(false);
                    }
                    if (n == 1) {
                        return F.booleanNode(true);
                    }
                }
                break;
            }
            case "integer": {
                if (val.isTextual()) {
                    try {
                        long i = Long.parseLong(val.textValue());
                        return F.numberNode(i);
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
                // Go 里 JSON 数字是 float64；整数值浮点转 int64
                if (val.isNumber()) {
                    double f = val.doubleValue();
                    if (f == (double) (long) f) {
                        return F.numberNode((long) f);
                    }
                }
                break;
            }
            case "number": {
                if (val.isTextual()) {
                    try {
                        double f = Double.parseDouble(val.textValue());
                        if (!Double.isNaN(f) && !Double.isInfinite(f)) {
                            return F.numberNode(f);
                        }
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
                break;
            }
            case "string": {
                // 非字符串 → 字符串（数字或 bool 被当成非字符串传来）
                if (val.isBoolean()) {
                    return F.textNode(val.booleanValue() ? "true" : "false");
                }
                if (val.isFloatingPointNumber()) {
                    // 对照 strconv.FormatFloat(v, 'f', -1, 64)（实录：123.5 → "123.5"、42.0 → "42"）
                    return F.textNode(goFormatFloat(val.doubleValue()));
                }
                if (val.isIntegralNumber()) {
                    return F.textNode(String.valueOf(val.longValue()));
                }
                break;
            }
            default:
                break;
        }
        return val;
    }

    /** strconv.FormatFloat(v, 'f', -1, 64)：最短 'f' 形态（绝无指数）。 */
    private static String goFormatFloat(double v) {
        String s = GoDoubleSerializer.format(v);
        int e = s.indexOf('e');
        if (e < 0) {
            return s;
        }
        // GoDoubleSerializer 对 |v|<1e-6 或 ≥1e21 走 'e' 形态；'f' -1 需展开为定点
        // （数字位沿用最短往返位数，正是 Go 的做法：先取最短十进制，再按 'f' 排布）。
        String mant = s.substring(0, e);
        int exp = Integer.parseInt(s.substring(e + 1));
        return new java.math.BigDecimal(mant).scaleByPowerOfTen(exp).toPlainString();
    }
    private static final class ObjectMapperHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
