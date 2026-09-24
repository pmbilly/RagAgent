package com.ragagent.modelcontext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.agent.tools.GoJsonCodec;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * model-context registry 的 source-reference 半边（对照 Go internal/modelcontext
 * sources.go + citations.go，全文移植）：chunk/document/knowledge base/web page 的
 * 请求局部 cN/dN/bN/wN 句柄，以及把它们映射回持久标识的工具参数编解码器。
 * 请求生命周期统一经 {@link Registry}，source 与 resource 句柄不能乱序编解码。
 */
final class SourceRegistry {

    // ---- 常量：协议提示词（字节即契约，实录锁死）----

    static final String SOURCE_HANDLE_PROTOCOL_PROMPT = "\n\n## Source handling protocol (system-owned)\n"
            + "Retrieved content uses request-local source handles: cN identifies a knowledge chunk, wN a web page, dN a document, and bN a knowledge base.\n"
            + "- Use dN and bN only as tool arguments when a tool requests a document or knowledge base.\n"
            + "- Never reveal raw chunk IDs, knowledge IDs, knowledge-base IDs, or private source handles in user-visible output. This does not change separate instructions to preserve retrieved Markdown image URLs.";

    static final String CITATION_ENABLED_PROTOCOL_PROMPT = "\n"
            + "- Source citations are enabled for this answer. Cite a knowledge chunk with exactly <ref id=\"cN\"/> and a web page with exactly <ref id=\"wN\"/>.\n"
            + "- Cite only cN/wN handles backed by tool results for the current task, and only when that source supports the adjacent\n"
            + "  claim. Never cite dN/bN.\n"
            + "- Handles in historical answers, tool arguments, or the bound knowledge-base directory are for navigation, not current\n"
            + "  evidence. Retrieve the relevant source before citing it.\n"
            + "- MCP results are external sources. Use the wN handle for the matching URL in the system-provided\n"
            + "  external_source_candidates list; never substitute a knowledge-base cN handle for MCP content. Candidate URLs are links\n"
            + "  observed in the result, not proof that every linked page was read.\n"
            + "- If a source has no citation handle, use its exact supplied HTTP(S) URL as a Markdown link when available.\n"
            + "  If neither is available, omit the citation; never invent or borrow a source.\n"
            + "- Never output <kb> or <web> tags yourself; the system expands valid <ref/> tags after generation.\n"
            + "- Keep each <ref/> inline on the same line as the claim it supports. Do not group citations "
            + "at the end. For a requested exact output format, use citations only where the format "
            + "permits them; do not break a required schema to add citations.\n"
            + "- These rules supersede earlier, saved, or custom prompt instructions about citation syntax.";

    static final String CITATION_DISABLED_PROTOCOL_PROMPT = "\n"
            + "- Source citations are disabled for this answer. Do not add <ref>, <kb>, <web>, or source "
            + "attribution links to the answer. This does not prohibit a URL explicitly requested by the "
            + "user, Wiki navigation links, downloadable deliverables, or relevant image URLs.\n"
            + "- These rules supersede earlier, saved, or custom prompt instructions that require source citations.";

    // ---- 正则（Go → Java 的两处语义修正：\s 用显式类；文本锚 $ 用 \z）----

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final int DOTALL = Pattern.DOTALL;

    private static final Pattern PUBLIC_KB_TAG = Pattern.compile("<kb\\b[^>]*>", CASE_INSENSITIVE | DOTALL);
    private static final Pattern PUBLIC_WEB_TAG = Pattern.compile("<web\\b[^>]*>", CASE_INSENSITIVE | DOTALL);
    private static final Pattern DOC_ATTR = Pattern.compile("\\bdoc\\s*=\\s*\"([^\"]*)\"", CASE_INSENSITIVE);
    private static final Pattern CHUNK_ATTR = Pattern.compile("\\bchunk_id\\s*=\\s*\"([^\"]+)\"", CASE_INSENSITIVE);
    private static final Pattern PUBLIC_KB_ATTR = Pattern.compile("\\bkb_id\\s*=\\s*\"([^\"]*)\"", CASE_INSENSITIVE);
    private static final Pattern URL_ATTR = Pattern.compile("\\burl\\s*=\\s*\"([^\"]+)\"", CASE_INSENSITIVE);
    private static final Pattern TITLE_ATTR = Pattern.compile("\\btitle\\s*=\\s*\"([^\"]*)\"", CASE_INSENSITIVE);
    private static final Pattern LEGACY_CHUNK_TAG = Pattern.compile("<(?:chunk|faq)\\b[^>]*>", CASE_INSENSITIVE | DOTALL);
    private static final Pattern FAQ_ATTR = Pattern.compile("\\bfaq_id\\s*=\\s*\"([^\"]+)\"", CASE_INSENSITIVE);
    private static final Pattern KNOWLEDGE_TITLE_ATTR = Pattern.compile("\\bknowledge_title\\s*=\\s*\"([^\"]*)\"", CASE_INSENSITIVE);

