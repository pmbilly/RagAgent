package com.ragagent.modelcontext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.common.web.GoJsonCodec;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionDef;
import com.ragagent.llm.domain.ToolCall;
import com.ragagent.common.retrieval.SearchResult;

/**
 * 请求局部模型句柄的唯一边界（对照 Go internal/modelcontext/registry.go +
 * mcp.go + mcp_sources.go，全文移植）。
 *
 * <p>边界是刻意的：</p>
 * <ul>
 *   <li>UUID、wiki slug、URL、resource:// 句柄是持久身份。</li>
 *   <li>cN/dN/bN/wN/iN/res://NNNN/ref-N 是临时模型句柄。</li>
 *   <li>临时句柄绝不持久化，也绝不在其 registry 之外被接受。</li>
 *   <li>每个模型响应在工具、存储或 UI 消费之前先解码。</li>
 * </ul>
 *
 * <p>持久资源引用先于 source 标识编码。这个顺序是刻意私有的：
 * summary/&lt;knowledge-id&gt; wiki slug 必须先整体变成 resource 类句柄，
 * 内嵌的 document ID 才能被压缩成 dN。调用方无法意外颠倒两个编解码器。</p>
 *
 * <p>JSON 字节语义：凡是 Go 侧走 map[string]json.RawMessage 的路径（MCP envelope、
 * 路由 enum、目录行），这里用 RawJson 扫描器保原始字节、按键序重组；凡是走
 * map[string]interface{} 的路径（walkJSON/rewriteJSONStringValues），数字按 Go
 * 的 float64 语义经 GoJsonCodec 重编。</p>
 */
public final class Registry {

    public static final String ARGUMENT_RESOLUTION_UNCHANGED = "unchanged";
    public static final String ARGUMENT_RESOLUTION_RESOLVED = "resolved";
    public static final String ARGUMENT_RESOLUTION_PARTIALLY_RESOLVED = "partially_resolved";
    public static final String ARGUMENT_RESOLUTION_UNRESOLVED = "unresolved";

    static final String RESOURCE_HANDLE_PROTOCOL_PROMPT = "\n\n## Resource handle protocol (system-owned)\n"
            + "Some durable resources and high-entropy Wiki slugs are represented by request-local res://NNNN handles. Wiki issues may use iN handles.\n"
            + "- Copy supplied handles exactly in links, images, and tool arguments; they refer only to the supplied resource versions.\n"
            + "- For downloadable deliverables generated in the session workspace, use sandbox:<file name>; "
            + "never reuse or invent a resource handle. This download convention does not apply to "
            + "editing installed skill files.";

    final SourceRegistry sources;
    final ResourceRegistry resources;
    final HandleTable issues;
    final HandleTable mcpServers;
    final HandleTable mcpTools;

    /** 为一次模型请求 / Agent 执行创建 registry（对照 NewRegistry）。 */
    public Registry(boolean citationsEnabled) {
        this.sources = new SourceRegistry(citationsEnabled);
        this.resources = new ResourceRegistry();
        this.issues = new HandleTable("i", 0, 1);
        this.mcpServers = new HandleTable("ms", 0, 1);
        this.mcpTools = new HandleTable("mt", 0, 1);
    }

    SourceRegistry sources() {
        return sources;
    }

    /** 系统私有的模型句柄与引用协议（对照 ProtocolPrompt；字节即契约，实录锁死）。 */
    public String protocolPrompt() {
        return sources.protocolPrompt() + RESOURCE_HANDLE_PROTOCOL_PROMPT
                + "\nMCP routing uses request-local msN server IDs and mtN tool references. "
                + "Copy them exactly from the directory or describe result; never invent them.\n";
    }

    /**
     * 返回带全部临时句柄、按唯一安全顺序编码的模型面消息副本
     * （对照 EncodeMessages）。
     */
    public List<ChatMessage> encodeMessages(List<ChatMessage> messages) {
        if (messages == null) {
            return messages;
        }
        messages = resources.encodeMessages(messages);
        // 在编码任何 assistant 调用之前，先从全部回放结果注册工具私有 ID。
        // 扫描刻意与顺序无关，匹配 source 编解码的两遍回放行为
        for (ChatMessage m : messages) {
            if ("system".equals(m.getRole()) || "user".equals(m.getRole())) {
                m.setContent(encodeMCPRoutingText(m.getContent()));
            }
            if ("tool".equals(m.getRole())) {
                m.setContent(encodeToolPrivateResult(m.getName(), m.getContent()));
            }
        }
        List<ChatMessage> out = sources.encodeMessagesWithPolicies(messages, ToolPolicy::sourceArgumentAllowed, ToolPolicy::sourceOutputAllowed);
        for (ChatMessage m : out) {
            if (m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
                continue;
            }
            List<ToolCall> calls = new ArrayList<>(m.getToolCalls());
            m.setToolCalls(calls);
            for (ToolCall call : calls) {
                ToolPolicy.encodeReplayedToolPolicies(this, call);
            }
        }
        return out;
    }

