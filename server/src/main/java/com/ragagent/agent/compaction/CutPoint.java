package com.ragagent.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.agent.TokenEstimator;
import com.ragagent.llm.domain.ChatMessage;

/**
 * 切点选择（对照 Go internal/agent/compaction/cutpoint.go 全文）。
 *
 * <p>切点只按 token 预算选——绝不按"保住当前轮完整"。ReAct 一轮能跑二十个 round、
 * 产出十万 token 的工具流量；拒绝碰当前轮的压缩器无事可压，每轮烧一次 LLM 调用
 * 却什么也得不到。</p>
 */
public final class CutPoint {

    /** 原样保留的第一条消息下标（对照 FirstKeptIdx）。 */
    private final int firstKeptIdx;
    /**
     * 切点所落轮次的 user 消息下标（对照 TurnStartIdx）；切在轮次边界上时为 -1。
     */
    private final int turnStartIdx;
    /** 切点把单个轮次切成了两半（对照 IsSplitTurn）。 */
    private final boolean splitTurn;

    private CutPoint(int firstKeptIdx, int turnStartIdx, boolean splitTurn) {
        this.firstKeptIdx = firstKeptIdx;
        this.turnStartIdx = turnStartIdx;
        this.splitTurn = splitTurn;
    }

    public int getFirstKeptIdx() {
        return firstKeptIdx;
    }

    public int getTurnStartIdx() {
        return turnStartIdx;
    }

    public boolean isSplitTurn() {
        return splitTurn;
    }

    static CutPoint of(int firstKeptIdx, int turnStartIdx) {
        return new CutPoint(firstKeptIdx, turnStartIdx, turnStartIdx >= 0);
    }

    /**
     * 是否可以在该消息之前立即切割（对照 isCutPointMessage）。除 tool 结果外都可以。
     * 排除 tool 结果是因为它必须跟请求它的 assistant 消息待在一起——把两者切开留下
     * 孤儿 tool result，供应商直接拒绝请求。推论是轮内压缩得以安全：落在 assistant
     * 消息上的切点会连它带它后面的所有 tool 结果一起保留，配对从两侧都不会断。
     */
    static boolean isCutPointMessage(ChatMessage msg) {
        return !"tool".equals(msg.getRole());
    }

    /**
     * 是否开启一个轮次（对照 isTurnStartMessage）。压缩摘要也算：它们替身了之前的一切，
     * 其开始的轮次自洽完整。
     */
    static boolean isTurnStartMessage(ChatMessage msg) {
        return "user".equals(msg.getRole());
    }

    /**
     * 压缩可以触碰的第一个下标（对照 historyStart）。系统提示词永不是候选——
     * 它承载 agent 的指令，不是历史。
     */
    static int historyStart(List<ChatMessage> messages) {
        if (messages != null && !messages.isEmpty() && "system".equals(messages.get(0).getRole())) {
            return 1;
        }
        return 0;
    }

    static CutPoint noCut(int start) {
        return new CutPoint(start, -1, false);
    }

    /**
     * 从最新消息向前累计估算大小，保留仍装得进 keepRecentTokens 的最大后缀
     * （对照 FindCutPoint）。
     *
     * <p>预算是<b>天花板不是地板</b>。看到第一条达到预算的消息就停下并连它一起保留，
     * 这个读法很自然但错了：单个 knowledge_search 结果随随便便就上万 token，
     * "保留最近 16k"会交回 25k，离下一次压缩只剩一轮的余量。</p>
     *
     * <p>这里刻意不看当前轮。预算是唯一的规则；大到自身就超预算的轮次会被切开，
     * 而不是被豁免。</p>
     */
    public static CutPoint findCutPoint(
            List<ChatMessage> messages, int start, int keepRecentTokens, TokenEstimator estimator) {
        if (start < 0) {
            start = 0;
        }
        List<Integer> cutPoints = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            if (isCutPointMessage(messages.get(i))) {
                cutPoints.add(i);
            }
        }
        if (cutPoints.isEmpty()) {
            return noCut(start);
        }

        // 最坏情况只保留最新一组。单条消息大于整个预算时在此无能为力——修剪它是
        // 工具结果修剪器的活，不是切点的活。
        int cutIdx = cutPoints.get(cutPoints.size() - 1);
        int accumulated = 0;
        for (int i = messages.size() - 1; i >= start; i--) {
            accumulated += estimator.estimateMessage(messages.get(i));
            if (accumulated > keepRecentTokens) {
                break;
            }
            // 仍在预算内，所以这是更好的（更早的）切点。只记录合法切点，
            // 保证每个 tool result 都跟着请求它的 assistant 消息。
            if (isCutPointMessage(messages.get(i))) {
                cutIdx = i;
            }
        }

        if (isTurnStartMessage(messages.get(cutIdx))) {
            return new CutPoint(cutIdx, -1, false);
        }
        int turnStart = findTurnStartIdx(messages, cutIdx, start);
        return new CutPoint(cutIdx, turnStart, turnStart >= 0);
    }

    /**
     * 找到 entryIdx 所在轮次的 user 消息下标（对照 findTurnStartIdx）；
     * 轮次在可搜索范围之前开始则 -1。
     */
    private static int findTurnStartIdx(List<ChatMessage> messages, int entryIdx, int start) {
        for (int i = entryIdx; i >= start; i--) {
            if (isTurnStartMessage(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
