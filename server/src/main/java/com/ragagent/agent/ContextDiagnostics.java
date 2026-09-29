package com.ragagent.agent;

import java.util.List;

import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;

/**
 * 上下文记账的诊断。
 *
 * <p>它要抓的失败天生无声：估算与供应商的账单不一致，正常日志里没有任何一行去
 * 比较它们。reasoning_content 就是这样漏记了足够久，把 130k 的上下文量成 26k——
 * 压缩不停触发、切错位置、什么也腾不出来，而每一行日志单看都合理。</p>
 *
 * <p>引擎侧由 {@link ContextDebugEmitter} 消费（读 compactor 与 lastUsage、
 * 写日志）；漂移判定的纯数学（ratio &gt; 1.5 或 &lt; 0.67 警告、
 * 漂移百分比 &lt; -25% / &gt; 50% 警告）留在调用方展开。</p>
 */
public final class ContextDiagnostics {

    /**
     * 与估算器的逐图常量刻意复制而非导出——
     * 它只是个上报细节。
     */
    private static final int ESTIMATED_IMAGE_TOKENS_FOR_LOG = 1200;

    private ContextDiagnostics() {
    }

    /**
     * 把上下文 token 归因到持有它的内容类型，
     * 意外总量可以追到来源而不是靠猜。
     */
    public static ContextBreakdown breakdownContext(
            List<ChatMessage> messages, List<ChatTool> tools, TokenEstimator est) {
        ContextBreakdown b = new ContextBreakdown();
        b.messages = messages == null ? 0 : messages.size();
        b.toolSchemas = est.estimateTools(tools);

        if (messages != null) {
            for (ChatMessage msg : messages) {
                int msgTokens = est.estimateMessage(msg);
                b.total += msgTokens;

                if (msgTokens > b.largestTokens) {
                    b.largestTokens = msgTokens;
                    b.largestKind = msg.getRole();
                    String name = msg.getName() == null ? "" : msg.getName();
                    if (!name.isEmpty()) {
                        b.largestKind += ":" + name;
                    }
                }

                int reasoning = est.estimateString(msg.getReasoningContent());
                if (reasoning > 0) {
                    b.reasoning += reasoning;
                }
                if (msg.getToolCalls() != null) {
                    for (var tc : msg.getToolCalls()) {
                        b.toolCallArgs += est.estimateString(tc.getFunction().getArguments());
                    }
                }
                if (msg.getMultiContent() != null) {
                    for (var part : msg.getMultiContent()) {
                        if (part.getImageUrl() != null || "image_url".equals(part.getType())) {
                            b.images += ESTIMATED_IMAGE_TOKENS_FOR_LOG;
                        }
                    }
                }
                if (msg.getMultiContent() == null || msg.getMultiContent().isEmpty()) {
                    b.images += (msg.getImages() == null ? 0 : msg.getImages().size())
                            * ESTIMATED_IMAGE_TOKENS_FOR_LOG;
                }

                int content = est.estimateString(msg.getContent());
                if (msg.isCompactionSummary()) {
                    b.summaries += content;
                } else if ("tool".equals(msg.getRole())) {
                    b.toolResults += content;
                } else {
                    b.text += content;
                }
            }
        }
        b.total += b.toolSchemas;
        return b;
    }

    /** 上下文的归因明细。 */
    public static final class ContextBreakdown {
        int messages;
        int total;
        int text;
        int reasoning;
        int toolResults;
        int toolCallArgs;
        int images;
        int summaries;
        int toolSchemas;
        /** 最大单条消息——通常就是值得动手的那个。 */
        String largestKind = "";
        int largestTokens;

        public int getMessages() { return messages; }
        public int getTotal() { return total; }
        public int getText() { return text; }
        public int getReasoning() { return reasoning; }
        public int getToolResults() { return toolResults; }
        public int getToolCallArgs() { return toolCallArgs; }
        public int getImages() { return images; }
        public int getSummaries() { return summaries; }
        public int getToolSchemas() { return toolSchemas; }
        public String getLargestKind() { return largestKind; }
        public int getLargestTokens() { return largestTokens; }

        /** 输出格式逐字节稳定（供日志比对/告警解析）。 */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("msgs=%d total=%d".formatted(messages, total));
            sb.append(" text=%d reasoning=%d tool_results=%d tool_args=%d"
                    .formatted(text, reasoning, toolResults, toolCallArgs));
            if (images > 0) {
                sb.append(" images=%d".formatted(images));
            }
            if (summaries > 0) {
                sb.append(" summary=%d".formatted(summaries));
            }
            sb.append(" tool_schemas=%d".formatted(toolSchemas));
            if (largestTokens > 0) {
                sb.append(" largest=%s(%d)".formatted(largestKind, largestTokens));
            }
            return sb.toString();
        }
    }
}
