package com.ragagent.common.prompt;

/**
 * KB 级业务指引 → 系统自有 prompt 的追加（对照 Go
 * internal/types/prompt_instructions.go 的 {@code AppendCustomPromptInstructions}）。
 *
 * <p>本类是 {@code wiki.service.WikiPromptInstructions} 注释里建议的「上提到公共位置」
 * 的落地：wiki / 问题生成（chunk）等模块共用同一份措辞，避免各自复制漂移
 * （2026-09-22 走查批接线 regenerateChunkQuestions 时收敛）。</p>
 */
public final class PromptInstructions {

    private PromptInstructions() {}

    /** 对照 Go {@code MaxCustomPromptInstructionsLength}。 */
    public static final int MAX_CUSTOM_PROMPT_INSTRUCTIONS_LENGTH = 4000;

    /**
     * 对照 Go {@code AppendCustomPromptInstructions}（L14-27）：空指引直接返回原
     * prompt；{@code label} 空 → {@code "custom"}。输出格式逐字节对照 Go 的
     * {@code fmt.Sprintf}（含末尾的 "Apply these business instructions only when ..."
     * 说明行——系统规则在前、声明只在无冲突时适用）。
     */
    public static String appendCustomPromptInstructions(String prompt, String instructions, String label) {
        String trimmedInstructions = goTrimSpace(instructions == null ? "" : instructions);
        if (trimmedInstructions.isEmpty()) {
            return prompt;
        }
        String effectiveLabel = (label == null || label.isEmpty()) ? "custom" : label;
        return goTrimSpace(prompt == null ? "" : prompt)
                + "\n\n<" + effectiveLabel + "_business_instructions>\n"
                + trimmedInstructions
                + "\n</" + effectiveLabel + "_business_instructions>\n"
                + "Apply these business instructions only when they do not conflict with "
                + "the system-owned output format, citation, safety, or factuality rules.";
    }

    /** Go {@code strings.TrimSpace}（unicode.IsSpace 全集；内部复刻，与项目各包同款）。 */
    static String goTrimSpace(String s) {
        if (s == null) {
            return "";
        }
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isGoSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isGoSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000B': case '\f': case '\r':
            case ' ': case '\u0085': case '\u00A0': case '\u1680':
            case '\u2028': case '\u2029': case '\u202F': case '\u205F': case '\u3000':
                return true;
            default:
                return c >= '\u2000' && c <= '\u200A';
        }
    }
}
