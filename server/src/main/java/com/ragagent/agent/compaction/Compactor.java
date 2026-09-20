package com.ragagent.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.CacheRetention;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;

/**
 * 压缩器：把会话历史收进一份检查点摘要（对照 Go internal/agent/compaction/compactor.go
 * 全文）。
 *
 * <p>摘要调用走 {@link LlmChatClient}。Go 侧每次摘要 attempt 带 60s 超时
 * （summarizationTimeout）与 LLM 调用元数据（llmCallLabel="agent_context_compaction"）；
 * Java 侧超时由调用方传入的客户端/传输层控制（对照约定 §9 阶段 4.0 的取数），
 * purpose 元数据随 llm 模块已登记的差异不单独携带。</p>
 */
public final class Compactor {

    /**
     * 单次压缩的重试上限（对照 maxSummarizationAttempts）。每个 attempt 都是一次完整
     * 的 LLM 往返；一再失败的压缩，原始档案比卡住整轮更好用。
     */
    private static final int MAX_SUMMARIZATION_ATTEMPTS = 2;

    /** 在追踪与用量记账里标识压缩流量的标签（对照 llmCallLabel）。 */
    public static final String LLM_CALL_LABEL = "agent_context_compaction";

    private final LlmChatClient chatModel;
    private final TokenEstimator estimator;
    private final CompactionSettings settings;

    private Compactor(LlmChatClient chatModel, TokenEstimator estimator, CompactionSettings settings) {
        this.chatModel = chatModel;
        this.estimator = estimator;
        this.settings = settings;
    }

    /**
     * 构建压缩器（对照 New）。无法运行时返回 <b>null</b>——调用方把 null 压缩器当作
     * "功能关闭"，不需要第二个开关标志。
     */
    public static Compactor create(LlmChatClient chatModel, TokenEstimator estimator,
            CompactionSettings settings) {
        if (chatModel == null || estimator == null || settings.maxContextTokens() <= 0) {
            return null;
        }
        return new Compactor(chatModel, estimator, settings.normalize());
    }

    /** 归一化后的设置，含派生阈值（对照 Settings()；nil 接收者返回零值）。 */
    public CompactionSettings settings() {
        return settings;
    }

    /**
     * 用摘要替换 keep-recent 预算之外的历史（对照 Compact）。
     *
     * @throws NothingToCompactException 没有那样的历史时——停止信号，不是重试理由
     */
    public CompactionResult compact(List<ChatMessage> messages, String reason) {
        CompactionPreparation prep = CompactionPreparation.prepare(messages, settings, estimator);
        if (prep == null) {
            throw new NothingToCompactException();
        }

        SummaryWithFlag built = buildSummary(prep);
        String summary = built.text() + prep.fileOps().format();
        List<ChatMessage> compacted = CompactionPreparation.apply(messages, prep, summary);

        return new CompactionResult(
                compacted,
                summary,
                reason,
                prep.getTokensBefore(),
                estimator.estimateMessages(compacted),
                messages.size(),
                compacted.size(),
                built.degraded(),
                prep.isSplitTurn());
    }

    /**
     * 产出检查点文本，摘要器产不出的部分回退到原始档案（对照 buildSummary）。
     * 返回 [text, degraded]。
     */
    private SummaryWithFlag buildSummary(CompactionPreparation p) {
        boolean degraded = false;

        String history = p.getPreviousSummary();
        if (!p.getMessagesToSummarize().isEmpty()) {
            String instructions = CompactionPrompts.INITIAL_SUMMARIZATION_INSTRUCTIONS;
            if (!p.getPreviousSummary().isEmpty()) {
                instructions = CompactionPrompts.UPDATE_SUMMARIZATION_INSTRUCTIONS;
            }
            String text;
            try {
                text = summarize(p.getMessagesToSummarize(), p.getPreviousSummary(),
                        instructions, settings.summaryBudget());
            } catch (Exception e) {
                degraded = true;
                // 上一次摘要仍是这一段之前一切的最佳记录，所以档案<b>追加</b>在它
                // 后面而不是替换它。
                history = joinNonEmpty(p.getPreviousSummary(),
                        ConversationSerializer.rawArchive(p.getMessagesToSummarize()));
                text = null;
            }
            if (text != null) {
                history = text;
            }
        }

        if (p.isSplitTurn() && !p.getTurnPrefixMessages().isEmpty()) {
            String prefix;
            try {
                prefix = summarize(p.getTurnPrefixMessages(), "",
                        CompactionPrompts.TURN_PREFIX_INSTRUCTIONS, settings.turnPrefixBudget());
            } catch (Exception e) {
                degraded = true;
                prefix = ConversationSerializer.rawArchive(p.getTurnPrefixMessages());
            }
            if (history.isEmpty()) {
                history = "No prior history.";
            }
            history += CompactionPrompts.SPLIT_TURN_SEPARATOR + prefix;
        }

        return new SummaryWithFlag(history, degraded);
    }

