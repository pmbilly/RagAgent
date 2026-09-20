package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.llm.domain.ChatMessage;

/**
 * LLM 兼容性消息清洗（对照 Go {@code sanitize_messages.go} 的 SanitizeMessages，逐字移植）。
 * 处理会导致 provider API 报错的常见问题：
 * <ul>
 *   <li>连续同角色消息（部分 provider 直接拒绝）→ 合并；</li>
 *   <li>tool 结果消息在前面的 assistant 消息里找不到对应 tool_call → 降级为
 *       {@code <untrusted_tool_result>} 包裹的 user 消息（外部输出永不能升格为策略）；</li>
 *   <li>空内容消息（会引发 API 错误）→ 丢弃（system 除外）。</li>
 * </ul>
 *
 * <p>Go 实录：合并用 {@code "\n\n"} 连接；降级包裹的转义是 Go {@code html.EscapeString}
 * 语义（{@code " → &#34;}，不是 {@code &quot;}——实录 SANITIZE 6 钉死）；
 * 孤儿判定查的是<b>原始输入</b>的前缀（messages[:i]），不是清洗后的结果。</p>
 */
public final class MessageSanitizer {

    private MessageSanitizer() {
    }

    /** 返回清洗后的消息列表（可能比输入短）。 */
    public static List<ChatMessage> sanitizeMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String role = msg.getRole() == null ? "" : msg.getRole();

            // 丢弃空的非 system 消息（部分 provider 会拒绝）
            boolean hasToolCalls = msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
            if ((msg.getContent() == null || msg.getContent().isEmpty())
                    && !"system".equals(role) && !"tool".equals(role) && !hasToolCalls) {
                continue;
            }

            // 防止连续同角色消息（tool 结果除外）
            if (!result.isEmpty() && !"tool".equals(role)) {
                ChatMessage prev = result.get(result.size() - 1);
                if (prev.getRole().equals(role) && !"tool".equals(prev.getRole())) {
                    // 与前一条合并。⚠️ 必须落成<b>新对象</b>：Go 的 []chat.Message 持
                    // 结构体值，result 里的合并写不回调用方的切片；Java 列表持有共享
                    // 引用，就地 setContent 会把合并泄漏进调用方的消息列表（多轮场景
                    // 下同一条用户消息被反复追加，引擎实录抓回）。
                    ChatMessage merged = shallowCopy(prev);
                    merged.setContent(prev.getContent() + "\n\n"
                            + (msg.getContent() == null ? "" : msg.getContent()));
                    result.set(result.size() - 1, merged);
                    continue;
                }
            }

            // 校验 tool 结果消息引用的 tool call 是否存在
            String toolCallId = msg.getToolCallId() == null ? "" : msg.getToolCallId();
            if ("tool".equals(role) && !toolCallId.isEmpty()) {
                if (!hasMatchingToolCall(messages.subList(0, i), toolCallId)) {
                    // 保留可恢复的数据，但不把外部输出升格为策略。改写同样落成新对象
                    // （Go 的 range 循环变量是结构体副本，原始切片不受影响）。
                    msg = shallowCopy(msg);
                    msg.setRole("user");
                    msg.setContent("<untrusted_tool_result name=\"" + goHtmlEscape(orEmpty(msg.getName()))
                            + "\">\n" + goHtmlEscape(orEmpty(msg.getContent())) + "\n</untrusted_tool_result>");
                    msg.setToolCallId("");
                    msg.setName("");
                }
            }

            result.add(msg);
        }

        return result;
    }

    /** Go 结构体值拷贝的对应物：字段逐个复制（列表字段保持同一引用，语义同 Go 切片头拷贝）。 */
    private static ChatMessage shallowCopy(ChatMessage m) {
        ChatMessage c = new ChatMessage(m.getRole(), m.getContent());
        c.setMultiContent(m.getMultiContent());
        c.setName(m.getName());
        c.setToolCallId(m.getToolCallId());
        c.setToolCalls(m.getToolCalls());
        c.setImages(m.getImages());
        c.setReasoningContent(m.getReasoningContent());
        c.setKind(m.getKind());
        return c;
    }

    /** 前面的 assistant 消息里是否有 ID 匹配的 tool call。 */
    private static boolean hasMatchingToolCall(List<ChatMessage> messages, String toolCallId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    if (toolCallId.equals(tc.getId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Go html.EscapeString：转义 < > & ' "（' → &#39;、" → &#34;）。 */
    static String goHtmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '\'' -> sb.append("&#39;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&#34;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