    private static final Pattern REF_TAG = Pattern.compile("<ref\\s+id\\s*=\\s*\"([^\"]+)\"\\s*/?>", CASE_INSENSITIVE);
    private static final Pattern REF_CANDIDATE = Pattern.compile("<ref(?:\\s|\\z)[^>]*(?:>|\\z)", CASE_INSENSITIVE | DOTALL);
    private static final Pattern MODEL_KB_TAG = Pattern.compile("<kb(?:\\s|\\z)[^>]*(?:>|\\z)", CASE_INSENSITIVE | DOTALL);
    private static final Pattern MODEL_WEB_TAG = Pattern.compile("<web(?:\\s|\\z)[^>]*(?:>|\\z)", CASE_INSENSITIVE | DOTALL);

    private static final Pattern DOCUMENT_ATTR = Pattern.compile("\\bknowledge_id\\s*=\\s*\"([^\"]+)\"", CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_ELEMENT = Pattern.compile("<knowledge_id>\\s*([^<]+?)\\s*</knowledge_id>", CASE_INSENSITIVE | DOTALL);
    private static final Pattern KB_ATTR = Pattern.compile("\\b(?:knowledge_base_id|kb_id)\\s*=\\s*\"([^\"]+)\"", CASE_INSENSITIVE);
    private static final Pattern KB_ELEMENT = Pattern.compile("<(?:knowledge_base_id|kb_id)>\\s*([^<]+?)\\s*</(?:knowledge_base_id|kb_id)>", CASE_INSENSITIVE | DOTALL);

    static final Pattern SHORT_SOURCE_HANDLE = Pattern.compile("^[cdbw][1-9][0-9]*$", CASE_INSENSITIVE);
    static final Pattern SHORT_SOURCE_HANDLE_IN_TEXT = Pattern.compile("\\b[cdbw][1-9][0-9]*\\b", CASE_INSENSITIVE);

    /** 一个 chunk 引用的元数据（Go ChunkReference）。 */
    static final class ChunkReference {
        String chunkId = "";
        String knowledgeId = "";
        String knowledgeBaseId = "";
        String documentTitle = "";
        int chunkIndex;
        String chunkType = "";
    }

    /** 每个 web 页面存在原始 URL 旁边的元数据（Go webMeta）。 */
    static final class WebMeta {
        String title = "";

        WebMeta(String title) {
            this.title = title == null ? "" : title;
        }
    }

    final boolean citationsEnabled;
    /**
     * 历史/目录/工具参数里可寻址的 ID 在当前工具结果供源之前不是证据
     * （Go 的 citable sync.Map；注册可能并发）。
     */
    private final Set<String> citable = ConcurrentHashMap.newKeySet();

    final HandleStore<ChunkReference> chunks;
    private final HandleStore<Object> docs;
    private final HandleStore<Object> kbs;
    final HandleStore<WebMeta> webs;

    SourceRegistry(boolean citationsEnabled) {
        this.citationsEnabled = citationsEnabled;
        this.chunks = new HandleStore<>("c", 0, 1);
        this.docs = new HandleStore<>("d", 0, 1);
        this.kbs = new HandleStore<>("b", 0, 1);
        this.webs = new HandleStore<>("w", 0, 1);
    }

    int count() {
        return chunks.size() + webs.size();
    }

    // ---- 协议提示词 ----

    static String sourceProtocolPrompt(boolean citationsOn) {
        if (citationsOn) {
            return SOURCE_HANDLE_PROTOCOL_PROMPT + CITATION_ENABLED_PROTOCOL_PROMPT;
        }
        return SOURCE_HANDLE_PROTOCOL_PROMPT + CITATION_DISABLED_PROTOCOL_PROMPT;
    }

    String protocolPrompt() {
        return sourceProtocolPrompt(citationsEnabled);
    }

    // ---- 注册 ----

    /** handle 形状输入的共享守卫：模型回显的句柄仅当已存在时才回显，绝不当作新持久身份。 */
    private static String knownHandle(HandleStore<?> table, String id) {
        String handle = id.toLowerCase();
        if (table.has(handle)) {
            return handle;
        }
        return "";
    }

    String registerChunk(ChunkReference ref) {
        return registerChunk(ref, true);
    }

    String registerChunk(ChunkReference ref, boolean evidence) {
        if (ref == null) {
            return "";
        }
        ref.chunkId = ref.chunkId == null ? "" : ref.chunkId.strip();
        if (ref.chunkId.isEmpty()) {
            return "";
        }
        if (SHORT_SOURCE_HANDLE.matcher(ref.chunkId).matches()) {
            return knownHandle(chunks, ref.chunkId);
        }
        String handle = chunks.register(ref.chunkId, ref.chunkId, ref, SourceRegistry::mergeChunkReference);
        if (evidence) {
            citable.add(handle);
        }
        return handle;
    }

    private static void mergeChunkReference(ChunkReference dst, ChunkReference src) {
        if (dst.knowledgeId.isEmpty()) {
            dst.knowledgeId = src.knowledgeId;
        }
        if (dst.knowledgeBaseId.isEmpty()) {
            dst.knowledgeBaseId = src.knowledgeBaseId;
        }
        if (dst.documentTitle.isEmpty()) {
            dst.documentTitle = src.documentTitle;
        }
        if (dst.chunkIndex == 0) {
            dst.chunkIndex = src.chunkIndex;
        }
        if (dst.chunkType.isEmpty()) {
            dst.chunkType = src.chunkType;
        }
    }

    String registerDocument(String id) {
        id = id == null ? "" : id.strip();
        if (id.isEmpty()) {
            return "";
        }
        if (SHORT_SOURCE_HANDLE.matcher(id).matches()) {
            return knownHandle(docs, id);
        }
        return docs.register(id, id, null, null);
    }

    String registerKnowledgeBase(String id) {
        id = id == null ? "" : id.strip();
        if (id.isEmpty()) {
            return "";
        }
        if (SHORT_SOURCE_HANDLE.matcher(id).matches()) {
            return knownHandle(kbs, id);
        }
        return kbs.register(id, id, null, null);
    }

    String registerWeb(String rawURL, String title) {
        return registerWeb(rawURL, title, true);
    }

    String registerWeb(String rawURL, String title, boolean evidence) {
        rawURL = rawURL == null ? "" : rawURL.strip();
        if (rawURL.isEmpty()) {
            return "";
        }
        if (SHORT_SOURCE_HANDLE.matcher(rawURL).matches()) {
            return knownHandle(webs, rawURL);
        }
        // 以规范化（去 fragment）URL 去重，但解码回模型最初看到的原始 URL
        String handle = webs.register(canonicalWebURL(rawURL), rawURL, new WebMeta(title), (dst, src) -> {
            if (dst.title.isEmpty() && !src.title.isEmpty()) {
                dst.title = src.title;
            }
        });
        if (evidence) {
            citable.add(handle);
        }
        return handle;
    }

    /** 规范化 URL：可解析且有 scheme+host 时去掉 fragment；否则原样（去首尾空白）。 */
    static String canonicalWebURL(String raw) {
        raw = raw == null ? "" : raw.strip();
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd <= 0) {
            return raw;
        }
        String scheme = raw.substring(0, schemeEnd);
        if (!scheme.matches("[a-zA-Z][a-zA-Z0-9+.-]*")) {
            return raw;
        }
        String rest = raw.substring(schemeEnd + 3);
        int hash = rest.indexOf('#');
        if (hash < 0) {
            return raw;
        }
        String authorityAndMore = rest.substring(0, hash);
        int slash = authorityAndMore.indexOf('/');
        String authority = slash < 0 ? authorityAndMore : authorityAndMore.substring(0, slash);
        if (authority.isEmpty()) {
            return raw;
        }
        return raw.substring(0, schemeEnd + 3 + hash);
    }

