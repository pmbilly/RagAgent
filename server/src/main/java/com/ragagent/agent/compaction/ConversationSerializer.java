package com.ragagent.agent.compaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.GoDoubleSerializer;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * 摘要用对话序列化（对照 Go internal/agent/compaction/serialize.go 全文）。
 *
 * <p>把消息渲染成<b>文字记录</b>而不是作为对话传入：拿到真消息的模型会续写它们；
 * 拿到文字记录才会摘要。</p>
 */
public final class ConversationSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 摘要请求里单个工具结果的字符上限（对照 toolResultMaxChars）。工具输出是上下文
     * 体积的最大贡献者，摘要要的是要点不是字节。
     */
    private static final int TOOL_RESULT_MAX_CHARS = 2000;

    /** user/assistant 正文的字符上限（对照 textMaxChars）。很少是问题，但也不该无界。 */
    private static final int TEXT_MAX_CHARS = 4000;

    /**
     * 工具调用参数的渲染上限（对照 toolArgsMaxChars）。write_sandbox_file 的参数里
     * 装着整个文件体；摘要需要路径和"写过"这个事实，绝不需要内容。
     */
    private static final int TOOL_ARGS_MAX_CHARS = 400;

    private ConversationSerializer() {
    }

    /** 把消息渲染成文字记录（对照 serializeConversation）。 */
    public static String serializeConversation(List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage msg : messages) {
                // Java ChatMessage 的 name/toolCallId/reasoningContent 可为 null
                //（Go 零值 ""；llm.domain 既有约定），一律 null 安全读取
                String reasoning = nvl(msg.getReasoningContent());
                String content = nvl(msg.getContent());
                String name = nvl(msg.getName());
                switch (msg.getRole()) {
                    case "system" -> {
                        continue;
                    }
                    case "user" -> {
                        String c = truncate(content, TEXT_MAX_CHARS);
                        if (!c.isEmpty()) {
                            parts.add("[User]: " + c);
                        }
                    }
                    case "assistant" -> {
                        if (!reasoning.isEmpty()) {
                            parts.add("[Assistant thinking]: "
                                    + truncate(reasoning, TEXT_MAX_CHARS));
                        }
                        if (!content.isEmpty()) {
                            parts.add("[Assistant]: " + truncate(content, TEXT_MAX_CHARS));
                        }
                        String calls = serializeToolCalls(msg.getToolCalls());
                        if (!calls.isEmpty()) {
                            parts.add("[Assistant tool calls]: " + calls);
                        }
                    }
                    case "tool" -> {
                        String c = truncate(content, TOOL_RESULT_MAX_CHARS);
                        if (!c.isEmpty()) {
                            parts.add("[Tool result %s]: %s".formatted(name, c));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return String.join("\n\n", parts);
    }

    /** 渲染工具调用列表（对照 serializeToolCalls）。 */
    public static String serializeToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(calls.size());
        for (ToolCall tc : calls) {
            rendered.add("%s(%s)".formatted(nvl(tc.getFunction().getName()),
                    renderToolArgs(nvl(tc.getFunction().getArguments()))));
        }
        return String.join("; ", rendered);
    }

    /**
     * 把参数 JSON 渲染成 {@code key=value} 对，丢弃超长值（对照 renderToolArgs）。
     * 键排序保证同一调用渲染结果恒定——跨压缩比较文字记录时用得上。
     *
     * <p>数值语义对齐 Go：{@code json.Unmarshal} 进 {@code any} 后<b>一切数字都是
     * float64</b>（大整数丢精度、1e21 记成 1e+21），序列化再按 Go 的 float 编码器
     * 输出。Java 侧把数值节点统一转 double 再用 {@link GoDoubleSerializer}。</p>
     */
    public static String renderToolArgs(String arguments) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(arguments == null ? "" : arguments);
        } catch (Exception e) {
            return truncate(arguments, TOOL_ARGS_MAX_CHARS);
        }
        if (parsed == null || !parsed.isObject()) {
            return truncate(arguments, TOOL_ARGS_MAX_CHARS);
        }
        try {
            List<String> pairs = new ArrayList<>();
            for (String k : sortedKeys(parsed)) {
                pairs.add("%s=%s".formatted(k,
                        truncate(goMarshal(parsed.get(k)), TOOL_ARGS_MAX_CHARS)));
            }
            return String.join(", ", pairs);
        } catch (GoMarshalException e) {
            // Go: 数字超出 float64 范围时整个 Unmarshal 报错 → 回退到截断原文
            return truncate(arguments, TOOL_ARGS_MAX_CHARS);
        }
    }

    /** 按字节序排键（Go 的 map 键排序是字节序；Java String.compareTo 是 UTF-16 序）。 */
    private static List<String> sortedKeys(JsonNode obj) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        keys.sort(ConversationSerializer::compareUtf8);
        return keys;
    }

    static int compareUtf8(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(ba.length, bb.length);
        for (int i = 0; i < n; i++) {
            int x = ba[i] & 0xFF;
            int y = bb[i] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return ba.length - bb.length;
    }

    private static final class GoMarshalException extends RuntimeException {
    }

    /**
     * Go json.Marshal 的值编码（HTML 转义 + 键字节序 + float64 语义）。
     * 递归遍历 JsonNode 而不是用 Jackson mapper：树里的 DoubleNode 走不到
     * DoubleSerializer，且 Go 先把一切数字归一成 float64。
     */
    private static String goMarshal(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        goMarshalInto(node, sb);
        return sb.toString();
    }

    private static void goMarshalInto(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            sb.append('{');
            boolean first = true;
            for (String k : sortedKeys(node)) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                goEscapeString(k, sb);
                sb.append(':');
                goMarshalInto(node.get(k), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode item : node) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                goMarshalInto(item, sb);
            }
            sb.append(']');
            return;
        }
        if (node.isNumber()) {
            double d = node.asDouble();
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                // Go: json.Unmarshal 对越界数字直接报错
                throw new GoMarshalException();
            }
            sb.append(GoDoubleSerializer.format(d));
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue());
            return;
        }
        goEscapeString(node.asText(), sb);
    }

    /**
     * Go encoding/json 的字符串编码：短转义 + 控制字符小写十六进制 +
     * HTML 转义（{@code < > &}）+ U+2028/9。
     */
    private static void goEscapeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * 按字符（rune）截断并加省略标记（对照 truncate）。Go 的 TrimSpace 按
     * unicode.IsSpace（含 NBSP/NEL），Java 的 strip() 不含——单独实现。
     */
    public static String truncate(String s, int maxChars) {
        String t = goTrimSpace(s);
        int[] runes = t.codePoints().toArray();
        if (runes.length <= maxChars) {
            return t;
        }
        String head = new String(runes, 0, maxChars);
        return "%s\n\n[... %d more characters truncated]".formatted(head, runes.length - maxChars);
    }

    /** unicode.IsSpace 语义的 TrimSpace（Go strings.TrimSpace）。 */
    /** Go 零值语义：null 字符串按 "" 处理（llm.domain 可空字段约定）。 */
    public static String nvl(String s) {
        return s == null ? "" : s;
    }

    public static String goTrimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    public static boolean isGoSpace(int cp) {
        return cp == '\t' || cp == '\n' || cp == 0x0B || cp == '\f' || cp == '\r'
                || cp == ' ' || cp == 0x85 || cp == 0xA0
                || Character.isSpaceChar(cp);
    }

    /**
     * 摘要器不可用时的兜底档案（对照 rawArchive）。有损且无结构，但保住了下一轮
     * 不重做已完成工作所需的工具名和路径。
     */
    public static String rawArchive(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Raw conversation archive (LLM summarization unavailable):\n\n");
        if (messages != null) {
            for (ChatMessage msg : messages) {
                String content = nvl(msg.getContent());
                String name = nvl(msg.getName());
                switch (msg.getRole()) {
                    case "user" -> sb.append("- User: %s\n".formatted(truncate(content, 500)));
                    case "assistant" -> {
                        String calls = serializeToolCalls(msg.getToolCalls());
                        if (!calls.isEmpty()) {
                            sb.append("- Assistant [%s]: %s\n"
                                    .formatted(calls, truncate(content, 500)));
                            continue;
                        }
                        sb.append("- Assistant: %s\n".formatted(truncate(content, 500)));
                    }
                    case "tool" -> sb.append("- Tool[%s]: %s\n"
                            .formatted(name, truncate(content, 500)));
                    default -> {
                    }
                }
            }
        }
        return sb.toString();
    }

    /** GoDoubleSerializer 已有 Go 的最短往返 + 科学计数切换，直接复用。 */
}
