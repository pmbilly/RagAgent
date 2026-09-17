package com.ragagent.wiki.service;

/**
 * 把 KB 级业务指引追加到系统自有 prompt 上（对照 Go
 * internal/types/prompt_instructions.go 的 {@code AppendCustomPromptInstructions}）。
 *
 * <p><b>为什么在 wiki 包</b>：这是 {@code types} 包里的通用函数（分块、图谱、
 * 问题生成都要用），但 Java 侧尚无对应的共享位置，而本任务的可写范围只有
 * wiki。放在这里先让 wiki ingest 跑起来；<b>建议后续把本类上提到
 * {@code com.ragagent.common} 或独立的 prompts 工具包，并把其它模块的调用点一起收敛过去</b>
 * ——否则四个模块会各自复制一份"业务指令包裹"的措辞，迟早漂移。</p>
 *
 * <p><b>语义</b>（照搬 Go 注释）：用户编写的业务指引被追加到系统自有的 prompt 之后。
 * 稳定的输出格式、引用、安全与事实性规则<b>永远优先</b>——追加文本里也写明了这一点，
 * 但真正的强制力来自"系统规则在前、且提示模型只在无冲突时适用"这个结构。</p>
 */
public final class WikiPromptInstructions {

    private WikiPromptInstructions() {}

    /** 对照 Go {@code MaxCustomPromptInstructionsLength} */
    public static final int MAX_CUSTOM_PROMPT_INSTRUCTIONS_LENGTH = 4000;

    /**
     * 对照 Go {@code AppendCustomPromptInstructions}（prompt_instructions.go L14-27）。
     *
     * <p>空指引直接返回原 prompt（不做任何包裹）；{@code label} 为空时用
     * {@code "custom"}。</p>
     *
     * <p>输出格式逐字节对照 Go 的 {@code fmt.Sprintf}：</p>
     * <pre>
     * {prompt trimmed}
     *
     * &lt;{label}_business_instructions&gt;
     * {instructions trimmed}
     * &lt;/{label}_business_instructions&gt;
     * Apply these business instructions only when they do not conflict with the system-owned output format, citation, safety, or factuality rules.
     * </pre>
     */
    public static String appendCustomPromptInstructions(String prompt, String instructions, String label) {
        String trimmedInstructions = GoStrings.trimSpace(instructions == null ? "" : instructions);
        if (trimmedInstructions.isEmpty()) {
            return prompt;
        }
        String effectiveLabel = (label == null || label.isEmpty()) ? "custom" : label;
        return GoStrings.trimSpace(prompt == null ? "" : prompt)
                + "\n\n<" + effectiveLabel + "_business_instructions>\n"
                + trimmedInstructions
                + "\n</" + effectiveLabel + "_business_instructions>\n"
                + "Apply these business instructions only when they do not conflict with "
                + "the system-owned output format, citation, safety, or factuality rules.";
    }
}