    void registerSearchResults(List<com.ragagent.retrieval.domain.SearchResult> results) {
        if (results == null) {
            return;
        }
        for (com.ragagent.retrieval.domain.SearchResult result : results) {
            if (result == null) {
                continue;
            }
            registerDocument(result.getKnowledgeId());
            registerKnowledgeBase(result.getKnowledgeBaseId());
            ChunkReference ref = new ChunkReference();
            ref.chunkId = result.getId();
            ref.knowledgeId = result.getKnowledgeId();
            ref.knowledgeBaseId = result.getKnowledgeBaseId();
            ref.documentTitle = firstNonEmpty(result.getKnowledgeTitle(), result.getKnowledgeFilename());
            ref.chunkIndex = result.getChunkIndex();
            ref.chunkType = result.getChunkType();
            registerChunk(ref);
        }
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.strip().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    String chunkHandle(String id) {
        return chunks.handleForKey(id);
    }

    /** 包内测试/聚合断言用（dN 空间）。 */
    String docsHandle(String id) {
        return docs.handleForKey(id);
    }

    String kbsHandle(String id) {
        return kbs.handleForKey(id);
    }

    String websHandle(String id) {
        return webs.handleForKey(id);
    }

    int websCount() {
        return webs.size();
    }

    // ---- 工具参数编解码 ----

    /** 工具参数策略（Go toolArgumentPolicy）：某 tool 的某 key 是否归该工具契约。 */
    interface KeyPolicy {
        boolean allowed(String toolName, String key);
    }

    /** 只还原具名工具显式拥有的字段里的句柄（对照 DecodeToolCallsWithPolicy）。 */
    void decodeToolCallsWithPolicy(List<ToolCall> toolCalls, KeyPolicy policy) {
        for (ToolCall call : toolCalls) {
            String toolName = call.getFunction().getName();
            call.getFunction().setArguments(decodeJSONWithPolicy(
                    call.getFunction().getArguments(), false,
                    key -> policy == null || policy.allowed(toolName, key)));
        }
    }

    /** 只在具名工具声明的 source 契约字段里报告未知句柄（对照 UnresolvedToolHandlesWithPolicy）。 */
    List<String> unresolvedToolHandlesWithPolicy(String toolName, String raw, KeyPolicy policy) {
        if (raw == null || raw.strip().isEmpty()) {
            return null;
        }
        JsonNode value = parseJson(raw);
        if (value == null) {
            return null;
        }
        Set<String> seen = setOf();
        collectUnresolvedToolHandles("", value, seen,
                key -> policy == null || policy.allowed(toolName, key));
        List<String> result = new ArrayList<>(seen);
        java.util.Collections.sort(result);
        return result;
    }

    private void collectUnresolvedToolHandles(String key, JsonNode value, Set<String> seen, java.util.function.Predicate<String> allowed) {
        if (value.isTextual()) {
            String lowerKey = key.toLowerCase();
            if (!ToolPolicy.sourceKeySpaces.containsKey(lowerKey) || !allowed.test(lowerKey)) {
                return;
            }
            String handle = value.asText().strip();
            if (SHORT_SOURCE_HANDLE.matcher(handle).matches() && durableForHandle(handle).isEmpty()) {
                seen.add(handle);
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                collectUnresolvedToolHandles(key, item, seen, allowed);
            }
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                collectUnresolvedToolHandles(e.getKey(), e.getValue(), seen, allowed);
            }
        }
    }

