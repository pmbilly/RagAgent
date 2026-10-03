package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoDoubleSerializer;

/**
 * 通用参数校验。
 *
 * <p>按工具声明的 JSON Schema 检查 args，支持的检查：required（存在且非 null）、
 * type（string/number/integer/boolean/array/object）、enum、minimum/maximum、
 * minLength/maxLength。错误文案（含工具参数名的格式化）固定如下：</p>
 * <pre>
 *   required parameter '%s' is missing
 *   parameter '%s' should be type '%s'
 *   parameter '%s' must be one of [%s]
 *   parameter '%s' must be >= %v        // 值走最短浮点格式（如 ">= 1"）
 *   parameter '%s' must be <= %v
 *   parameter '%s' must have at least %d characters
 *   parameter '%s' must have at most %d characters
 * </pre>
 *
 * <p>已知差异：多参数错误按参数在 JSON 里的出现序排列——required 错误恒在最前
 * （schema 的 required 数组序）。</p>
 */
public final class ParamValidator {

    private ParamValidator() {
    }

    /** 单条校验失败（参数名 + 文案）。 */
    public record ValidationError(String param, String message) {
    }

    /**
     * 按 schema 校验 args；合法返回空列表。schema/args 为空或不可解析时不校验。
     * 额外参数放行（LLM 有时会加）。
     */
    public static List<ValidationError> validateParams(JsonNode args, JsonNode schema) {
        // {}（空对象）照样走 required 检查
        // （实测：对 {} 参数执行 required 校验并报 "required parameter ... is missing"）。
        if (schema == null || schema.isEmpty() || args == null) {
            return List.of();
        }
        if (!schema.isObject() || !args.isObject()) {
            return List.of();
        }

        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return List.of();
        }

        List<ValidationError> errs = new ArrayList<>();

        // required 检查
        JsonNode reqRaw = schema.get("required");
        if (reqRaw != null && reqRaw.isArray()) {
            for (JsonNode r : reqRaw) {
                if (!r.isTextual()) {
                    continue;
                }
                String fieldName = r.textValue();
                JsonNode val = args.get(fieldName);
                if (val == null || val.isNull()) {
                    errs.add(new ValidationError(fieldName,
                            "required parameter '" + fieldName + "' is missing"));
                }
            }
        }

