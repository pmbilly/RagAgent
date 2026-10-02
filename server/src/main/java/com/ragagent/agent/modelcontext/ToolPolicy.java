package com.ragagent.agent.modelcontext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.common.web.GoJsonCodec;
import com.ragagent.llm.domain.ToolCall;

/**
 * 内建工具字段句柄策略的完整 allowlist（对照 Go internal/modelcontext/tool_policy.go，
 * 全文移植）。字段名本身刻意不够：动态 MCP 工具可能用同名而语义无关，必须保持不透明。
 */
final class ToolPolicy {

    static final String TOOL_DATABASE_QUERY = "database_query";
    static final String TOOL_DATA_ANALYSIS = "data_analysis";
    static final String TOOL_WIKI_READ_ISSUE = "wiki_read_issue";
    static final String TOOL_WIKI_UPDATE_ISSUE = "wiki_update_issue";

    private static final Pattern ISSUE_HANDLE_SHAPE = Pattern.compile("^i[1-9][0-9]*$");

    /** source-handle 空间（Go sourceKeySpace）。 */
    enum SourceKeySpace {
        CHUNK, DOCUMENT,
        /** "knowledgeID|title" 存储引用；只有 ID 是持久的。 */
        DOCUMENT_REF, KNOWLEDGE_BASE, WEB
    }

    /**
     * source 编解码认识的全部 ID 键表（对照 sourceKeySpaces）。它同时驱动句柄注册
     * （registerSourceIDByKey）与 handle 形状值的解码闸（walkJSON）。
     */
    static final Map<String, SourceKeySpace> SOURCE_KEY_SPACES = Map.ofEntries(
            Map.entry("chunk_id", SourceKeySpace.CHUNK), Map.entry("faq_id", SourceKeySpace.CHUNK),
            Map.entry("chunk_ids", SourceKeySpace.CHUNK), Map.entry("faq_ids", SourceKeySpace.CHUNK),
            Map.entry("knowledge_id", SourceKeySpace.DOCUMENT), Map.entry("knowledge_ids", SourceKeySpace.DOCUMENT),
            Map.entry("suspected_knowledge_ids", SourceKeySpace.DOCUMENT),
            Map.entry("source_refs", SourceKeySpace.DOCUMENT_REF),
            Map.entry("knowledge_base", SourceKeySpace.KNOWLEDGE_BASE), Map.entry("knowledge_base_id", SourceKeySpace.KNOWLEDGE_BASE),
            Map.entry("knowledge_base_ids", SourceKeySpace.KNOWLEDGE_BASE), Map.entry("kb_id", SourceKeySpace.KNOWLEDGE_BASE),
            Map.entry("kb_ids", SourceKeySpace.KNOWLEDGE_BASE),
            Map.entry("url", SourceKeySpace.WEB), Map.entry("urls", SourceKeySpace.WEB));

    /** 兼容 Go 名 sourceKeySpaces 的包内访问（sources.go 用）。 */
    static final Map<String, SourceKeySpace> sourceKeySpaces = SOURCE_KEY_SPACES;

    private static Set<String> keys(String... names) {
        return Set.of(names);
    }

    /** 单个工具的句柄策略（Go toolHandlePolicy）。 */
    private record ToolHandlePolicy(
            String mcpRoutingKey,
            boolean mcpDirectoryOutput,
            boolean opaqueOutput,
            Set<String> sourceIDKeys,
            Set<String> sourceTextKeys,
            boolean sourceOutput,
            Set<String> decodedIssueIDKeys,
            Set<String> encodedIssueIDKeys,
            boolean encodeKnownIssueIDs) {

        static final ToolHandlePolicy EMPTY = new ToolHandlePolicy("", false, false, Set.of(), Set.of(), false, Set.of(), Set.of(), false);
    }

    private static ToolHandlePolicy policy(
            String mcpRoutingKey, boolean mcpDirectoryOutput, boolean opaqueOutput,
            Set<String> sourceIDKeys, Set<String> sourceTextKeys, boolean sourceOutput,
            Set<String> decodedIssueIDKeys, Set<String> encodedIssueIDKeys, boolean encodeKnownIssueIDs) {
        return new ToolHandlePolicy(mcpRoutingKey, mcpDirectoryOutput, opaqueOutput,
                sourceIDKeys == null ? Set.of() : sourceIDKeys,
                sourceTextKeys == null ? Set.of() : sourceTextKeys,
                sourceOutput,
                decodedIssueIDKeys == null ? Set.of() : decodedIssueIDKeys,
                encodedIssueIDKeys == null ? Set.of() : encodedIssueIDKeys,
                encodeKnownIssueIDs);
    }

