package com.ragagent.agent.modelcontext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * model-context registry 的 source-reference 半边：chunk/document/knowledge base/web page 的
 * 请求局部 cN/dN/bN/wN 句柄，以及把它们映射回持久标识的工具参数编解码器。
 * 请求生命周期统一经 {@link Registry}，source 与 resource 句柄不能乱序编解码。
 */
final class SourceRegistry {

    // ---- 常量：协议提示词（字节即契约）----

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

    // ---- 正则（两处语义修正：\s 用显式类；文本锚 $ 用 \z）----

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

    /** 一个 chunk 引用的元数据。 */
    static final class ChunkReference {
        String chunkId = "";
        String knowledgeId = "";
        String knowledgeBaseId = "";
        String documentTitle = "";
        int chunkIndex;
        String chunkType = "";
    }

    /** 每个 web 页面存在原始 URL 旁边的元数据。 */
    static final class WebMeta {
        String title = "";

        WebMeta(String title) {
            this.title = title == null ? "" : title;
        }
    }

    final boolean citationsEnabled;
    /**
     * 历史/目录/工具参数里可寻址的 ID 在当前工具结果供源之前不是证据
     * （注册可能并发，用并发安全集合）。
     */
    private final Set<String> citable = ConcurrentHashMap.newKeySet();

    final HandleStore<ChunkReference> chunks;
    final HandleStore<Object> docs;
    final HandleStore<Object> kbs;
    final HandleStore<WebMeta> webs;

    /** 工具参数编解码协作者（encode/decode/compact 族，见 {@link SourceToolCodec}）。 */
    private final SourceToolCodec toolCodec = new SourceToolCodec(this);

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

    void registerSearchResults(List<com.ragagent.common.retrieval.SearchResult> results) {
        if (results == null) {
            return;
        }
        for (com.ragagent.common.retrieval.SearchResult result : results) {
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

    // ---- 工具参数编解码（实现外提至 {@link SourceToolCodec}，门面保签名） ----

    /** 只还原具名工具显式拥有的字段里的句柄。 */
    void decodeToolCallsWithPolicy(List<ToolCall> toolCalls, SourceToolCodec.KeyPolicy policy) {
        toolCodec.decodeToolCallsWithPolicy(toolCalls, policy);
    }

    /** 只在具名工具声明的 source 契约字段里报告未知句柄。 */
    List<String> unresolvedToolHandlesWithPolicy(String toolName, String raw, SourceToolCodec.KeyPolicy policy) {
        return toolCodec.unresolvedToolHandlesWithPolicy(toolName, raw, policy);
    }

    /** 压缩回放消息中的已知真实标识，并按工具名闸住 tool 结果的 source 处理。 */
    List<ChatMessage> encodeMessagesWithPolicies(List<ChatMessage> messages, SourceToolCodec.KeyPolicy argumentPolicy,
            java.util.function.Predicate<String> resultPolicy) {
        return toolCodec.encodeMessagesWithPolicies(messages, argumentPolicy, resultPolicy);
    }

    /** 还原文本里全部已知句柄。 */
    String decodeKnownText(String text) {
        return toolCodec.decodeKnownText(text);
    }

    /** 只还原单/双引号或反引号包裹段内的 source 句柄。 */
    String decodeKnownQuotedText(String text) {
        return toolCodec.decodeKnownQuotedText(text);
    }

    /** 引号结构文本段内不在本请求 registry 的 handle 形状值。 */
    List<String> unresolvedQuotedTextHandles(String text) {
        return toolCodec.unresolvedQuotedTextHandles(text);
    }

    /** key→source 空间的唯一分派。 */
    void registerSourceIDByKey(String key, String value, boolean evidence) {
        toolCodec.registerSourceIDByKey(key, value, evidence);
    }

    /** 只压缩已注册标识为句柄。 */
    String compactKnownText(String text) {
        return toolCodec.compactKnownText(text);
    }

    String handleForDurable(String real) {
        return toolCodec.handleForDurable(real);
    }

    String durableForHandle(String handle) {
        return toolCodec.durableForHandle(handle);
    }

    // ---- 公共引用面 ----

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
     * 把规范引用折叠回私有协议。
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

    /** 属性正则的第一个捕获组，HTML 反转义后返回。 */
    static String publicAttr(Pattern expression, String tag) {
        Matcher m = expression.matcher(tag);
        if (!m.find()) {
            return "";
        }
        return GoHtml.unescape(m.group(1));
    }

    /**
     * 私有模型协议 → 公共 <kb/>/<web/> 契约。
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

    static Set<String> setOf() {
        return java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    static JsonNode parseJson(String raw) {
        return GoJsonValues.parse(raw);
    }

    /** 正则替换（回调返回值按字面拼回）。 */
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
