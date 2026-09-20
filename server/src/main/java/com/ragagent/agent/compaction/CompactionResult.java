package com.ragagent.agent.compaction;

import java.util.List;

import com.ragagent.llm.domain.ChatMessage;

/**
 * 一次压缩做了什么（对照 Go compaction.Result，compactor.go L46-68）。
 */
public final class CompactionResult {

    private final List<ChatMessage> messages;
    private final String summary;
    private final String reason;
    private final int tokensBefore;
    private final int tokensAfter;
    private final int messagesBefore;
    private final int messagesAfter;
    /** 至少一段摘要来自原始档案（摘要器失败）。上下文还是缩了，只是记忆更粗。 */
    private final boolean degraded;
    /** 切点切开了单个轮次：轮次前缀摘要与历史摘要并存。 */
    private final boolean splitTurn;

    CompactionResult(List<ChatMessage> messages, String summary, String reason,
            int tokensBefore, int tokensAfter, int messagesBefore, int messagesAfter,
            boolean degraded, boolean splitTurn) {
        this.messages = messages;
        this.summary = summary;
        this.reason = reason;
        this.tokensBefore = tokensBefore;
        this.tokensAfter = tokensAfter;
        this.messagesBefore = messagesBefore;
        this.messagesAfter = messagesAfter;
        this.degraded = degraded;
        this.splitTurn = splitTurn;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public String getSummary() {
        return summary;
    }

    public String getReason() {
        return reason;
    }

    public int getTokensBefore() {
        return tokensBefore;
    }

    public int getTokensAfter() {
        return tokensAfter;
    }

    public int getMessagesBefore() {
        return messagesBefore;
    }

    public int getMessagesAfter() {
        return messagesAfter;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public boolean isSplitTurn() {
        return splitTurn;
    }

    /** 实际腾出的空间；非正值说明摘要的代价与它替换的历史一样大（对照 Freed）。 */
    public int freed() {
        return tokensBefore - tokensAfter;
    }
}