    /** 对照 Go toolHandlePolicies（键集与每个字段逐条对应）。 */
    private static final Map<String, ToolHandlePolicy> TOOL_HANDLE_POLICIES = Map.ofEntries(
            Map.entry("discover_mcp_tools", policy("server_id", true, true, null, null, false, null, null, false)),
            Map.entry("call_mcp_tool", policy("tool_ref", false, true, null, null, false, null, null, false)),
            Map.entry("read_file", ToolHandlePolicy.EMPTY),
            Map.entry("knowledge_search", policy("", false, false, keys("knowledge_base_ids"), null, true, null, null, false)),
            Map.entry("grep_chunks", policy("", false, false, null, null, true, null, null, false)),
            Map.entry("list_knowledge_chunks", policy("", false, false, keys("knowledge_id", "faq_id", "chunk_id"), null, true, null, null, false)),
            Map.entry("get_document_info", policy("", false, false, keys("knowledge_ids", "faq_ids"), null, true, null, null, false)),
            Map.entry("search_conversations", ToolHandlePolicy.EMPTY),
            Map.entry("search_memory", ToolHandlePolicy.EMPTY),
            Map.entry("query_knowledge_graph", policy("", false, false, keys("knowledge_base_ids"), null, true, null, null, false)),
            Map.entry(TOOL_DATABASE_QUERY, policy("", false, false, null, keys("sql"), true, null, null, false)),
            Map.entry(TOOL_DATA_ANALYSIS, policy("", false, false, keys("knowledge_id"), keys("sql"), false, null, null, false)),
            Map.entry("data_schema", policy("", false, false, keys("knowledge_id"), null, false, null, null, false)),
            Map.entry("web_fetch", policy("", false, false, keys("url", "urls"), null, true, null, null, false)),
            Map.entry("web_search", policy("", false, false, null, null, true, null, null, false)),
            Map.entry("wiki_read_page", policy("", false, false, null, null, true, null, null, false)),
            Map.entry("wiki_read_source_doc", policy("", false, false, keys("knowledge_id"), null, true, null, null, false)),
            Map.entry("wiki_write_page", policy("", false, false, keys("source_refs"), null, false, null, null, false)),
            Map.entry("wiki_replace_text", policy("", false, false, keys("source_refs"), null, false, null, null, false)),
            Map.entry("wiki_flag_issue", policy("", false, false, keys("suspected_knowledge_ids"), null, false, null, null, false)),
            Map.entry("wiki_search", policy("", false, false, keys("knowledge_base_id"), null, true, null, null, false)),
            Map.entry(TOOL_WIKI_READ_ISSUE, policy("", false, false, null, null, true, keys("issue_id"), keys("id"), true)),
            Map.entry(TOOL_WIKI_UPDATE_ISSUE, policy("", false, false, null, null, false, keys("issue_id"), null, true)),
            Map.entry("wiki_rename_page", policy("", false, false, null, null, true, null, null, false)),
            Map.entry("wiki_delete_page", policy("", false, false, null, null, true, null, null, false)),
            Map.entry("thinking", ToolHandlePolicy.EMPTY),
            Map.entry("todo_write", ToolHandlePolicy.EMPTY),
            Map.entry("read_skill", ToolHandlePolicy.EMPTY),
            Map.entry("execute_skill_script", ToolHandlePolicy.EMPTY),
            Map.entry("shell_exec", ToolHandlePolicy.EMPTY),
            Map.entry("list_sandbox_files", ToolHandlePolicy.EMPTY),
            Map.entry("read_sandbox_file", ToolHandlePolicy.EMPTY),
            Map.entry("write_sandbox_file", ToolHandlePolicy.EMPTY),
            Map.entry("edit_sandbox_file", ToolHandlePolicy.EMPTY),
            Map.entry("write_skill_file", ToolHandlePolicy.EMPTY),
            Map.entry("edit_skill_file", ToolHandlePolicy.EMPTY));

