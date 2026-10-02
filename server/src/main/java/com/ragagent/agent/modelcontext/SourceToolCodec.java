package com.ragagent.agent.modelcontext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

import com.ragagent.common.web.GoJsonCodec;

/**
 * source 句柄的<b>工具参数编解码</b>（Go toolArgument encode/decode 族，
 * 自 {@code SourceRegistry} 的同名段外提）。
 *
 * <p>职责：把模型回显的短句柄还原成真实标识（decode）、把本轮注册过的真实标识
 * 压缩成句柄（encode/compact）、按工具契约键闸注册与报告未知句柄。
 * 四张 handle 表与注册/引用面仍在 {@link SourceRegistry}，本类经 {@code reg}
 * 回引用访问；键空间定义在 {@link ToolPolicy#sourceKeySpaces}。</p>
 */
final class SourceToolCodec {

    private final SourceRegistry reg;

    SourceToolCodec(SourceRegistry reg) {
        this.reg = reg;
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
        JsonNode value = SourceRegistry.parseJson(raw);
        if (value == null) {
            return null;
        }
        Set<String> seen = SourceRegistry.setOf();
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
            if (SourceRegistry.SHORT_SOURCE_HANDLE.matcher(handle).matches() && durableForHandle(handle).isEmpty()) {
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
                m.setContent(reg.compactPublicCitations(m.getContent(), false));
                m.setReasoningContent(reg.compactPublicCitations(m.getReasoningContent(), false));
            }
            if (m.getMultiContent() != null && !m.getMultiContent().isEmpty()) {
                List<com.ragagent.llm.domain.MessageContentPart> parts =
                        new ArrayList<>(m.getMultiContent());
                m.setMultiContent(parts);
                for (int j = 0; j < parts.size(); j++) {
                    if ("text".equals(parts.get(j).getType()) && ("assistant".equals(m.getRole()) || processToolResult)) {
                        parts.get(j).setText(reg.compactPublicCitations(parts.get(j).getText(), false));
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
                reg.registerLegacyToolReferences(m.getContent(), false);
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
        return HandleStore.replaceAll(SourceRegistry.SHORT_SOURCE_HANDLE_IN_TEXT, text, handle -> {
            String real = durableForHandle(handle);
            return real.isEmpty() ? handle : real;
        });
    }

    /** 只还原单/双引号或反引号包裹段内的 source 句柄（对照 DecodeKnownQuotedText）。 */
    String decodeKnownQuotedText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return rewriteQuotedText(text, segment -> HandleStore.replaceAll(SourceRegistry.SHORT_SOURCE_HANDLE_IN_TEXT, segment, handle -> {
            String real = durableForHandle(handle);
            return real.isEmpty() ? handle : real;
        }));
    }

    /** 引号结构文本段内不在本请求 registry 的 handle 形状值（对照 UnresolvedQuotedTextHandles）。 */
    List<String> unresolvedQuotedTextHandles(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Set<String> seen = SourceRegistry.setOf();
        rewriteQuotedText(text, segment -> {
            Matcher m = SourceRegistry.SHORT_SOURCE_HANDLE_IN_TEXT.matcher(segment);
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
        JsonNode value = SourceRegistry.parseJson(raw);
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
        if (value.isEmpty() || SourceRegistry.SHORT_SOURCE_HANDLE.matcher(value).matches()) {
            return;
        }
        ToolPolicy.SourceKeySpace space = ToolPolicy.sourceKeySpaces.get(key.toLowerCase());
        if (space == null) {
            return;
        }
        switch (space) {
            case CHUNK -> reg.registerChunk(refOf(value), evidence);
            case DOCUMENT -> reg.registerDocument(value);
            case DOCUMENT_REF -> {
                // 存储的 refs 用 "knowledgeID|title"；只有 ID 部分是持久的
                int bar = value.indexOf('|');
                String id = bar < 0 ? value : value.substring(0, bar);
                reg.registerDocument(id.strip());
            }
            case KNOWLEDGE_BASE -> reg.registerKnowledgeBase(value);
            case WEB -> {
                // 只有公共网页成为 web 引用；res://、存储 provider 等内部 scheme
                // 绝不进入 web 句柄空间（CompactKnownText 会二次改写它们）
                if (isHttpUrl(value)) {
                    reg.registerWeb(value, "", evidence);
                }
            }
        }
    }

    private static SourceRegistry.ChunkReference refOf(String chunkId) {
        SourceRegistry.ChunkReference ref = new SourceRegistry.ChunkReference();
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
        JsonNode value = SourceRegistry.parseJson(raw);
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
            if (!SourceRegistry.SHORT_SOURCE_HANDLE.matcher(typed.strip()).matches()) {
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
        String handle = reg.chunks.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = reg.docs.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = reg.kbs.handleForKey(real);
        if (handle != null) {
            return handle;
        }
        handle = reg.webs.handleForKey(reg.canonicalWebURL(real));
        // 对照 Go 的 (handle, ok) 双返回：未命中即零值 ""，不能把 null 留给
        // 调用方（walkJSON 的 encode 分支对返回值直接 isEmpty——MCP 工具参数
        // 经此路径时曾 NPE）。
        return handle == null ? "" : handle;
    }

    String durableForHandle(String handle) {
        handle = handle == null ? "" : handle.strip().toLowerCase();
        String real = reg.chunks.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = reg.docs.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = reg.kbs.resolveValue(handle);
        if (real != null) {
            return real;
        }
        real = reg.webs.resolveValue(handle);
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
        pairs.addAll(reg.chunks.pairs());
        pairs.addAll(reg.docs.pairs());
        pairs.addAll(reg.kbs.pairs());
        pairs.addAll(reg.webs.pairs());
        pairs.sort(Comparator.comparingInt((HandleStore.Pair<?> p) -> p.value.length()).reversed());
        for (HandleStore.Pair<?> item : pairs) {
            if (!item.value.isEmpty()) {
                text = text.replace(item.value, item.handle);
            }
        }
        return text;
    }


    /** 注册与解码的键→空间分派（转发注册段；ModelOutput 直呼 registry 的同签名）。 */
    void registerSourceIDByKeyForward(String key, String value, boolean evidence) {
        registerSourceIDByKey(key, value, evidence);
    }
}
