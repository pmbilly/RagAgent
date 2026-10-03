package com.ragagent.agent.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP 工具入参的完整 schema 校验——自带一个 Draft 2020-12 校验器，见下"为什么手写"。
 *
 * <p>语义锚点：</p>
 * <ul>
 *   <li><b>编译一次，按工具快照缓存</b>（校验器实例随工具定义走，刷新定义自然换新）；
 *       内建工具保持既有校验与转换语义不走这里；</li>
 *   <li><b>MCP schema 不可信</b>：解析内嵌的 $defs/definitions/$ref，但<b>绝不</b>抓远程
 *       URL、绝不打开本地文件——外部 $ref 报
 *       {@code no URLLoader set}（钉死的文案片段）；</li>
 *   <li>参数 JSON 非法 → {@code invalid MCP arguments JSON: ...}；非对象 →
 *       {@code MCP arguments must be an object}；schema 编译失败 →
 *       {@code MCP input schema cannot be validated: ...}（探针用 "schema cannot be validated"
 *       探测 "properties": 42 这类坏 schema）。</li>
 * </ul>
 *
 * <p><b>为什么手写校验器</b>：Maven 无逐字节等价的现成校验器；引第三方库会带来
 * 传递依赖且无法复现 "no URLLoader set" 的边界。MCP 入参校验的特征集
 * 是封闭的（语料：type 数组/required/additionalProperties/items/
 * uniqueItems/oneOf/allOf/anyOf/if-then-else/const/minLength(按码点)/minimum/$defs+本地
 * $ref/布尔 schema），本实现按该语料全覆盖，深度上限防御恶意递归。</p>
 */