    /** 该工具是否有显式 model-handle 策略（对照 HasToolPolicy）。 */
    static boolean hasToolPolicy(String toolName) {
        return TOOL_HANDLE_POLICIES.containsKey(toolName);
    }

    static boolean sourceArgumentAllowed(String toolName, String key) {
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        if (policy == null) {
            return false;
        }
        return policy.sourceIDKeys().contains(key.toLowerCase());
    }

    static boolean sourceOutputAllowed(String toolName) {
        if (toolName.isEmpty()) {
            // 非 Agent 调用方的通用低层门面；Agent 生命周期总是给具体工具名
            return true;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        return policy != null && policy.sourceOutput();
    }

    static boolean sourceCompactionAllowed(String toolName) {
        if (toolName.isEmpty()) {
            return true;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        return policy != null && !policy.opaqueOutput();
    }

    static ToolHandlePolicy policyOf(String toolName) {
        return TOOL_HANDLE_POLICIES.get(toolName);
    }

    /** 该工具的 MCP 路由键（server_id / tool_ref / ""）。 */
    static String mcpRoutingKeyOf(String toolName) {
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        return policy == null ? "" : policy.mcpRoutingKey();
    }

    static boolean isIssueHandleShape(String value) {
        return ISSUE_HANDLE_SHAPE.matcher(value).matches();
    }

    // ---- 依赖 Registry 的策略逻辑（Go 里是 *Registry 方法，这里收拢为静态）----

    /**
     * 处理模型句柄内嵌在结构化文本、或属于工具私有身份空间的小集合参数
     * （对照 Registry.decodeToolPolicies）。通用自由文本绝不改写。
     */
    static void decodeToolPolicies(Registry registry, ToolCall call) {
        if (registry == null || call == null) {
            return;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(call.getFunction().getName());
        if (policy == null) {
            return;
        }
        call.getFunction().setArguments(rewriteJSONStringValues(
                call.getFunction().getArguments(),
                (key, value) -> {
                    if (policy.sourceTextKeys().contains(key)) {
                        return registry.sources.decodeKnownQuotedText(value);
                    }
                    if (policy.decodedIssueIDKeys().contains(key) && ISSUE_HANDLE_SHAPE.matcher(value.strip()).matches()) {
                        HandleTable.Resolved resolved = registry.issues.resolve(value);
                        if (resolved.ok()) {
                            return resolved.value();
                        }
                    }
                    return value;
                }));
    }

    /** decodeToolPolicies 的精确逆操作，作用于回放进后续模型轮的 assistant 工具调用。 */
    static void encodeReplayedToolPolicies(Registry registry, com.ragagent.llm.domain.ToolCall call) {
        if (registry == null || call == null) {
            return;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(call.getFunction().getName());
        if (policy == null) {
            return;
        }
        call.getFunction().setArguments(registry.encodeMCPArguments(call.getFunction().getName(), call.getFunction().getArguments()));
        call.getFunction().setArguments(rewriteJSONStringValues(
                call.getFunction().getArguments(),
                (key, value) -> {
                    if (policy.sourceTextKeys().contains(key)) {
                        return registry.sources.compactKnownText(value);
                    }
                    if (policy.decodedIssueIDKeys().contains(key)) {
                        return registry.issues.encodeKnownText(value);
                    }
                    return value;
                }));
    }

    static List<String> unresolvedPrivateToolHandles(Registry registry, String toolName, String raw) {
        if (registry == null || raw == null || raw.isEmpty()) {
            return null;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        if (policy == null || (policy.decodedIssueIDKeys().isEmpty() && policy.sourceTextKeys().isEmpty())) {
            return null;
        }
        List<String> unresolved = new ArrayList<>();
        walkJSONStringValues(raw, (key, value) -> {
            String v = value.strip();
            if (policy.sourceTextKeys().contains(key)) {
                List<String> found = registry.sources.unresolvedQuotedTextHandles(v);
                if (found != null) {
                    unresolved.addAll(found);
                }
            }
            if (policy.decodedIssueIDKeys().contains(key) && ISSUE_HANDLE_SHAPE.matcher(v).matches()) {
                if (!registry.issues.resolve(v).ok()) {
                    unresolved.add(v);
                }
            }
            return v;
        });
        return unresolved;
    }

    /**
     * 注册并压缩单个内建工具家族局部的标识（对照 Registry.encodeToolPrivateResult）。
     * MCP 路由字段有显式 envelope 策略；远端 schema、arguments 与执行结果保持不透明。
     */
    static String encodeToolPrivateResult(Registry registry, String toolName, String output) {
        if (registry == null || output == null || output.isEmpty()) {
            return output;
        }
        ToolHandlePolicy policy = TOOL_HANDLE_POLICIES.get(toolName);
        if (policy == null) {
            return output;
        }
        if (policy.mcpDirectoryOutput()) {
            return registry.encodeMCPDirectory(output);
        }
        if (!policy.encodedIssueIDKeys().isEmpty()) {
            output = rewriteJSONStringValues(output, (key, value) -> {
                if (policy.encodedIssueIDKeys().contains(key) && !value.strip().isEmpty()) {
                    if (ISSUE_HANDLE_SHAPE.matcher(value.strip()).matches()) {
                        // 模型面的 tool 消息会被跨轮回放。绝不能把既有临时句柄当成
                        // 新的持久 issue 身份而分配出 i2/i3 漂移
                        return value;
                    }
                    return registry.issues.register(value);
                }
                return value;
            });
        }
        if (policy.encodeKnownIssueIDs()) {
            output = registry.issues.encodeKnownText(output);
        }
        return output;
    }

    // ---- JSON 字符串值遍历（Go rewriteJSONStringValues / walkJSONStringValues）----

    static String rewriteJSONStringValues(String raw, java.util.function.BinaryOperator<String> rewrite) {
        JsonNode value = GoJsonValues.parse(raw);
        if (value == null) {
            return raw;
        }
        value = GoJsonValues.goFloatTree(value);
        value = walkJSONValue("", value, rewrite);
        return GoJsonCodec.write(value);
    }

    private static JsonNode walkJSONValue(String key, JsonNode value, java.util.function.BinaryOperator<String> rewrite) {
        if (value.isTextual()) {
            return TextNode.valueOf(rewrite.apply(key.toLowerCase(), value.asText()));
        }
        if (value.isArray()) {
            ArrayNode array = (ArrayNode) value;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, walkJSONValue(key, array.get(i), rewrite));
            }
        } else if (value.isObject()) {
            ObjectNode obj = (ObjectNode) value;
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            obj.fields().forEachRemaining(entries::add);
            for (Map.Entry<String, JsonNode> e : entries) {
                obj.set(e.getKey(), walkJSONValue(e.getKey(), e.getValue(), rewrite));
            }
        }
        return value;
    }

    /** 仅供收集（不改写）的遍历；解析失败静默（对照 walkJSONStringValues 返回 err）。 */
    static void walkJSONStringValues(String raw, java.util.function.BinaryOperator<String> rewrite) {
        JsonNode value = GoJsonValues.parse(raw);
        if (value == null) {
            return;
        }
        walkJSONValue("", value, rewrite);
    }

    // ---- 供 Registry 复用的其余包级函数 ----

    /**
     * 有些 provider 会把 items 双重编码。只在 web_fetch 的数组上解包
     * （对照 normalizeWebFetchItems）；ModelArguments 已保留 provider 原文。
     */
    static void normalizeWebFetchItems(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            if (!"web_fetch".equals(call.getFunction().getName())) {
                continue;
            }
            String rewritten = RawJson.rewriteRawObject(call.getFunction().getArguments(), "items", rawValue -> {
                // rawValue 是 JSON 字符串字面量；解码出内层字符串
                JsonNode inner = GoJsonValues.parse(rawValue);
                if (inner == null || !inner.isTextual()) {
                    return null;
                }
                String wrapped = inner.asText();
                JsonNode items = GoJsonValues.parse(wrapped);
                if (items == null || !items.isArray() || items.isEmpty()) {
                    return null;
                }
                // 替换为内层字符串的原文（Go: args["items"] = json.RawMessage(wrapped)）
                return new RawJson.Raw(wrapped);
            });
            if (rewritten != null) {
                call.getFunction().setArguments(rewritten);
            }
        }
    }

    /**
     * 容忍 bridge 的 arguments envelope 被多编码一层（对照 normalizeMCPCallArguments）。
     * 不是 JSON repair：不完整 JSON、null、数组与更深的字符串层都保持非法，
     * 远端业务字段绝不在此被强转。
     */
    static void normalizeMCPCallArguments(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            if (!"call_mcp_tool".equals(call.getFunction().getName())) {
                continue;
            }
            String rewritten = RawJson.rewriteRawObject(call.getFunction().getArguments(), "arguments", rawValue -> {
                JsonNode inner = GoJsonValues.parse(rawValue);
                if (inner == null || !inner.isTextual()) {
                    return null;
                }
                String wrapped = inner.asText();
                JsonNode object = GoJsonValues.parse(wrapped);
                if (object == null || !object.isObject() || object.isEmpty()) {
                    return null;
                }
                return new RawJson.Raw(wrapped);
            });
            if (rewritten != null) {
                call.getFunction().setArguments(rewritten);
            }
        }
    }

    /**
     * 保留原始字节的顶层 JSON 对象（对照 Go 对 map[string]json.RawMessage 的
     * Unmarshal/Marshal）：未触及的值原字节输出，触及的值替换后编码；键按字节序排序。
     */
    static final class RawJson {

        private RawJson() {
        }

        /** 替换结果：Raw 表示新的原文字节；null 表示不修改。 */
        record Raw(String text) {
        }

        interface ValueRewriter {
            /** 输入是该键的原始 JSON 值文本；返回新 Raw 或 null（不改）。 */
            Raw rewrite(String rawValue);
        }

        /**
         * 解析顶层对象（键 → 原始值文本，重复键后写覆盖，对照 json.Unmarshal 进 map），
         * 把 childKey 的原始值交给 rewriter；有替换时按 json.Marshal(map) 语义重组。
         * 解析失败 / 不是对象 / 缺键 / 无替换 → 返回 null（调用方保留原文）。
         */
        static String rewriteRawObject(String raw, String childKey, ValueRewriter rewriter) {
            Map<String, String> object = scanTopLevelObject(raw);
            if (object == null || !object.containsKey(childKey)) {
                return null;
            }
            Raw replacement = rewriter.rewrite(object.get(childKey));
            if (replacement == null) {
                return null;
            }
            return marshalObjectWithReplacement(object, childKey, replacement.text());
        }

        /** 按键序重组对象，childKey（若存在）用 replacement，其余原字节。 */
        static String marshalObjectWithReplacement(Map<String, String> object, String childKey, String replacement) {
            List<String> sortedKeys = new ArrayList<>(object.keySet());
            java.util.Collections.sort(sortedKeys);
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (String key : sortedKeys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(GoJsonCodec.write(TextNode.valueOf(key)));
                sb.append(':');
                sb.append(key.equals(childKey) ? replacement : object.get(key));
            }
            sb.append('}');
            return sb.toString();
        }

        /** json.Marshal(map[string]json.RawMessage) 的等价：键序排序、值原字节。 */
        static String marshalObject(Map<String, String> object) {
            List<String> sortedKeys = new ArrayList<>(object.keySet());
            java.util.Collections.sort(sortedKeys);
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (String key : sortedKeys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(GoJsonCodec.write(TextNode.valueOf(key)));
                sb.append(':');
                sb.append(object.get(key));
            }
            sb.append('}');
            return sb.toString();
        }

        /**
         * 把顶层 JSON 对象扫描成 (键, 原始值文本) 序。字符串按 JSON 语法扫描
         * （转义感知），值段按括号深度扫描。不是对象或语法错误返回 null。
         */
        static Map<String, String> scanTopLevelObject(String raw) {
            if (raw == null) {
                return null;
            }
            int[] pos = {0};
            skipWs(raw, pos);
            if (pos[0] >= raw.length() || raw.charAt(pos[0]) != '{') {
                return null;
            }
            pos[0]++;
            Map<String, String> out = new LinkedHashMap<>();
            skipWs(raw, pos);
            if (pos[0] < raw.length() && raw.charAt(pos[0]) == '}') {
                return out;
            }
            while (pos[0] < raw.length()) {
                skipWs(raw, pos);
                // 键：JSON 字符串
                if (pos[0] >= raw.length() || raw.charAt(pos[0]) != '"') {
                    return null;
                }
                int keyStart = pos[0];
                if (!skipString(raw, pos)) {
                    return null;
                }
                String keyLiteral = raw.substring(keyStart, pos[0]);
                JsonNode keyNode = GoJsonValues.parse(keyLiteral);
                if (keyNode == null || !keyNode.isTextual()) {
                    return null;
                }
                String key = keyNode.asText();
                skipWs(raw, pos);
                if (pos[0] >= raw.length() || raw.charAt(pos[0]) != ':') {
                    return null;
                }
                pos[0]++;
                skipWs(raw, pos);
                int valueStart = pos[0];
                if (!skipValue(raw, pos)) {
                    return null;
                }
                out.put(key, raw.substring(valueStart, pos[0]));
                skipWs(raw, pos);
                if (pos[0] < raw.length() && raw.charAt(pos[0]) == ',') {
                    pos[0]++;
                    continue;
                }
                if (pos[0] < raw.length() && raw.charAt(pos[0]) == '}') {
                    pos[0]++;
                    return out;
                }
                return null;
            }
            return null;
        }

        /**
         * 把顶层 JSON 数组扫描成元素原始文本序（对照 json.Unmarshal 进 []json.RawMessage）。
         * 不是数组或语法错误返回 null。
         */
        static List<String> scanTopLevelArray(String raw) {
            if (raw == null) {
                return null;
            }
            int[] pos = {0};
            skipWs(raw, pos);
            if (pos[0] >= raw.length() || raw.charAt(pos[0]) != '[') {
                return null;
            }
            pos[0]++;
            List<String> out = new ArrayList<>();
            skipWs(raw, pos);
            if (pos[0] < raw.length() && raw.charAt(pos[0]) == ']') {
                pos[0]++;
                return out;
            }
            while (pos[0] < raw.length()) {
                skipWs(raw, pos);
                int start = pos[0];
                if (!skipValue(raw, pos)) {
                    return null;
                }
                out.add(raw.substring(start, pos[0]));
                skipWs(raw, pos);
                if (pos[0] < raw.length() && raw.charAt(pos[0]) == ',') {
                    pos[0]++;
                    continue;
                }
                if (pos[0] < raw.length() && raw.charAt(pos[0]) == ']') {
                    pos[0]++;
                    return out;
                }
                return null;
            }
            return null;
        }

        private static void skipWs(String s, int[] pos) {
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos[0]++;
                } else {
                    break;
                }
            }
        }

