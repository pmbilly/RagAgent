package com.ragagent.im.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IM 流式显示的 think 块处理。
 *
 * <p>字节契约：stripThinkBlocks / transformThinkBlocks / 组装族由测试 fixture
 * （contracts/w5g1-im-foundation.tsv）钉住。</p>
 */
public final class ThinkDisplay {

    private ThinkDisplay() {
    }

    /** intermediate：进行中的思考/工具（Web: 展开的进度）。 */
    public static final int STREAM_DISPLAY_INTERMEDIATE = 0;
    /** final：answer-only 文本（Web: 折叠/隐藏中间步）。 */
    public static final int STREAM_DISPLAY_FINAL = 1;

    private static final Pattern THINK_BLOCK_RE =
            Pattern.compile("(?s)<think>.*?</think>");
    private static final Pattern THINK_OPEN_TAIL_RE =
            Pattern.compile("(?s)<think>.*$");

    /** 镜像前端 RAG_PIPELINE_TOOL_NAMES。 */
    private static final java.util.Set<String> RAG_PIPELINE_TOOL_NAMES =
            java.util.Set.of("query_understand", "knowledge_search");

    /** 是否属于 quick-QA 进度 UI 的工具。 */
    public static boolean isRAGPipelineToolName(String name) {
        return RAG_PIPELINE_TOOL_NAMES.contains(name);
    }