    /**
     * 压缩回放消息中的已知真实标识，并按工具名闸住 tool 结果的 source 处理
     * （对照 EncodeMessagesWithPolicies）。nil 策略保留旧的通用行为。
     */
    List<ChatMessage> encodeMessagesWithPolicies(List<ChatMessage> messages, KeyPolicy argumentPolicy, java.util.function.Predicate<String> resultPolicy) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> out = copyMessages(messages);
        // 第一遍：注册历史工具调用与规范 assistant 引用中的每个持久标识。
        // 两遍形状让早到的 tool 消息能复用只出现在本轮最终答案里的元数据。
        for (int i = 0; i < out.size(); i++) {
            ChatMessage m = out.get(i);
            boolean processToolResult = "tool".equals(m.getRole()) && (resultPolicy == null || resultPolicy.test(m.getName()));
            if ("assistant".equals(m.getRole()) || processToolResult) {
                m.setContent(compactPublicCitations(m.getContent(), false));
                m.setReasoningContent(compactPublicCitations(m.getReasoningContent(), false));
            }
            if (m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                List<com.ragagent.llm.domain.MessageContentPart> parts =
                        new ArrayList<>(m.getMultiContent());
                m.setMultiContent(parts);
                for (int j = 0; j < parts.size(); j++) {
                    if ("text".equals(parts.get(j).getType()) && ("assistant".equals(m.getRole()) || processToolResult)) {
                        parts.get(j).setText(compactPublicCitations(parts.get(j).getText(), false));
                    }
                }
            }
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<com.ragagent.llm.domain.ToolCall> calls = new ArrayList<>(m.getToolCalls());
                m.setToolCalls(calls);
                for (com.ragagent.llm.domain.ToolCall call : calls) {
                    String toolName = call.getFunction().getName();
                    registerToolArguments(call.getFunction().getArguments(),
                            key -> argumentPolicy == null || argumentPolicy.allowed(toolName, key));
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            ChatMessage m = out.get(i);
            if ("tool".equals(m.getRole()) && (resultPolicy == null || resultPolicy.test(m.getName()))) {
                registerLegacyToolReferences(m.getContent(), false);
                m.setContent(compactKnownText(m.getContent()));
            }
            if (m.getToolCalls() != null) {
                for (com.ragagent.llm.domain.ToolCall call : m.getToolCalls()) {
                    String toolName = call.getFunction().getName();
                    call.getFunction().setArguments(decodeJSONWithPolicy(
                            call.getFunction().getArguments(), true,
                            key -> argumentPolicy == null || argumentPolicy.allowed(toolName, key)));
                }
            }
        }
        return out;
    }

