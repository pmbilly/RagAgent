package com.ragagent.agent.tools;

/**
 * 流式 {@code <think>} 分离器（对照 Go {@code think_stream.go} 的 ThinkStreamSplitter，逐字移植）。
 *
 * <p>{@link ThinkBlocks} 处理的是完整字符串；本类按 chunk 增量地把内联思维链与正文分流——
 * 思考部分实时进 "thought" UI 区，正文进 "最终回答" 区，不等整个响应结束。跨 chunk 的标签
 * 边界（一个 chunk 尾部是 {@code <thi}、下个 chunk 开头是 {@code nk>}）靠缓冲"可能仍是标签
 * 前缀的尾部字节"解决；流结束时调 {@link #flush()} 排空缓冲。</p>
 *
 * <p><b>非并发安全</b>：每条流新建一个（与 Go 一致）。Go 实录语料见
 * {@code ThinkStreamSplitterTest}。</p>
 */
public final class ThinkStreamSplitter {

    private static final String THINK_OPEN_TAG = "<think>";
    private static final String THINK_CLOSE_TAG = "</think>";

    private boolean inThink;
    /** 上一次 Feed 留下的、可能构成（可能被拆开的）think 标签前缀的尾部字节。 */
    private String pending = "";

    public ThinkStreamSplitter() {
    }

    /** Feed 一个内容 chunk，返回此刻已无歧义的 (think, answer) 文本；两者都可为空。 */
    public FeedResult feed(String s) {
        if (s == null || s.isEmpty()) {
            return new FeedResult("", "");
        }
        pending += s;

        StringBuilder think = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        while (true) {
            if (inThink) {
                int idx = pending.indexOf(THINK_CLOSE_TAG);
                if (idx >= 0) {
                    think.append(pending, 0, idx);
                    pending = pending.substring(idx + THINK_CLOSE_TAG.length());
                    inThink = false;
                    continue;
                }
                HoldBack safe = holdBackPartialTag(pending, THINK_CLOSE_TAG);
                think.append(safe.safe());
                pending = safe.hold();
                return new FeedResult(think.toString(), answer.toString());
            }

            int idx = pending.indexOf(THINK_OPEN_TAG);
            if (idx >= 0) {
                answer.append(pending, 0, idx);
                pending = pending.substring(idx + THINK_OPEN_TAG.length());
                inThink = true;
                continue;
            }
            HoldBack safe = holdBackPartialTag(pending, THINK_OPEN_TAG);
            answer.append(safe.safe());
            pending = safe.hold();
            return new FeedResult(think.toString(), answer.toString());
        }
    }

    /** 流结束时排空缓冲。未闭合的 {@code <think>} 块视为 thinking 文本；其余为 answer 文本。 */
    public FeedResult flush() {
        String rest = pending;
        pending = "";
        if (rest.isEmpty()) {
            return new FeedResult("", "");
        }
        if (inThink) {
            return new FeedResult(rest, "");
        }
        return new FeedResult("", rest);
    }

    /** feed/flush 的返回（thinkOut, answerOut）。 */
    public record FeedResult(String think, String answer) {
    }

    private record HoldBack(String safe, String hold) {
    }

    /**
     * 把 s 拆成"现在就能发射的安全部分"和"tag 的真前缀尾部"（下个 chunk 可能把补全成真标签）。
     * s 结尾没有这种前缀时，整体安全、hold 为空。
     */
    private static HoldBack holdBackPartialTag(String s, String tag) {
        int maxK = tag.length() - 1;
        if (maxK > s.length()) {
            maxK = s.length();
        }
        for (int k = maxK; k >= 1; k--) {
            if (s.endsWith(tag.substring(0, k))) {
                return new HoldBack(s.substring(0, s.length() - k), s.substring(s.length() - k));
            }
        }
        return new HoldBack(s, "");
    }
}