    /** 为 IM 最终显示移除 &lt;think&gt; 块。 */
    public static String stripThinkBlocks(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String cleaned = THINK_BLOCK_RE.matcher(content).replaceAll("");
        cleaned = THINK_OPEN_TAIL_RE.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /** agent 推理 vs quick-QA RAG 管线显示。 */
    public static final int IM_STREAM_MODE_AGENT = 0;
    public static final int IM_STREAM_MODE_QUICK_QA = 1;

    /** IM 流内容分部。 */
    public static final class IMStreamParts {
        public int mode = IM_STREAM_MODE_AGENT;
        /** quick-QA：query_understand / knowledge_search 行。 */
        public List<ToolDisplay.IMToolStep> pipelineToolSteps = new ArrayList<>();
        /** quick-QA：模型 reasoning_content——独立"思考"节。 */
        public String reasoningInner = "";
        /** agent：收回的 preamble + 思考（"思考过程"）。 */
        public String agentInner = "";
        /** agent：工具进度行（每 tool_call_id 一行）。 */
        public List<ToolDisplay.IMToolStep> agentToolSteps = new ArrayList<>();
        /** agent：工具收回前的乐观答案流。 */
        public String liveAnswer = "";
        /** 最终答案（complete / knowledge QA）。 */
        public String answer = "";
    }

    private static String agentThinkContent(IMStreamParts parts) {
        String toolLines = ToolDisplay.renderIMToolSteps(parts.agentToolSteps,
                ToolDisplay::formatIMToolLine);
        return mergeIMNarrativeAndTools(parts.agentInner, toolLines);
    }

    private static String quickQAPipelineContent(IMStreamParts parts) {
        return ToolDisplay.renderIMToolSteps(parts.pipelineToolSteps,
                ToolDisplay::formatIMRagPipelineLine);
    }

    /** 对照 Web RagPipelineProgress 的思考行样式（agent.think）。 */
    public static final ThinkBlockStyle RAG_THINKING_STYLE = new ThinkBlockStyle(
            "> 💭 **思考中...**\n", "> 💭 **思考**\n", "> ", "", "\n---\n\n");

    /** inner 内容包上 think 标签。 */
    public static String wrapThinkBlock(String inner) {
        inner = inner == null ? "" : inner.strip();
        if (inner.isEmpty()) {
            return "";
        }
        return "<think>\n" + inner + "\n</think>";
    }

    /** 组装 agent 模式的原始内容。 */
    public static String buildIMAgentStreamRaw(IMStreamParts parts, boolean agentInProgress) {
        String thinkTagged = wrapThinkBlock(agentThinkContent(parts));
        if (agentInProgress || parts.answer.strip().isEmpty()) {
            return thinkTagged;
        }
        if (thinkTagged.isEmpty()) {
            return parts.answer;
        }
        return thinkTagged + "\n\n" + parts.answer;
    }

    /** 对照 Web AgentStreamDisplay：答案先行；工具运行时收回进"思考过程"。 */
    private static String formatIMAgentIntermediate(IMStreamParts parts) {
        String think = agentThinkContent(parts).strip();
        String live = parts.liveAnswer.strip();

        List<String> sections = new ArrayList<>();
        if (!think.isEmpty()) {
            sections.add(formatIMDisplayContent(wrapThinkBlock(think), STREAM_DISPLAY_INTERMEDIATE));
        }
        if (!live.isEmpty()) {
            sections.add(live);
        }
        return String.join(ThinkBlockStyle.MARKDOWN_SEPARATOR, sections);
    }

    /** 对照 Web RagPipelineProgress：管线步是普通行，推理在独立"思考"块。 */
    private static String formatIMQuickQAIntermediate(IMStreamParts parts) {
        String answer = parts.answer.strip();
        if (!answer.isEmpty()) {
            return answer;
        }
        List<String> sections = new ArrayList<>();
        String p = quickQAPipelineContent(parts).strip();
        if (!p.isEmpty()) {
            sections.add(p);
        }
        String r = parts.reasoningInner.strip();
        if (!r.isEmpty()) {
            sections.add(transformThinkBlocks(wrapThinkBlock(r), RAG_THINKING_STYLE));
        }
        return String.join("\n\n", sections);
    }

    /** 进行中的 IM 流显示。 */
    public static String formatIMIntermediateFromParts(IMStreamParts parts,
            boolean agentInProgress) {
        if (parts.mode == IM_STREAM_MODE_QUICK_QA) {
            return formatIMQuickQAIntermediate(parts);
        }
        return formatIMAgentIntermediate(parts);
    }

    /** 最终替换帧（所有模式都是 answer-only）。 */
    public static String formatIMFinalFromParts(IMStreamParts parts) {
        String answer = parts.answer.strip();
        if (!answer.isEmpty()) {
            return answer;
        }
        // 非 agent 答案里嵌着 think 标签时的兜底。
        return formatIMDisplayContent(buildIMAgentStreamRaw(parts, false), STREAM_DISPLAY_FINAL);
    }

    /** 按显示阶段格式化原始流内容。 */
    public static String formatIMDisplayContent(String raw, int phase) {
        if (phase == STREAM_DISPLAY_FINAL) {
            return stripThinkBlocks(raw);
        }
        return transformThinkBlocks(raw, ThinkBlockStyle.MARKDOWN);
    }

    /** think 块渲染样式。 */
    public static final class ThinkBlockStyle {
        /** think 块还在进行中（没等到闭合标签）时显示。 */
        public final String thinkingHeader;
        /** think 内容前显示。 */
        public final String thoughtHeader;
        /** think 内容每行前缀。 */
        public final String linePrefix;
        /** think 内容每行后缀（换行前）。 */
        public final String lineSuffix;
        /** think 块与余下内容之间的分隔。 */
        public final String separator;

        public ThinkBlockStyle(String thinkingHeader, String thoughtHeader,
                String linePrefix, String lineSuffix, String separator) {
            this.thinkingHeader = thinkingHeader;
            this.thoughtHeader = thoughtHeader;
            this.linePrefix = linePrefix;
            this.lineSuffix = lineSuffix;
            this.separator = separator;
        }

        /** Markdown blockquote（DingTalk 与 Feishu/Lark 用）。 */
        public static final ThinkBlockStyle MARKDOWN = new ThinkBlockStyle(
                "> 💭 **思考中...**\n", "> 💭 **思考过程**\n", "> ", "", "\n---\n\n");
        /** Telegram：同样的 blockquote 形态（流式中的残缺 markdown 会炸 API）。 */
        public static final ThinkBlockStyle TELEGRAM = new ThinkBlockStyle(
                "> 💭 *思考中...*\n", "> 💭 *思考过程*\n", "> ", "", "\n---\n\n");
        private static final String MARKDOWN_SEPARATOR = "\n---\n\n";
    }

    /**
     * 按给定样式转换 &lt;think&gt;...&lt;/think&gt; 块；闭合与未闭合（流式中）都处理。
     */
    public static String transformThinkBlocks(String content, ThinkBlockStyle style) {
        final String openTag = "<think>";
        final String closeTag = "</think>";

        int openIdx = content.indexOf(openTag);
        if (openIdx < 0) {
            return content;
        }

        String before = content.substring(0, openIdx);
        String after = content.substring(openIdx + openTag.length());

        int closeIdx = after.indexOf(closeTag);
        boolean thinkClosed = closeIdx >= 0;

        String thinkContent;
        String rest;
        if (thinkClosed) {
            thinkContent = after.substring(0, closeIdx);
            rest = after.substring(closeIdx + closeTag.length());
        } else {
            thinkContent = after;
            rest = "";
        }

        thinkContent = thinkContent.strip();

        StringBuilder result = new StringBuilder();
        result.append(before);

        if (thinkContent.isEmpty()) {
            if (!thinkClosed) {
                result.append(style.thinkingHeader);
                return result.toString();
            }
            result.append(trimLeftNewlines(rest));
            return result.toString();
        }

        result.append(style.thoughtHeader);
        for (String line : thinkContent.split("\n", -1)) {
            result.append(style.linePrefix);
            result.append(line);
            result.append(style.lineSuffix);
            result.append("\n");
        }

        if (thinkClosed) {
            rest = trimLeftNewlines(rest);
            if (!rest.isEmpty()) {
                result.append(style.separator);
                result.append(rest);
            }
        }

        return result.toString();
    }

    private static String trimLeftNewlines(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '\n') {
            i++;
        }
        return s.substring(i);
    }

    static String mergeIMNarrativeAndTools(String narrative, String toolLines) {
        narrative = narrative == null ? "" : narrative.strip();
        toolLines = toolLines == null ? "" : toolLines.strip();
        if (!narrative.isEmpty() && !toolLines.isEmpty()) {
            return narrative + "\n" + toolLines;
        }
        if (!toolLines.isEmpty()) {
            return toolLines;
        }
        return narrative;
    }
}