        /** 从 pos 的 '"' 起扫描 JSON 字符串到闭引号；转义感知。 */
        private static boolean skipString(String s, int[] pos) {
            pos[0]++; // 开引号
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]);
                if (c == '\\') {
                    pos[0] += 2;
                    continue;
                }
                if (c == '"') {
                    pos[0]++;
                    return true;
                }
                pos[0]++;
            }
            return false;
        }

        /** 扫过一个完整 JSON 值（对象/数组/字符串/数字/true/false/null）。 */
        private static boolean skipValue(String s, int[] pos) {
            if (pos[0] >= s.length()) {
                return false;
            }
            char c = s.charAt(pos[0]);
            switch (c) {
                case '"' -> {
                    return skipString(s, pos);
                }
                case '{', '[' -> {
                    int depth = 0;
                    while (pos[0] < s.length()) {
                        char d = s.charAt(pos[0]);
                        if (d == '"') {
                            if (!skipString(s, pos)) {
                                return false;
                            }
                            continue;
                        }
                        if (d == '{' || d == '[') {
                            depth++;
                        } else if (d == '}' || d == ']') {
                            depth--;
                            if (depth == 0) {
                                pos[0]++;
                                return true;
                            }
                        }
                        pos[0]++;
                    }
                    return false;
                }
                default -> {
                    // 数字 / true / false / null：扫到结构性终止符
                    int start = pos[0];
                    while (pos[0] < s.length()) {
                        char d = s.charAt(pos[0]);
                        if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\t' || d == '\n' || d == '\r') {
                            break;
                        }
                        pos[0]++;
                    }
                    return pos[0] > start;
                }
            }
        }
    }
}
