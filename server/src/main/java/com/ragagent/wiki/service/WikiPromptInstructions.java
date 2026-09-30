package com.ragagent.wiki.service;

/**
 * 把 KB 级业务指引追加到系统自有 prompt 上。
 *
 * <p>实现已上提至 {@link com.ragagent.common.prompt.PromptInstructions}
 * （chunk 问题生成需要共用），本类保留为 wiki 侧的历史入口，一行委托。</p>
 */
public final class WikiPromptInstructions {

    private WikiPromptInstructions() {}

    /** 业务指引的最大长度 */
    public static final int MAX_CUSTOM_PROMPT_INSTRUCTIONS_LENGTH =
            com.ragagent.common.prompt.PromptInstructions.MAX_CUSTOM_PROMPT_INSTRUCTIONS_LENGTH;

    /** 委托公共实现（见类注释）。 */
    public static String appendCustomPromptInstructions(String prompt, String instructions, String label) {
        return com.ragagent.common.prompt.PromptInstructions
                .appendCustomPromptInstructions(prompt, instructions, label);
    }
}
