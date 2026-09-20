package com.ragagent.agent.tools;

import java.util.regex.Pattern;

/**
 * 剥离 {@code <think>…</think>} 块（对照 Go {@code strip_think.go} 的 StripThinkBlocks）。
 *
 * <p>DeepSeek、Qwen 等模型会把思维链直接嵌在 content 字段的 think 标签里。在展示给用户、
 * 存入 agent 状态/上下文管理器、经 EventBus 发射之前都应剥掉。</p>
 *
 * <p>Go 实录：未闭合的 {@code <think>} 不剥（无闭合标签就没有匹配）；前导的孤立
 * {@code </think>} 原样保留；剥完裁掉两侧的 ' ' \n \r \t。</p>
 */
public final class ThinkBlocks {

    /** 对照 thinkBlockRe = (?s)<think>.*?</think>（(?s) 让 . 匹配换行）。 */
    private static final Pattern THINK_BLOCK_RE =
            Pattern.compile("(?s)<think>.*?</think>");

    private ThinkBlocks() {
    }

    /** 剥掉 think 块；输入为空或剥完为空则返回空串。 */
    public static String stripThinkBlocks(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String cleaned = THINK_BLOCK_RE.matcher(content).replaceAll("");
        // 剥完后裁掉残留的首尾空白
        return trimWhitespace(cleaned);
    }

    /** 只裁 ' ' \n \r \t（对照 Go 的 trimWhitespace，不引入 strings 包的同款）。 */
    private static String trimWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isTrimmable(s.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmable(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isTrimmable(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
