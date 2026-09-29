package com.ragagent.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.domain.ChatMessage;

/**
 * 压缩准备与重建。
 *
 * <p>摘要以 {@code user} 消息注入而非第二条 {@code system}：系统消息是指令，
 * 这是会话历史——会合并或特殊加权 system 消息的供应商不是它的去处。</p>
 */
public final class CompactionPreparation {

    static final String SUMMARY_PREFIX = "The conversation history before this point was compacted "
            + "into the following summary:\n\n<summary>\n";
    static final String SUMMARY_SUFFIX = "\n</summary>";

    private final int firstKeptIdx;
    private final List<ChatMessage> messagesToSummarize;
    private final List<ChatMessage> turnPrefixMessages;
    private final boolean splitTurn;
    private final String previousSummary;
    private final int tokensBefore;
    private final FileOps fileOps;

    private CompactionPreparation(int firstKeptIdx, List<ChatMessage> messagesToSummarize,
            List<ChatMessage> turnPrefixMessages, boolean splitTurn, String previousSummary,
            int tokensBefore, FileOps fileOps) {
        this.firstKeptIdx = firstKeptIdx;
        this.messagesToSummarize = messagesToSummarize;
        this.turnPrefixMessages = turnPrefixMessages;
        this.splitTurn = splitTurn;
        this.previousSummary = previousSummary;
        this.tokensBefore = tokensBefore;
        this.fileOps = fileOps;
    }

    /** 原样尾部开始的下标。 */
    public int getFirstKeptIdx() {
        return firstKeptIdx;
    }

    /** 被散文替换掉的完整轮次。 */
    public List<ChatMessage> getMessagesToSummarize() {
        return messagesToSummarize;
    }

    /** 被切开轮次被丢弃的头部。 */
    public List<ChatMessage> getTurnPrefixMessages() {
        return turnPrefixMessages;
    }

    public boolean isSplitTurn() {
        return splitTurn;
    }

    /** 上一次压缩的文本，原地更新而非再次摘要。 */
    public String getPreviousSummary() {
        return previousSummary;
    }

    public int getTokensBefore() {
        return tokensBefore;
    }

    FileOps fileOps() {
        return fileOps;
    }

    /**
     * 选切点并收集切口两侧的消息区间。
     *
     * <p>无法帮上忙时返回 null——没有消息落在 keep-recent 预算之外，或上一次压缩
     * 已经没剩什么可删。这个 null 挡住"每一轮都对压不动的上下文烧一次摘要调用"
     * 的失败模式。</p>
     */
    public static CompactionPreparation prepare(
            List<ChatMessage> messages, CompactionSettings s, TokenEstimator estimator) {
        if (estimator == null || messages == null || messages.isEmpty()) {
            return null;
        }
        CompactionSettings settings = s.normalize();

        // 摘要从上一次压缩的边界之后开始。摘要消息本身被排除：把它喂回去就是把
        // 连续压缩变成"摘要的摘要的摘要"的原因。
        int boundaryStart = CutPoint.historyStart(messages);
        String previousSummary = "";
        for (int i = messages.size() - 1; i >= boundaryStart; i--) {
            if (!messages.get(i).isCompactionSummary()) {
                continue;
            }
            previousSummary = unwrapSummary(messages.get(i).getContent());
            boundaryStart = i + 1;
            break;
        }
        if (boundaryStart >= messages.size()) {
            return null;
        }

        CutPoint cut = CutPoint.findCutPoint(messages, boundaryStart, settings.keepRecentTokens(), estimator);

        int historyEnd = cut.getFirstKeptIdx();
        if (cut.isSplitTurn()) {
            historyEnd = cut.getTurnStartIdx();
        }
        List<ChatMessage> toSummarize = cloneRange(messages, boundaryStart, historyEnd);

        List<ChatMessage> turnPrefix = null;
        if (cut.isSplitTurn()) {
            turnPrefix = cloneRange(messages, cut.getTurnStartIdx(), cut.getFirstKeptIdx());
        }
        List<ChatMessage> safeToSummarize = toSummarize == null ? new ArrayList<>() : toSummarize;
        List<ChatMessage> safeTurnPrefix = turnPrefix == null ? new ArrayList<>() : turnPrefix;

        if (safeToSummarize.isEmpty() && safeTurnPrefix.isEmpty()) {
            return null;
        }

        return new CompactionPreparation(
                cut.getFirstKeptIdx(), safeToSummarize, safeTurnPrefix, cut.isSplitTurn(),
                previousSummary, estimator.estimateMessages(messages),
                FileOps.extractFileOps(previousSummary,
                        List.of(safeToSummarize, safeTurnPrefix)));
    }

    /**
     * 以系统提示词、摘要、原样尾部重建消息列表。
     * 系统提示词与切点之间的任何内容都不保留。
     */
    public static List<ChatMessage> apply(List<ChatMessage> messages, CompactionPreparation p, String summary) {
        int tailStart = Math.max(Math.min(p.firstKeptIdx, messages.size()), 0);
        List<ChatMessage> out = new ArrayList<>(2 + messages.size() - tailStart);
        if (CutPoint.historyStart(messages) == 1) {
            out.add(messages.get(0));
        }
        out.add(summaryMessage(summary));
        out.addAll(messages.subList(tailStart, messages.size()));
        return out;
    }

    /**
     * 把摘要文本包进模型看到的信封，打上标记让下一次压缩
     * 认出这是自己的产物。
     */
    public static ChatMessage summaryMessage(String summary) {
        ChatMessage m = new ChatMessage();
        m.setRole("user");
        m.setKind(ChatMessage.KIND_COMPACTION_SUMMARY);
        m.setContent(SUMMARY_PREFIX + summary + SUMMARY_SUFFIX);
        return m;
    }

    /**
     * 从摘要消息里取回原文，去掉信封后交给 update 提示词。
     */
    static String unwrapSummary(String content) {
        String c = content == null ? "" : content;
        int start = c.indexOf("<summary>");
        if (start < 0) {
            return ConversationSerializer.goTrimSpace(c);
        }
        start += "<summary>".length();
        int end = c.lastIndexOf("</summary>");
        if (end < start) {
            return ConversationSerializer.goTrimSpace(c.substring(start));
        }
        return ConversationSerializer.goTrimSpace(c.substring(start, end));
    }

    private static List<ChatMessage> cloneRange(List<ChatMessage> messages, int start, int end) {
        if (start < 0) {
            start = 0;
        }
        if (end > messages.size()) {
            end = messages.size();
        }
        if (start >= end) {
            return null;
        }
        return new ArrayList<>(messages.subList(start, end));
    }
}
