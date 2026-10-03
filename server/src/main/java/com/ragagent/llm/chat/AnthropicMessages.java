package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.domain.ToolCall;

/**
 * 把本模块的消息/工具转成 Anthropic Messages 的请求形态。
 *
 * <p>两条关键纪律：</p>
 * <ol>
 *   <li><b>连续 tool 消息必须合并进同一条 user 消息的 tool_result blocks</b>——
 *       并行工具调用的多个结果属于紧随其后的那一条 user 消息；</li>
 *   <li><b>保留 ID、保留 JSON、保留空 tool 结果</b>（空串照发）。</li>
 * </ol>
 */
final class AnthropicMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicMessages() {
    }

    /** system 段与转换后的消息列表。 */
    record Converted(List<String> system, List<AnthropicMessage> messages) {
    }

    /**
     * 工具定义 + tool_choice 映射。
     *
     * <p>Anthropic 的 schema 字段叫 {@code input_schema}，且原样透传（不做 OpenAI 那样的
     * 整形）——`$defs`/`$ref`/`oneOf`/`additionalProperties` 全部保留。</p>
     *
     * <p>tool_choice："required" → {@code any}，"" / "auto" → {@code auto}，
     * "none" → {@code none}，其它当作具体工具名 → {@code tool} + name。
     * {@code disable_parallel_tool_use} 只在 choice 不是 none 且调用方显式给了
     * parallel_tool_calls 时才发——注意它是 <b>取反</b>的。</p>
     */
    static void toolOptions(AnthropicRequest req, ChatOptions opts) {
        if (opts == null || opts.getTools() == null || opts.getTools().isEmpty()) {
            return;
        }
        for (ChatTool tool : opts.getTools()) {
            var function = tool.getFunction();
            req.addTool(new AnthropicTool(
                    function == null ? "" : function.getName(),
                    function == null ? "" : function.getDescription(),
                    function == null ? null : function.getParameters()));
        }

        AnthropicToolChoice choice = new AnthropicToolChoice("auto");
        String toolChoice = opts.getToolChoice() == null ? "" : opts.getToolChoice();
        switch (toolChoice) {
            case "", "auto" -> {
                // 默认即 auto
            }
            case "required" -> choice.setType("any");
            case "none" -> choice.setType("none");
            default -> {
                choice.setType("tool");
                choice.setName(toolChoice);
            }
        }
        if (opts.getParallelToolCalls() != null && !"none".equals(choice.getType())) {
            choice.setDisableParallelToolUse(!opts.getParallelToolCalls());
        }
        req.setToolChoice(choice);
    }

    /**
     * 拆出 system 段，其余消息转成 Anthropic 形态。
     *
     * <p>角色映射：system → 顶层 system；assistant + tool_calls → assistant（text block +
     * tool_use blocks）；tool → user 的 tool_result block（连续 tool 合并）；其余 →
     * user/assistant 纯文本（空内容直接跳过）。</p>
     */
    static Converted messages(List<ChatMessage> messages) {
        List<String> system = new ArrayList<>();
        List<AnthropicMessage> result = new ArrayList<>();
        if (messages == null) {
            return new Converted(system, result);
        }
        for (ChatMessage msg : messages) {
            String content = trimSpace(msg.getContent());
            if (content.isEmpty()) {
                content = textFromMultiContent(msg.getMultiContent());
            }

            if ("system".equals(msg.getRole())) {
                if (!content.isEmpty()) {
                    system.add(content);
                }
                continue;
            }
            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                List<AnthropicContentBlock> blocks = new ArrayList<>();
                if (!content.isEmpty()) {
                    blocks.add(AnthropicContentBlock.text(content));
                }
                for (ToolCall call : msg.getToolCalls()) {
                    String name = call.getFunction() == null ? "" : call.getFunction().getName();
                    String arguments = call.getFunction() == null ? "" : call.getFunction().getArguments();
                    blocks.add(AnthropicContentBlock.toolUse(call.getId(), name, parseToolArguments(arguments)));
                }
                result.add(new AnthropicMessage("assistant", blocks));
                continue;
            }
            if ("tool".equals(msg.getRole())) {
                // 注意：tool_result 带的是**原始** content，不是上面 trim 过的那份
                AnthropicContentBlock block =
                        AnthropicContentBlock.toolResult(msg.getToolCallId(), msg.getContent());
                if (!result.isEmpty()) {
                    AnthropicMessage last = result.get(result.size() - 1);
                    if ("user".equals(last.getRole()) && last.getContent() instanceof List<?> raw
                            && !raw.isEmpty() && raw.get(0) instanceof AnthropicContentBlock first
                            && AnthropicContentBlock.TYPE_TOOL_RESULT.equals(first.getType())) {
                        @SuppressWarnings("unchecked")
                        List<AnthropicContentBlock> blocks = (List<AnthropicContentBlock>) raw;
                        blocks.add(block);
                        continue;
                    }
                }
                List<AnthropicContentBlock> blocks = new ArrayList<>();
                blocks.add(block);
                result.add(new AnthropicMessage("user", blocks));
                continue;
            }
            if (content.isEmpty()) {
                continue;
            }
            result.add(new AnthropicMessage("assistant".equals(msg.getRole()) ? "assistant" : "user", content));
        }
        return new Converted(system, result);
    }

    /**
     * 工具调用参数解析：空串补 {@code {}}；非空则按 JSON 解析，
     * 非法 JSON 在构造时就抛错。
     */
    private static JsonNode parseToolArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "marshal request: invalid JSON in tool call arguments: " + e.getMessage(), e);
        }
    }

    /**
     * 只取 type=text 且非空白的 part，各自 trim 后用
     * "\n" 拼接（注意不是空串拼接——这是与 OpenAI 路径不同的地方）。
     */
    static String textFromMultiContent(List<MessageContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        List<String> textParts = new ArrayList<>(parts.size());
        for (MessageContentPart part : parts) {
            if (part == null || !MessageContentPart.TYPE_TEXT.equals(part.getType())) {
                continue;
            }
            String text = trimSpace(part.getText());
            if (!text.isEmpty()) {
                textParts.add(text);
            }
        }
        return String.join("\n", textParts);
    }

    /** 去除首尾 Unicode 空白（不含   等不换行空白）。 */
    private static String trimSpace(String value) {
        return value == null ? "" : value.strip();
    }
}