    private record SummaryWithFlag(String text, boolean degraded) {
    }

    /** 跑一次带重试的摘要调用（对照 summarize）。 */
    private String summarize(List<ChatMessage> messages, String previousSummary,
            String instructions, int maxTokens) {
        String prompt = buildSummarizationPrompt(messages, previousSummary, instructions);
        RuntimeException lastErr = null;

        for (int attempt = 1; attempt <= MAX_SUMMARIZATION_ATTEMPTS; attempt++) {
            List<ChatMessage> callMessages = new ArrayList<>();
            callMessages.add(new ChatMessage("system", CompactionPrompts.SUMMARIZATION_SYSTEM_PROMPT));
            callMessages.add(new ChatMessage("user", prompt));
            ChatOptions opts = new ChatOptions();
            opts.setTemperature(0.3); // 事实性摘要用低温度
            opts.setMaxTokens(maxTokens);
            opts.setCacheRetention(CacheRetention.NONE);

            ChatResponse resp;
            try {
                resp = chatModel.chat(callMessages, opts);
            } catch (RuntimeException e) {
                lastErr = e;
                continue;
            }
            String err = validateSummary(resp);
            if (err != null) {
                lastErr = new IllegalStateException(err);
                continue;
            }
            return ConversationSerializer.goTrimSpace(ConversationSerializer.nvl(resp.getContent()));
        }

        throw new IllegalStateException(
                ("summarization failed after %d attempts: %s".formatted(MAX_SUMMARIZATION_ATTEMPTS,
                        lastErr == null ? "<nil>" : lastErr.getMessage())));
    }

    /**
     * 拒绝不能充当检查点的响应（对照 validateSummary）；null = 通过。
     *
     * <p>length 停止值得单说：被 token 上限切断的摘要读起来像份有效摘要，实际上
     * 在小节中间无声结束，之后每一轮都把这份残篇当作被丢历史唯一的记忆。
     * 半份摘要就是失败：它当不了检查点。</p>
     *
     * @return null 或 Go 版错误文本
     */
    static String validateSummary(ChatResponse resp) {
        String content = resp == null || resp.getContent() == null ? "" : resp.getContent();
        if (content.trim().isEmpty()) {
            return "empty response from LLM";
        }
        String reason = resp.getFinishReason() == null ? "" : resp.getFinishReason().trim().toLowerCase();
        switch (reason) {
            case "length", "max_tokens", "max_output_tokens" -> {
                return "generation hit the token cap and the summary is incomplete";
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 把文字记录包进 tag、指令放最后（对照 buildSummarizationPrompt）——
     * 摘要器就不会把对话文本误当成自己的指令。
     */
    static String buildSummarizationPrompt(List<ChatMessage> messages, String previousSummary,
            String instructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("<conversation>\n");
        sb.append(ConversationSerializer.serializeConversation(messages));
        sb.append("\n</conversation>\n\n");
        if (previousSummary != null && !previousSummary.isEmpty()) {
            sb.append("<previous-summary>\n");
            sb.append(previousSummary);
            sb.append("\n</previous-summary>\n\n");
        }
        sb.append(instructions);
        return sb.toString();
    }

    private static String joinNonEmpty(String... parts) {
        List<String> kept = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                kept.add(p);
            }
        }
        return String.join("\n\n", kept);
    }
}
