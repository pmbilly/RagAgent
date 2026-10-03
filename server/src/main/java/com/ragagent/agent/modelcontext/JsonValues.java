package com.ragagent.agent.modelcontext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * modelcontext 包内的 JSON 语义小工具：解析容错与 double 归一。
 * 编码统一走 agent.tools 的 ToolJson（map 键排序 + HTML 转义 + 64 位浮点）。
 */
final class JsonValues {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private JsonValues() {
    }

    /** 解析失败返回 null。 */
    static JsonNode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 一切 JSON 数字都按 double 读入。凡是要重新序列化的树，
     * 先把整型/大数/Decimal 节点归一成 DoubleNode，编码字节才稳定
     * （大整数超出 double 精度时按同样规则损失精度）。
     */
    static JsonNode goFloatTree(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ObjectNode out = MAPPER.createObjectNode();
            var fields = obj.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                out.set(e.getKey(), goFloatTree(e.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : arr) {
                out.add(goFloatTree(item));
            }
            return out;
        }
        if (node.isNumber() && !node.isFloatingPointNumber()) {
            return new DoubleNode(node.doubleValue());
        }
        return node;
    }

    /**
     * JSON 值等价比较：数字一律按 double 语义、对象键序无关的深比较。
     * 任一侧解析失败 → 退回字符串相等。
     */
    static boolean jsonEquivalent(String left, String right) {
        JsonNode lv = parse(left);
        JsonNode rv = parse(right);
        if (lv == null || rv == null) {
            return left.equals(right);
        }
        return deepEquals(lv, rv);
    }

    private static boolean deepEquals(JsonNode a, JsonNode b) {
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            var fields = a.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                JsonNode bv = b.get(e.getKey());
                if (bv == null || !deepEquals(e.getValue(), bv)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!deepEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.doubleValue() == b.doubleValue();
        }
        if (a.isTextual() && b.isTextual()) {
            return a.asText().equals(b.asText());
        }
        return a.equals(b);
    }
}