        // 逐个已提供参数按其 property schema 校验
        Iterator<Map.Entry<String, JsonNode>> it = args.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            JsonNode propDef = properties.get(key);
            if (propDef == null) {
                continue; // 额外参数放行
            }
            if (!propDef.isObject()) {
                continue;
            }
            errs.addAll(validateProperty(key, val, propDef));
        }

        return errs;
    }

    /** 单参数校验（type 错则短路返回，再 enum、数值界、字符串长度）。 */
    private static List<ValidationError> validateProperty(String name, JsonNode val, JsonNode prop) {
        if (val == null || val.isNull()) {
            return List.of(); // null 由 required 检查处理
        }

        List<ValidationError> errs = new ArrayList<>();
        JsonNode typeNode = prop.get("type");
        String targetType = typeNode != null && typeNode.isTextual() ? typeNode.textValue() : "";

        // 类型检查
        if (!targetType.isEmpty() && !checkType(val, targetType)) {
            errs.add(new ValidationError(name,
                    "parameter '" + name + "' should be type '" + targetType + "'"));
            return errs; // 类型错了就不再做后续检查
        }

        // enum 检查
        JsonNode enumRaw = prop.get("enum");
        if (enumRaw != null && enumRaw.isArray() && !enumRaw.isEmpty()) {
            if (!isInEnum(val, enumRaw)) {
                String allowed = formatEnum(enumRaw);
                errs.add(new ValidationError(name,
                        "parameter '" + name + "' must be one of [" + allowed + "]"));
            }
        }

        // 数值界
        if (targetType.equals("number") || targetType.equals("integer")) {
            double numVal = toFloat64(val);
            double[] minVal = getFloat(prop, "minimum");
            if (minVal != null && numVal < minVal[0]) {
                errs.add(new ValidationError(name,
                        "parameter '" + name + "' must be >= " + goValue(minVal[0])));
            }
            double[] maxVal = getFloat(prop, "maximum");
            if (maxVal != null && numVal > maxVal[0]) {
                errs.add(new ValidationError(name,
                        "parameter '" + name + "' must be <= " + goValue(maxVal[0])));
            }
        }

        // 字符串长度按 UTF-8 字节计（CJK 多字节字符按字节计账）
        if (targetType.equals("string") && val.isTextual()) {
            String s = val.textValue();
            int byteLen = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            double[] minLen = getFloat(prop, "minLength");
            if (minLen != null && byteLen < minLen[0]) {
                errs.add(new ValidationError(name,
                        "parameter '" + name + "' must have at least " + (int) minLen[0] + " characters"));
            }
            double[] maxLen = getFloat(prop, "maxLength");
            if (maxLen != null && byteLen > maxLen[0]) {
                errs.add(new ValidationError(name,
                        "parameter '" + name + "' must have at most " + (int) maxLen[0] + " characters"));
            }
        }

        return errs;
    }

    /** 类型检查（integer = 数值且等于自身的整型化）。 */
    private static boolean checkType(JsonNode val, String targetType) {
        return switch (targetType) {
            case "string" -> val.isTextual();
            case "number" -> val.isNumber();
            case "integer" -> {
                if (!val.isNumber()) {
                    yield false;
                }
                double f = val.doubleValue();
                yield f == (double) (long) f;
            }
            case "boolean" -> val.isBoolean();
            case "array" -> val.isArray();
            case "object" -> val.isObject();
            default -> true; // 未知类型不拒绝
        };
    }

    /** enum 成员判定：按节点文本形态字符串相等（1 == 1.0 == "1" 视为相同）。 */
    private static boolean isInEnum(JsonNode val, JsonNode enumList) {
        String v = goValueOfNode(val);
        for (JsonNode e : enumList) {
            if (goValueOfNode(e).equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** enum 报错文案格式化（", " 连接）。 */
    private static String formatEnum(JsonNode enumList) {
        List<String> parts = new ArrayList<>(enumList.size());
        for (JsonNode e : enumList) {
            parts.add(goValueOfNode(e));
        }
        return String.join(", ", parts);
    }

    /** schema 里的浮点数（缺失/非数返回 null）。 */
    private static double[] getFloat(JsonNode m, String key) {
        JsonNode v = m.get(key);
        if (v == null || !v.isNumber()) {
            return null;
        }
        return new double[] {v.doubleValue()};
    }

    /** 数值化（非数返回 0）。 */
    private static double toFloat64(JsonNode val) {
        return val.isNumber() ? val.doubleValue() : 0;
    }

    /** 节点文本形态（字符串原样、bool true/false、数字走最短浮点格式）。 */
    private static String goValueOfNode(JsonNode n) {
        if (n.isNumber()) {
            return goValue(n.doubleValue());
        }
        if (n.isBoolean()) {
            return n.booleanValue() ? "true" : "false";
        }
        if (n.isNull()) {
            return "<nil>";
        }
        return n.isTextual() ? n.textValue() : n.toString();
    }

    /** 浮点文本（'g' 最短形态：1 → "1"、0.5 → "0.5"）。 */
    private static String goValue(double d) {
        return GoDoubleSerializer.format(d);
    }

    /**
     * 错误列表 → 人读文案："Parameter validation failed: " + join("; ")；空列表 → ""。
     */
    public static String formatValidationErrors(List<ValidationError> errs) {
        if (errs == null || errs.isEmpty()) {
            return "";
        }
        List<String> msgs = new ArrayList<>(errs.size());
        for (ValidationError e : errs) {
            msgs.add(e.message());
        }
        return "Parameter validation failed: " + String.join("; ", msgs);
    }
}