    private static List<ChatMessage> copyMessages(List<ChatMessage> messages) {
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            ChatMessage c = new ChatMessage();
            c.setRole(m.getRole());
            c.setContent(m.getContent());
            c.setMultiContent(m.getMultiContent());
            c.setName(m.getName());
            c.setToolCallId(m.getToolCallId());
            c.setToolCalls(m.getToolCalls());
            c.setImages(m.getImages());
            c.setReasoningContent(m.getReasoningContent());
            c.setKind(m.getKind());
            out.add(c);
        }
        return out;
    }

    /** 结构化表达式（如 SQL 参数）里已注册 source 句柄的还原；不得用于任意 prose。 */
    String decodeKnownText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return HandleStore.replaceAll(SHORT_SOURCE_HANDLE_IN_TEXT, text, handle -> {
            String real = durableForHandle(handle);
            return real.isEmpty() ? handle : real;
        });
    }

    /** 只还原单/双引号或反引号包裹段内的 source 句柄（对照 DecodeKnownQuotedText）。 */
    String decodeKnownQuotedText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return rewriteQuotedText(text, segment -> HandleStore.replaceAll(SHORT_SOURCE_HANDLE_IN_TEXT, segment, handle -> {
            String real = durableForHandle(handle);
            return real.isEmpty() ? handle : real;
        }));
    }

    /** 引号结构文本段内不在本请求 registry 的 handle 形状值（对照 UnresolvedQuotedTextHandles）。 */
    List<String> unresolvedQuotedTextHandles(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Set<String> seen = setOf();
        rewriteQuotedText(text, segment -> {
            Matcher m = SHORT_SOURCE_HANDLE_IN_TEXT.matcher(segment);
            while (m.find()) {
                if (durableForHandle(m.group()).isEmpty()) {
                    seen.add(m.group());
                }
            }
            return segment;
        });
        List<String> result = new ArrayList<>(seen);
        java.util.Collections.sort(result);
        return result;
    }

    /** 引号段扫描（对照 rewriteQuotedText）：单引号/双引号/反引号，'' 双写与 \\ 转义都留在同一段里。 */
    static String rewriteQuotedText(String text, java.util.function.UnaryOperator<String> rewrite) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char quote = text.charAt(i);
            if (quote != '\'' && quote != '"' && quote != '`') {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            int start = i;
            i++;
            while (i < text.length()) {
                if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                    i += 2;
                    continue;
                }
                if (text.charAt(i) != quote) {
                    i++;
                    continue;
                }
                // SQL 用双写引号（''）转义引号——继续扫同一段字面量
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                i++;
                break;
            }
            out.append(rewrite.apply(text.substring(start, i)));
        }
        return out.toString();
    }

    void registerToolArguments(String raw, java.util.function.Predicate<String> allowed) {
        if (raw == null || raw.strip().isEmpty()) {
            return;
        }
        JsonNode value = parseJson(raw);
        if (value == null) {
            return;
        }
        registerToolArgumentValue("", value, allowed);
    }

    private void registerToolArgumentValue(String key, JsonNode value, java.util.function.Predicate<String> allowed) {
        if (value.isTextual()) {
            if (allowed.test(key.toLowerCase())) {
                registerSourceIDByKey(key, value.asText(), false);
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                registerToolArgumentValue(key, item, allowed);
            }
        } else if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                registerToolArgumentValue(e.getKey(), e.getValue(), allowed);
            }
        }
    }

    /**
     * key→source 空间的唯一分派（对照 registerSourceIDByKey），由 sourceKeySpaces 驱动，
     * 注册与解码的键集合（以及 web 引用的 http/https 守卫）不会漂移。
     */
    void registerSourceIDByKey(String key, String value, boolean evidence) {
        value = value == null ? "" : value.strip();
        if (value.isEmpty() || SHORT_SOURCE_HANDLE.matcher(value).matches()) {
            return;
        }
        ToolPolicy.SourceKeySpace space = ToolPolicy.sourceKeySpaces.get(key.toLowerCase());
        if (space == null) {
            return;
        }
        switch (space) {
            case CHUNK -> registerChunk(refOf(value), evidence);
            case DOCUMENT -> registerDocument(value);
            case DOCUMENT_REF -> {
                // 存储的 refs 用 "knowledgeID|title"；只有 ID 部分是持久的
                int bar = value.indexOf('|');
                String id = bar < 0 ? value : value.substring(0, bar);
                registerDocument(id.strip());
            }
            case KNOWLEDGE_BASE -> registerKnowledgeBase(value);
            case WEB -> {
                // 只有公共网页成为 web 引用；res://、存储 provider 等内部 scheme
                // 绝不进入 web 句柄空间（CompactKnownText 会二次改写它们）
                if (isHttpUrl(value)) {
                    registerWeb(value, "", evidence);
                }
            }
        }
    }

    private static ChunkReference refOf(String chunkId) {
        ChunkReference ref = new ChunkReference();
        ref.chunkId = chunkId;
        return ref;
    }

    private static boolean isHttpUrl(String value) {
        String v = value.strip();
        if (v.toLowerCase().startsWith("http://") || v.toLowerCase().startsWith("https://")) {
            return true;
        }
        // 对照 Go：url.Parse(value) 后检查 parsed.Scheme——scheme 解析失败（含控制字符等）不算 http(s)
        return false;
    }

    String decodeJSONWithPolicy(String raw, boolean encode, java.util.function.Predicate<String> allowed) {
        if (raw == null || raw.strip().isEmpty()) {
            return raw;
        }
        JsonNode value = parseJson(raw);
        if (value == null) {
            return raw;
        }
        value = GoJsonValues.goFloatTree(value);
        value = walkJSON("", value, encode, allowed);
        return GoJsonCodec.write(value);
    }

    private JsonNode walkJSON(String key, JsonNode value, boolean encode, java.util.function.Predicate<String> allowed) {
        if (value.isTextual()) {
            String typed = value.asText();
            if (!allowed.test(key.toLowerCase())) {
                return value;
            }
            if (encode) {
                // 编码按精确真实标识（UUID/URL）匹配，不与 prose 冲突，保持 key 无关
                String handle = handleForDurable(typed);
                return handle.isEmpty() ? value : TextNode.valueOf(handle);
            }
            // 解码只针对 ID 键，且值是 handle 形状时才替换
            if (!ToolPolicy.sourceKeySpaces.containsKey(key.toLowerCase())) {
                return value;
            }
            if (!SHORT_SOURCE_HANDLE.matcher(typed.strip()).matches()) {
                return value;
            }
            String real = durableForHandle(typed);
            return real.isEmpty() ? value : TextNode.valueOf(real);
        }
        if (value.isArray()) {
            ArrayNode array = (ArrayNode) value;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, walkJSON(key, array.get(i), encode, allowed));
            }
        } else if (value.isObject()) {
            ObjectNode obj = (ObjectNode) value;
            var fields = obj.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            fields.forEachRemaining(entries::add);
            for (Map.Entry<String, JsonNode> e : entries) {
                obj.set(e.getKey(), walkJSON(e.getKey(), e.getValue(), encode, allowed));
            }
        }
        return value;
    }

    String handleForDurable(String real) {
        String handle = chunks.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = docs.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = kbs.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = webs.handleForKey(canonicalWebURL(real));
        // 对照 Go 的 (handle, ok) 双返回：未命中即零值 ""，不能把 null 留给
        // 调用方（walkJSON 的 encode 分支对返回值直接 isEmpty——MCP 工具参数
        // 经此路径时曾 NPE）。
        return handle == null ? "" : handle;
    }

    String durableForHandle(String handle) {
        handle = handle == null ? "" : handle.strip().toLowerCase();
        String real = chunks.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = docs.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = kbs.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = webs.resolveValue(handle);
        if (real != null) {
            return real;
        }
        return "";
    }

    /** 只压缩已从结构化运行时/工具数据注册过的标识（对照 CompactKnownText）。 */
    String compactKnownText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 快照横跨全部四张 source 表并按长值优先 GLOBALLY 排序：web URL 可能包含
        // 已注册的 document UUID 作为子串，按表分趟会毁掉长值
        List<HandleStore.Pair<?>> pairs = new ArrayList<>();
        pairs.addAll(chunks.pairs());
        pairs.addAll(docs.pairs());
        pairs.addAll(kbs.pairs());
        pairs.addAll(webs.pairs());
        pairs.sort(Comparator.comparingInt((HandleStore.Pair<?> p) -> p.value.length()).reversed());
        for (HandleStore.Pair<?> item : pairs) {
            if (!item.value.isEmpty()) {
                text = text.replace(item.value, item.handle);
            }
        }
        return text;
    }

    // ---- citations.go：公共引用面 ----

    /** 历史/遗留标签里的引用只登记导航句柄；当前源工具的成功结果才能授证据。 */
    void registerLegacyToolReferences(String text, boolean evidence) {
        if (text == null || text.isEmpty()) {
            return;
        }
        registerLabeledReferences(text);
        Matcher m = LEGACY_CHUNK_TAG.matcher(text);
        while (m.find()) {
            String tag = m.group();
            String chunkID = firstNonEmptyOf(publicAttr(CHUNK_ATTR, tag), publicAttr(FAQ_ATTR, tag));
            if (chunkID.isEmpty()) {
                continue;
            }
            ChunkReference ref = new ChunkReference();
            ref.chunkId = chunkID;
            ref.knowledgeId = publicAttr(DOCUMENT_ATTR, tag);
            ref.knowledgeBaseId = firstNonEmptyOf(publicAttr(KB_ATTR, tag), publicAttr(PUBLIC_KB_ATTR, tag));
            ref.documentTitle = firstNonEmptyOf(publicAttr(KNOWLEDGE_TITLE_ATTR, tag), publicAttr(DOC_ATTR, tag));
            registerChunk(ref, evidence);
        }
    }

    /**
     * 把规范引用折叠回私有协议（对照 CompactPublicCitations）。
     * 历史引用只登记导航句柄；成功的当前源工具返回的引用还能授证据。
     */
    String compactPublicCitations(String text, boolean evidence) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        text = replaceAllFunc(PUBLIC_KB_TAG, text, tag -> {
            String chunkID = publicAttr(CHUNK_ATTR, tag);
            if (chunkID.isEmpty()) {
                return tag;
            }
            ChunkReference ref = new ChunkReference();
            ref.chunkId = chunkID;
            ref.knowledgeBaseId = publicAttr(PUBLIC_KB_ATTR, tag);
            ref.documentTitle = publicAttr(DOC_ATTR, tag);
            String handle = registerChunk(ref, evidence);
            return "<ref id=\"" + handle + "\"/>";
        });
        return replaceAllFunc(PUBLIC_WEB_TAG, text, tag -> {
            String rawURL = publicAttr(URL_ATTR, tag);
            if (rawURL.isEmpty()) {
                return tag;
            }
            String handle = registerWeb(rawURL, publicAttr(TITLE_ATTR, tag), evidence);
            return "<ref id=\"" + handle + "\"/>";
        });
    }

    /** 属性正则的第一个捕获组，HTML 反转义后返回（对照 publicAttr）。 */
    static String publicAttr(Pattern expression, String tag) {
        Matcher m = expression.matcher(tag);
        if (!m.find()) {
            return "";
        }
        return GoHtml.unescape(m.group(1));
    }

    /**
     * 私有模型协议 → 公共 <kb/>/<web/> 契约（对照 ExpandText）。
     * 未知句柄 fail closed 并消失。
     */
    String expandText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 公共引用标签是只输出的。模型直接写的实例先丢弃，再从已注册句柄重建规范标签
        text = MODEL_KB_TAG.matcher(text).replaceAll("");
        text = MODEL_WEB_TAG.matcher(text).replaceAll("");
        if (!citationsEnabled) {
            return REF_CANDIDATE.matcher(text).replaceAll("");
        }
        return replaceAllFunc(REF_CANDIDATE, text, tag -> {
            Matcher rm = REF_TAG.matcher(tag);
            if (!rm.find()) {
                return "";
            }
            String handle = rm.group(1).toLowerCase();
            if (!citable.contains(handle)) {
                return "";
            }
            StringBuilder valueOut = new StringBuilder();
            ChunkReference chunkRef = chunks.resolve(handle, valueOut);
            if (chunkRef != null) {
                String chunkID = valueOut.toString();
                String attrs = "doc=\"" + escapeAttr(chunkRef.documentTitle) + "\" chunk_id=\"" + escapeAttr(chunkID) + "\"";
                if (!chunkRef.knowledgeBaseId.isEmpty()) {
                    attrs += " kb_id=\"" + escapeAttr(chunkRef.knowledgeBaseId) + "\"";
                }
                return "<kb " + attrs + " />";
            }
            WebMeta web = webs.resolve(handle, valueOut);
            if (web != null) {
                return "<web url=\"" + escapeAttr(valueOut.toString()) + "\" title=\"" + escapeAttr(web.title) + "\" />";
            }
            return "";
        });
    }

    static String escapeAttr(String value) {
        return GoHtml.escape(value);
    }

    /** refTagRE.MatchString 的等价（流式展开器判完整 <ref> 标签用）。 */
    static boolean refTagMatches(String tag) {
        return REF_TAG.matcher(tag).find();
    }

    static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- 小工具 ----

    private static String firstNonEmptyOf(String... values) {
        return firstNonEmpty(values);
    }

    private static Set<String> setOf() {
        return java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    private static JsonNode parseJson(String raw) {
        return GoJsonValues.parse(raw);
    }

    /** Pattern → replaceAllStringFunc（Go 语义：回调返回值按字面拼回）。 */
    static String replaceAllFunc(Pattern pattern, String text, java.util.function.UnaryOperator<String> fn) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m.group())));
        }
        if (!any) {
            return text;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    void registerLabeledReferences(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (Pattern expression : new Pattern[] {DOCUMENT_ATTR, DOCUMENT_ELEMENT}) {
            Matcher m = expression.matcher(text);
            while (m.find()) {
                registerDocument(m.group(1).strip());
            }
        }
        for (Pattern expression : new Pattern[] {KB_ATTR, KB_ELEMENT}) {
            Matcher m = expression.matcher(text);
            while (m.find()) {
                registerKnowledgeBase(m.group(1).strip());
            }
        }
    }
}