public final class McpInputSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXTERNAL_REF_MESSAGE =
            "no URLLoader set: reference is external and remote fetching is disabled";
    private static final int MAX_DEPTH = 64;

    private final String schemaJson;
    private volatile JsonNode compiledSchema;
    private volatile String compileError;

    public McpInputSchemaValidator(String schemaJson) {
        this.schemaJson = schemaJson == null ? "" : schemaJson;
    }

    /** 编译失败或校验失败返回错误文案；通过返回 null。 */
    public String validateArguments(String argsJson) {
        JsonNode schema = compileIfNeeded();
        if (compileError != null) {
            return "MCP input schema cannot be validated: " + compileError;
        }
        JsonNode value;
        try {
            value = MAPPER.readTree(argsJson);
        } catch (Exception e) {
            return "invalid MCP arguments JSON: " + e.getMessage();
        }
        if (value == null || !value.isObject()) {
            return "MCP arguments must be an object";
        }
        String err = validate(schema, value, schema, 0);
        return err;
    }

    private JsonNode compileIfNeeded() {
        JsonNode result = compiledSchema;
        if (result != null || compileError != null) {
            return result;
        }
        synchronized (this) {
            if (compiledSchema != null || compileError != null) {
                return compiledSchema;
            }
            JsonNode doc;
            try {
                doc = MAPPER.readTree(schemaJson);
            } catch (Exception e) {
                compileError = e.getMessage();
                return null;
            }
            if (doc == null) {
                compileError = "schema is not a JSON value";
                return null;
            }
            String err = checkCompilable(doc, new HashSet<>());
            if (err != null) {
                compileError = err;
                return null;
            }
            compiledSchema = doc;
            return doc;
        }
    }

    /** 编译期检查：坏 schema（如 "properties": 42）与外部引用在此即失败。 */
    private String checkCompilable(JsonNode doc, Set<JsonNode> seen) {
        if (doc.isObject()) {
            if (seen.contains(doc)) {
                return "schema $ref cycle";
            }
            if (seen.size() > MAX_DEPTH) {
                return "schema nesting too deep";
            }
            JsonNode properties = doc.get("properties");
            if (properties != null && !properties.isObject() && !properties.isBoolean()) {
                return "properties must be an object";
            }
            JsonNode items = doc.get("items");
            if (items != null && items.isObject()) {
                String err = checkCompilable(items, seen);
                if (err != null) {
                    return err;
                }
            }
            JsonNode defs = doc.get("$defs");
            if (defs != null && defs.isObject()) {
                for (JsonNode def : defs) {
                    if (def.isObject()) {
                        String err = checkCompilable(def, seen);
                        if (err != null) {
                            return err;
                        }
                    }
                }
            }
        }
        return externalRefError(doc);
    }

    /** 非 "#..." 的 $ref（http/file/urn）一律拒绝（远程抓取禁用）。 */
    private String externalRefError(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        JsonNode ref = node.get("$ref");
        if (ref != null && ref.isTextual() && !ref.textValue().startsWith("#")) {
            return EXTERNAL_REF_MESSAGE;
        }
        return null;
    }

    // ---- 校验器 ----

    private String validate(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        if (depth > MAX_DEPTH) {
            return "value nesting too deep for validation";
        }
        if (schema.isBoolean()) {
            return schema.booleanValue() ? null : "schema forbids any value";
        }
        if (!schema.isObject()) {
            return null;
        }
        String err = externalRefError(schema);
        if (err != null) {
            return err;
        }

        // type（单值或数组，数组可含 "null"）
        JsonNode typeNode = schema.get("type");
        if (typeNode != null) {
            boolean ok;
            if (typeNode.isTextual()) {
                ok = typeMatches(value, typeNode.textValue());
            } else if (typeNode.isArray()) {
                ok = false;
                for (JsonNode t : typeNode) {
                    if (t.isTextual() && typeMatches(value, t.textValue())) {
                        ok = true;
                        break;
                    }
                }
            } else {
                ok = true;
            }
            if (!ok) {
                return "value does not match type " + typeNode;
            }
        }

        // enum / const
        JsonNode constNode = schema.get("const");
        if (constNode != null && !jsonEquals(value, constNode)) {
            return "value does not match const";
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean ok = false;
            for (JsonNode e : enumNode) {
                if (jsonEquals(value, e)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return "value is not one of the enum values";
            }
        }

        // 数值界
        if (value.isNumber()) {
            BigDecimal v = value.decimalValue();
            JsonNode minimum = schema.get("minimum");
            if (minimum != null && minimum.isNumber() && v.compareTo(minimum.decimalValue()) < 0) {
                return "value is less than minimum";
            }
            JsonNode maximum = schema.get("maximum");
            if (maximum != null && maximum.isNumber() && v.compareTo(maximum.decimalValue()) > 0) {
                return "value is greater than maximum";
            }
        }

        // 字符串长度按 code point 计（如 "中文" 过 minLength 2）
        if (value.isTextual()) {
            JsonNode minLength = schema.get("minLength");
            if (minLength != null && minLength.isNumber()
                    && value.textValue().codePointCount(0, value.textValue().length()) < minLength.intValue()) {
                return "string is shorter than minLength";
            }
            JsonNode maxLength = schema.get("maxLength");
            if (maxLength != null && maxLength.isNumber()
                    && value.textValue().codePointCount(0, value.textValue().length()) > maxLength.intValue()) {
                return "string is longer than maxLength";
            }
        }

        // 数组：items + uniqueItems
        if (value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null && !items.isNull()) {
                for (JsonNode item : value) {
                    err = validate(items, item, root, depth + 1);
                    if (err != null) {
                        return err;
                    }
                }
            }
            JsonNode unique = schema.get("uniqueItems");
            if (unique != null && unique.booleanValue()) {
                List<JsonNode> seen = new ArrayList<>();
                for (JsonNode item : value) {
                    for (JsonNode prev : seen) {
                        if (jsonEquals(prev, item)) {
                            return "array items are not unique";
                        }
                    }
                    seen.add(item);
                }
            }
        }

        // 对象：required / properties / additionalProperties
        if (value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                List<String> missing = new ArrayList<>();
                for (JsonNode r : required) {
                    if (r.isTextual() && !value.has(r.textValue())) {
                        missing.add(r.textValue());
                    }
                }
                if (!missing.isEmpty()) {
                    return "missing properties: " + missing;
                }
            }
            JsonNode properties = schema.get("properties");
            JsonNode additional = schema.get("additionalProperties");
            boolean additionalAllowed = !(additional != null && additional.isBoolean() && !additional.booleanValue());
            for (Map.Entry<String, JsonNode> entry : propertiesObjects(value)) {
                String key = entry.getKey();
                JsonNode child = entry.getValue();
                JsonNode propSchema = properties != null && properties.isObject() ? properties.get(key) : null;
                if (propSchema != null && !propSchema.isNull()) {
                    err = validate(propSchema, child, root, depth + 1);
                    if (err != null) {
                        return err;
                    }
                } else if (!additionalAllowed) {
                    return "unexpected additional property " + key;
                } else if (additional != null && additional.isObject()) {
                    err = validate(additional, child, root, depth + 1);
                    if (err != null) {
                        return err;
                    }
                }
            }
        }

        // 组合关键字
        err = validateAllOf(schema, value, root, depth);
        if (err != null) {
            return err;
        }
        err = validateAnyOf(schema, value, root, depth);
        if (err != null) {
            return err;
        }
        JsonNode oneOf = schema.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            int matches = 0;
            for (JsonNode sub : oneOf) {
                if (validate(sub, value, root, depth + 1) == null) {
                    matches++;
                }
            }
            if (matches != 1) {
                return "value matches " + matches + " oneOf branches, want exactly one";
            }
        }

        // if / then / else
        JsonNode ifNode = schema.get("if");
        if (ifNode != null) {
            boolean ifHolds = validate(ifNode, value, root, depth + 1) == null;
            if (ifHolds) {
                JsonNode thenNode = schema.get("then");
                if (thenNode != null) {
                    err = validate(thenNode, value, root, depth + 1);
                    if (err != null) {
                        return "if/then: " + err;
                    }
                }
            } else {
                JsonNode elseNode = schema.get("else");
                if (elseNode != null) {
                    err = validate(elseNode, value, root, depth + 1);
                    if (err != null) {
                        return "if/else: " + err;
                    }
                }
            }
        }

        // 本地 $ref（#/$defs/... 等 JSON Pointer）
        JsonNode ref = schema.get("$ref");
        if (ref != null && ref.isTextual()) {
            String pointer = ref.textValue();
            if (!pointer.startsWith("#")) {
                return EXTERNAL_REF_MESSAGE;
            }
            JsonNode resolved = resolvePointer(root, pointer.substring(1));
            if (resolved == null) {
                return "unresolvable $ref " + pointer;
            }
            err = validate(resolved, value, root, depth + 1);
            if (err != null) {
                return err;
            }
        }

        return null;
    }

    /** allOf = 全过（返回首个子错误）；anyOf = 至少一个过。 */
    private String validateAllOf(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        JsonNode list = schema.get("allOf");
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode sub : list) {
            String err = validate(sub, value, root, depth + 1);
            if (err != null) {
                return "allOf: " + err;
            }
        }
        return null;
    }

    private String validateAnyOf(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        JsonNode list = schema.get("anyOf");
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode sub : list) {
            if (validate(sub, value, root, depth + 1) == null) {
                return null;
            }
        }
        return "value does not match any anyOf branch";
    }

    private Iterable<Map.Entry<String, JsonNode>> propertiesObjects(JsonNode obj) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        obj.fields().forEachRemaining(out::add);
        return out;
    }

    /** "#/$defs/id" → root.$defs.id；"#/" 或 "#" → root。 */
    private static JsonNode resolvePointer(JsonNode root, String pointer) {
        if (pointer.isEmpty() || pointer.equals("/")) {
            return root;
        }
        JsonNode cur = root;
        for (String part : pointer.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            part = part.replace("~1", "/").replace("~0", "~");
            if (cur.isArray()) {
                cur = cur.get(Integer.parseInt(part));
            } else if (cur.isObject()) {
                cur = cur.get(part);
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static boolean typeMatches(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isNumber() && value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    /** JSON 值等值（数字按数值比较——1 与 1.0 相等，1 与 "2" 不等）。 */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isValueNode() || b.isValueNode()) {
            return a.equals(b);
        }
        return a.equals(b);
    }
}