    /** 还原工具调用参数里的全部临时句柄（对照 DecodeToolCalls）。 */
    public void decodeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        for (ToolCall call : toolCalls) {
            if (call.getModelArguments() == null || call.getModelArguments().isEmpty()) {
                call.setModelArguments(call.getFunction().getArguments());
            }
        }
        ToolPolicy.normalizeWebFetchItems(toolCalls);
        ToolPolicy.normalizeMCPCallArguments(toolCalls);
        resources.decodeToolCalls(toolCalls);
        sources.decodeToolCallsWithPolicy(toolCalls, ToolPolicy::sourceArgumentAllowed);
        for (ToolCall call : toolCalls) {
            ToolPolicy.decodeToolPolicies(this, call);
            DecodedMCP decodedMCP = decodeMCPArguments(call.getFunction().getName(), call.getFunction().getArguments());
            call.getFunction().setArguments(decodedMCP.arguments());
            String resolved = call.getFunction().getArguments();
            List<String> unresolved = new ArrayList<>();
            addAll(unresolved, resources.orphanHandles(resolved));
            addAll(unresolved, sources.unresolvedToolHandlesWithPolicy(
                    call.getFunction().getName(), resolved, ToolPolicy::sourceArgumentAllowed));
            addAll(unresolved, ToolPolicy.unresolvedPrivateToolHandles(this, call.getFunction().getName(), resolved));
            addAll(unresolved, decodedMCP.unresolved());
            call.setUnresolvedHandles(uniqueSorted(unresolved));
            boolean changed = !GoJsonValues.jsonEquivalent(call.getModelArguments(), resolved);
            if (changed && !call.getUnresolvedHandles().isEmpty()) {
                call.setArgumentResolution(ARGUMENT_RESOLUTION_PARTIALLY_RESOLVED);
            } else if (!call.getUnresolvedHandles().isEmpty()) {
                call.setArgumentResolution(ARGUMENT_RESOLUTION_UNRESOLVED);
            } else if (changed) {
                call.setArgumentResolution(ARGUMENT_RESOLUTION_RESOLVED);
            } else {
                call.setArgumentResolution(ARGUMENT_RESOLUTION_UNCHANGED);
            }
        }
    }

    private static void addAll(List<String> dst, List<String> src) {
        if (src != null) {
            dst.addAll(src);
        }
    }

    static List<String> uniqueSorted(List<String> values) {
        TreeSet<String> seen = new TreeSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    seen.add(value);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /** 非流式响应的统一解码：还原资源、展开引用、解码工具参数（对照 DecodeResponse）。 */
    public void decodeResponse(ChatResponse response) {
        if (response == null) {
            return;
        }
        response.setContent(decodeOutputText(response.getContent()));
        response.setReasoningContent(decodeOutputText(response.getReasoningContent()));
        decodeToolCalls(response.getToolCalls());
    }

    /** 一个响应文本通道的有序解码器（对照 StreamDecoder）。 */
    public StreamDecoder streamDecoder() {
        return new StreamDecoder(resources, sources, issues, mcpServers, mcpTools);
    }

    /** 模型自造、无持久引用背书的 resource 句柄（对照 OrphanResourceHandles）。 */
    public List<String> orphanResourceHandles(String decoded) {
        return resources.orphanHandles(decoded);
    }

    public String registerChunk(SourceRegistry.ChunkReference ref) {
        return sources.registerChunk(ref);
    }

    /** 让目录条目可被工具寻址，但不允许它在检索前支撑答案（对照 RegisterContextChunk）。 */
    public String registerContextChunk(SourceRegistry.ChunkReference ref) {
        return sources.registerChunk(ref, false);
    }

    /**
     * 引擎桥接（4.6b，新增公开重载，无行为变更）：{@code ChunkReference} 是包内类型，
     * agent 引擎（observe.go 的 registerRuntimeReferences）拿不到构造面，按字段透传。
     */
    public String registerContextChunk(String chunkId, String knowledgeId, String knowledgeBaseId,
            String documentTitle, int chunkIndex, String chunkType) {
        SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
        ref.chunkId = chunkId == null ? "" : chunkId;
        ref.knowledgeId = knowledgeId == null ? "" : knowledgeId;
        ref.knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId;
        ref.documentTitle = documentTitle == null ? "" : documentTitle;
        ref.chunkIndex = chunkIndex;
        ref.chunkType = chunkType == null ? "" : chunkType;
        return sources.registerChunk(ref, false);
    }

    public String registerDocument(String id) {
        return sources.registerDocument(id);
    }

    public String registerKnowledgeBase(String id) {
        return sources.registerKnowledgeBase(id);
    }

    public String registerWeb(String rawURL, String title) {
        return sources.registerWeb(rawURL, title);
    }

    public void registerSearchResults(List<SearchResult> results) {
        sources.registerSearchResults(results);
    }

    public String chunkHandle(String id) {
        return sources.chunkHandle(id);
    }

    /** 只替换此前已注册的持久 source ID（对照 CompactKnownText）。 */
    public String compactKnownText(String text) {
        String encoded = resources.encodeText(text);
        return sources.compactKnownText(encoded);
    }

    /** 用已注册句柄渲染工具结果（对照 ModelToolResult）。 */
    public String modelToolResult(ToolResult result) {
        return modelToolResultForTool("", result);
    }

    /** 渲染结果并应用该内建工具族显式持有的私有 ID 策略（对照 ModelToolResultForTool）。 */
    public String modelToolResultForTool(String toolName, ToolResult result) {
        if (result == null) {
            return "";
        }
        // 先保护持久资源与含 UUID 的 summary slug，source 编解码才能看到内嵌 document ID
        ToolResult copyResult = copyResult(result);
        // 错误文本与 Output 同路编码：失败的工具调用常把肇事参数回显在错误里。
        // 之后对来自结构化 ToolResult.Data 的资源引用再编码一次
        copyResult.setOutput(resources.encodeText(encodeToolPrivateResult(toolName, result.getOutput())));
        copyResult.setError(resources.encodeText(encodeToolPrivateResult(toolName, result.getError())));
        String modelOutput;
        if (ToolPolicy.sourceOutputAllowed(toolName)) {
            modelOutput = ModelOutput.modelOutput(sources, copyResult);
        } else if (copyResult.isSuccess()) {
            modelOutput = copyResult.getOutput();
        } else {
            modelOutput = ModelOutput.failedToolModelText(copyResult.getOutput(), copyResult.getError());
        }
        // 没有结构化 source 结果的工具也会在校验错误或状态文本里露出已知持久 ID。
        // 只压缩显式声明的内建；动态 MCP 输出保持完全不透明
        if (ToolPolicy.sourceCompactionAllowed(toolName)) {
            modelOutput = sources.compactKnownText(modelOutput);
        }
        if (result.isSuccess() && ("call_mcp_tool".equals(toolName) || toolName.startsWith("mcp_"))) {
            modelOutput += mcpSourceCandidates(result.getOutput());
        }
        return resources.encodeText(modelOutput) + outputFilesPrompt(result);
    }

    private static ToolResult copyResult(ToolResult result) {
        ToolResult copy = new ToolResult();
        copy.setOutputFiles(result.getOutputFiles());
        copy.setSuccess(result.isSuccess());
        copy.setOutput(result.getOutput());
        copy.setData(result.getData());
        copy.setError(result.getError());
        copy.setImages(result.getImages());
        return copy;
    }

    static String outputFilesPrompt(ToolResult result) {
        if (result == null || result.getOutputFiles() == null || result.getOutputFiles().isEmpty()) {
            return "";
        }
        return "\nOutput files: `" + String.join("`, `", result.getOutputFiles()) + "`";
    }

    /** 对完整文本应用公共引用策略（对照 DecodeOutputText）。 */
    public String decodeOutputText(String text) {
        text = resources.decodeText(text);
        text = resources.stripOrphanHandles(text);
        text = sources.expandText(text);
        return mcpTools.decodeKnownText(mcpServers.decodeKnownText(issues.decodeKnownText(text)));
    }

    /** 引擎在 observe/act 里登记工具私有结果（对照 encodeToolPrivateResult）。 */
    String encodeToolPrivateResult(String toolName, String output) {
        return ToolPolicy.encodeToolPrivateResult(this, toolName, output);
    }

    // ---- mcp.go ----

    /** MCP 路由身份属于 bridge 而非远端 schema（对照 mcpArgumentTable）。 */
    private record McpArgumentTable(String key, HandleTable table) {
    }

    private McpArgumentTable mcpArgumentTable(String toolName) {
        return switch (ToolPolicy.mcpRoutingKeyOf(toolName)) {
            case "server_id" -> new McpArgumentTable("server_id", mcpServers);
            case "tool_ref" -> new McpArgumentTable("tool_ref", mcpTools);
            default -> null;
        };
    }

    /** 顶层对象的单字段改写（对照 rewriteMCPField）；其余值原字节保留。 */
    static String rewriteMCPField(String raw, String key, java.util.function.UnaryOperator<String> rewrite) {
        String result = ToolPolicy.RawJson.rewriteRawObject(raw, key, rawValue -> {
            JsonNode value = GoJsonValues.parse(rawValue);
            if (value == null || !value.isTextual()) {
                return null;
            }
            String rewritten = rewrite.apply(value.asText());
            if (rewritten.equals(value.asText())) {
                return null;
            }
            return new ToolPolicy.RawJson.Raw(GoJsonCodec.write(TextNode.valueOf(rewritten)));
        });
        return result != null ? result : raw;
    }

    static boolean mcpHandleShape(String value, HandleTable table) {
        String prefix = table.store().prefix();
        return value.startsWith(prefix) && StreamDecoder.allDigits(value.substring(prefix.length()));
    }

    private String registerMCPIdentity(HandleTable table, String value) {
        // 回放的当轮结果已编码。绝不为别名再分配别名，包括压缩历史里的未知别名
        if (value == null || value.isEmpty() || mcpHandleShape(value, table)) {
            return value;
        }
        return table.register(value);
    }

    String encodeMCPArguments(String toolName, String raw) {
        McpArgumentTable entry = mcpArgumentTable(toolName);
        if (entry == null) {
            return raw;
        }
        return rewriteMCPField(raw, entry.key(), value -> {
            String handle = entry.table().handle(value);
            return !handle.isEmpty() ? handle : value;
        });
    }

    record DecodedMCP(String arguments, List<String> unresolved) {
    }

    DecodedMCP decodeMCPArguments(String toolName, String raw) {
        McpArgumentTable entry = mcpArgumentTable(toolName);
        if (entry == null) {
            return new DecodedMCP(raw, null);
        }
        List<String> unresolved = new ArrayList<>();
        String decoded = rewriteMCPField(raw, entry.key(), value -> {
            if (!mcpHandleShape(value, entry.table())) {
                return value;
            }
            HandleTable.Resolved durable = entry.table().resolve(value);
            if (durable.ok()) {
                return durable.value();
            }
            unresolved.add(value);
            return value;
        });
        return new DecodedMCP(decoded, unresolved);
    }

    String encodeMCPDirectory(String output) {
        java.util.function.UnaryOperator<String> encodeRow = raw -> rewriteMCPField(raw, "server_id",
                value -> registerMCPIdentity(mcpServers, value));
        java.util.function.UnaryOperator<String> encodeRowBoth = raw -> rewriteMCPField(encodeRow.apply(raw), "tool_ref",
                value -> registerMCPIdentity(mcpTools, value));
        Map<String, String> object = ToolPolicy.RawJson.scanTopLevelObject(output);
        if (object == null || object.isEmpty()) {
            // 校验失败文本以 prose 形式含已知持久标识
            return mcpTools.encodeKnownText(mcpServers.encodeKnownText(output));
        }
        Map<String, String> rebuilt = new java.util.LinkedHashMap<>(object);
        for (String key : new String[] {"servers", "tools"}) {
            String raw = rebuilt.get(key);
            if (raw == null) {
                continue;
            }
            List<String> rows = ToolPolicy.RawJson.scanTopLevelArray(raw);
            if (rows == null) {
                continue;
            }
            List<String> encodedRows = new ArrayList<>(rows.size());
            for (String row : rows) {
                encodedRows.add(encodeRowBoth.apply(row));
            }
            rebuilt.put(key, marshalRawArray(encodedRows));
        }
        // Go: encoded, _ := json.Marshal(object); return encodeRow(string(encoded))
        String encoded = ToolPolicy.RawJson.marshalObject(rebuilt);
        return encodeRowBoth.apply(encoded);
    }

    /** json.Marshal([]json.RawMessage) 的等价：原字节元素按紧凑数组输出。 */
    static String marshalRawArray(List<String> elements) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(elements.get(i));
        }
        return sb.append(']').toString();
    }

    /** 模型面工具清单副本；registry/executor 保留原 UUID enum（对照 EncodeTools）。 */
    public List<ChatTool> encodeTools(List<ChatTool> tools) {
        List<ChatTool> encoded = new ArrayList<>(tools.size());
        for (ChatTool tool : tools) {
            ChatTool copy = new ChatTool();
            copy.setType(tool.getType());
            FunctionDef original = tool.getFunction();
            copy.setFunction(new FunctionDef(original.getName(), original.getDescription(),
                    original.getParameters() == null ? null : original.getParameters().deepCopy()));
            encoded.add(copy);
        }
        // 先注册路由 enum，与工具描述顺序无关
        for (ChatTool tool : encoded) {
            FunctionDef def = tool.getFunction();
            McpArgumentTable entry = mcpArgumentTable(def.getName());
            if (entry == null) {
                continue;
            }
            String parametersText = def.getParameters() == null ? null : GoJsonCodec.write(def.getParameters());
            Map<String, String> schema = ToolPolicy.RawJson.scanTopLevelObject(parametersText);
            if (schema == null || !schema.containsKey("properties")) {
                continue;
            }
            Map<String, String> properties = ToolPolicy.RawJson.scanTopLevelObject(schema.get("properties"));
            if (properties == null || !properties.containsKey(entry.key())) {
                continue;
            }
            Map<String, String> field = ToolPolicy.RawJson.scanTopLevelObject(properties.get(entry.key()));
            if (field == null || !field.containsKey("enum")) {
                continue;
            }
            List<String> ids = decodeStringArray(field.get("enum"));
            if (ids == null) {
                continue;
            }
            for (int j = 0; j < ids.size(); j++) {
                ids.set(j, registerMCPIdentity(entry.table(), ids.get(j)));
            }
            // field["enum"], _ = json.Marshal(ids) → 替换后逐层按键序重组
            Map<String, String> fieldRewritten = new java.util.LinkedHashMap<>(field);
            fieldRewritten.put("enum", GoJsonCodec.write(GoJsonValues.MAPPER.valueToTree(ids)));
            Map<String, String> propertiesRewritten = new java.util.LinkedHashMap<>(properties);
            propertiesRewritten.put(entry.key(), ToolPolicy.RawJson.marshalObject(fieldRewritten));
            Map<String, String> schemaRewritten = new java.util.LinkedHashMap<>(schema);
            schemaRewritten.put("properties", ToolPolicy.RawJson.marshalObject(propertiesRewritten));
            String marshaled = ToolPolicy.RawJson.marshalObject(schemaRewritten);
            // Go 的 def.Parameters 是 json.RawMessage（原文内嵌）；Java FunctionDef 用
            // JsonNode——解析保序的编出文本回树，后续序列化保持同样的键序与数字形态
            def.setParameters(GoJsonValues.parse(marshaled));
        }
        for (ChatTool tool : encoded) {
            FunctionDef def = tool.getFunction();
            if ("discover_mcp_tools".equals(def.getName())) {
                String[] lines = def.getDescription().split("\n", -1);
                for (int j = 0; j < lines.length; j++) {
                    lines[j] = rewriteMCPField(lines[j], "server_id",
                            value -> registerMCPIdentity(mcpServers, value));
                }
                def.setDescription(String.join("\n", lines));
            } else if (def.getName().startsWith("mcp_") && def.getDescription().startsWith("[MCP service ")) {
                int cut = def.getDescription().indexOf(" (external)] ");
                if (cut < 0) {
                    continue;
                }
                String prefix = def.getDescription().substring(0, cut);
                String body = def.getDescription().substring(cut + " (external)] ".length());
                def.setDescription(encodeMCPRoutingText(prefix) + " (external)] " + body);
            }
        }
        return encoded;
    }

    /** 解码一个 JSON 字符串数组的原始文本；任何元素非字符串返回 null（对照 json.Unmarshal 失败）。 */
    static List<String> decodeStringArray(String raw) {
        JsonNode node = GoJsonValues.parse(raw);
        if (node == null || !node.isArray()) {
            return null;
        }
        ArrayNode array = (ArrayNode) node;
        List<String> ids = new ArrayList<>(array.size());
        for (JsonNode id : array) {
            if (!id.isTextual()) {
                return null;
            }
            ids.add(id.asText());
        }
        return ids;
    }

    /** 运行时上下文文本只压缩系统私有的路由语法（对照 encodeMCPRoutingText）。 */
    String encodeMCPRoutingText(String text) {
        for (HandleStore.Pair<Void> pair : mcpServers.store().pairs()) {
            text = text.replace(
                    "server_id=" + goQuote(pair.value),
                    "server_id=" + goQuote(pair.handle));
        }
        return text;
    }

    /** Go strconv.Quote 的等价（路由文本替换用；服务 ID 是简单 ASCII）。 */
    static String goQuote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ---- mcp_sources.go ----

    /** MCP 结果里观察到的字面 HTTP(S) 链接索引上限。 */
    static final int MAX_MCP_SOURCE_CANDIDATES = 50;

    private static final java.util.regex.Pattern MCP_JSON_STRING =
            java.util.regex.Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final java.util.regex.Pattern MCP_URL = java.util.regex.Pattern
            .compile("https?://[^\\t\\n\\u000c\\r <>\"'`\\\\，。；！？、（）【】]+");

    /** 成功结果里观察到的链接 sidecar（对照 mcpSourceCandidates）；结果本身保持不变。 */
    String mcpSourceCandidates(String output) {
        if (!sources.citationsEnabled) {
            return "";
        }
        // 只在用于找链接的草稿副本里解 JSON 字符串转义；外部正文与持久 ToolResult 不动
        String scanned = SourceRegistry.replaceAllFunc(MCP_JSON_STRING, output, token -> {
            JsonNode value = GoJsonValues.parse(token);
            if (value != null && value.isTextual()) {
                return "\"" + value.asText() + "\"";
            }
            return token;
        });
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder rows = new StringBuilder();
        java.util.regex.Matcher m = MCP_URL.matcher(scanned);
        while (m.find()) {
            String match = m.group();
            String rawURL = GoHtml.unescape(match);
            rawURL = trimRight(rawURL, ".,;!?");
            // 剥 Markdown/prose 收尾定界符，保留平衡的 URL 括号（如 /wiki/Function_(mathematics)）
            for (String[] pair : new String[][] {{"(", ")"}, {"[", "]"}, {"{", "}"}}) {
                while (rawURL.endsWith(pair[1]) && countOf(rawURL, pair[1]) > countOf(rawURL, pair[0])) {
                    rawURL = rawURL.substring(0, rawURL.length() - pair[1].length());
                }
            }
            if (!isFetchableWebURL(rawURL)) {
                continue;
            }
            String key = SourceRegistry.canonicalWebURL(rawURL);
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            String handle = sources.registerWeb(rawURL, "");
            rows.append("<source id=\"").append(handle).append("\" url=\"")
                    .append(SourceRegistry.escapeAttr(rawURL)).append("\"/>\n");
            if (seen.size() >= MAX_MCP_SOURCE_CANDIDATES) {
                break;
            }
        }
        if (rows.length() == 0) {
            return "";
        }
        return "\n\n<external_source_candidates>\n"
                + "System-indexed links from this MCP result. Cite the matching wN only when the result supports the claim; "
                + "a link alone does not mean the linked page was read. Do not use KB cN handles for this external content.\n"
                + rows + "</external_source_candidates>";
    }

    /**
     * Go url.Parse 后的守卫：err==nil 且 Hostname 非空、无 userinfo、scheme 是
     * http/https（regex 已保证前缀；这里查 host 非空、无 userinfo、无控制字符）。
     */
    private static boolean isFetchableWebURL(String rawURL) {
        String rest = rawURL;
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd < 0) {
            return false;
        }
        rest = rest.substring(schemeEnd + 3);
        if (rest.isEmpty()) {
            return false;
        }
        String authority = rest;
        int pathStart = authority.indexOf('/');
        if (pathStart >= 0) {
            authority = authority.substring(0, pathStart);
        }
        if (authority.lastIndexOf('@') >= 0) {
            return false; // userinfo（Go: parsed.User != nil）
        }
        int hash = authority.indexOf('#');
        if (hash >= 0) {
            authority = authority.substring(0, hash);
        }
        int question = authority.indexOf('?');
        if (question >= 0) {
            authority = authority.substring(0, question);
        }
        if (authority.isEmpty()) {
            return false; // Hostname() == ""
        }
        for (int i = 0; i < rawURL.length(); i++) {
            if (rawURL.charAt(i) < 0x20) {
                return false; // Go url.Parse 拒绝 ASCII 控制字符
            }
        }
        return true;
    }

    static String trimRight(String s, String cutset) {
        int end = s.length();
        while (end > 0 && cutset.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    static int countOf(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
