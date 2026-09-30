package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.FunctionDef;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.domain.ToolCall;

/**
 * 出站组装协作者（对照 openai_request.go 段，自 {@link RemoteApiChat} 机械搬出）：
 * 消息转换与标准 OpenAI 请求体组装。持门面回引用 adapter/modelName/provider——
 * adapter 是可变字段（测试 setAdapter 替换），必须每处经 {@code service.adapter()} 取当前值。
 */
final class RemoteApiRequestOps {

    private final RemoteApiChat service;

    RemoteApiRequestOps(RemoteApiChat service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // 出站组装（对照 openai_request.go）
    // ------------------------------------------------------------------

    /**
     * 对照 Go ConvertMessages：把消息转成 OpenAI 格式。
     *
     * <p>保真要点：</p>
     * <ol>
     *   <li>MultiContent 优先于 Images 优先于纯文本（三分支互斥）；</li>
     *   <li>Images 只在 <b>user</b> 角色下展开为 image_url part（detail 固定 auto），
     *       并把原 content 作为尾部 text part；</li>
     *   <li>role == "tool" 才带 tool_call_id / name；</li>
     *   <li>reasoning_content <b>只对 assistant 回传</b>——MiMo / DeepSeek V3.2+ thinking 模式
     *       要求多轮里上一轮 assistant 的 reasoning_content 原样带回，否则 400
     *       （不认该字段的厂商会安全忽略，见 issue #1302）。</li>
     * </ol>
     */
    public List<ChatMessage> convertMessages(List<ChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            ChatMessage m = new ChatMessage(msg.getRole(), "");
            List<MessageContentPart> multi = msg.getMultiContent();
            if (multi != null && !multi.isEmpty()) {
                List<MessageContentPart> parts = new ArrayList<>(multi.size());
                for (MessageContentPart part : multi) {
                    String type = part.getType() == null ? "" : part.getType();
                    switch (type) {
                        case MessageContentPart.TYPE_TEXT ->
                                parts.add(MessageContentPart.text(part.getText()));
                        case MessageContentPart.TYPE_IMAGE_URL -> {
                            if (part.getImageUrl() != null) {
                                parts.add(MessageContentPart.image(
                                        part.getImageUrl().getUrl(), part.getImageUrl().getDetail()));
                            }
                        }
                        default -> {
                            // 对照 Go：未知 part 类型静默丢弃
                        }
                    }
                }
                m.setMultiContent(parts);
            } else if (msg.getImages() != null && !msg.getImages().isEmpty()
                    && "user".equals(msg.getRole())) {
                List<MessageContentPart> parts = new ArrayList<>(msg.getImages().size() + 1);
                for (String imageUrl : msg.getImages()) {
                    parts.add(MessageContentPart.image(
                            ImageResolver.resolveImageUrlForLlm(imageUrl), "auto"));
                }
                parts.add(MessageContentPart.text(msg.getContent()));
                m.setMultiContent(parts);
            } else if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                m.setContent(msg.getContent());
            }

            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                m.setToolCalls(new ArrayList<>(msg.getToolCalls()));
            }
            if ("tool".equals(msg.getRole())) {
                m.setToolCallId(msg.getToolCallId());
                m.setName(msg.getName());
            }
            if ("assistant".equals(msg.getRole())
                    && msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
                m.setReasoningContent(msg.getReasoningContent());
            }
            converted.add(m);
        }
        return converted;
    }

    /**
     * 对照 Go BuildChatCompletionRequest：标准聊天请求参数（导出供子类使用）。
     *
     * <p>采样参数按 opts 直接映射（注意 go-openai 的 struct 带 omitempty：0 值字段不上线，
     * 故这里只在非零时写入）；完成预算经 {@link CompletionBudget} 收成一个值，再按供应商
     * 只写入 max_tokens 或 max_completion_tokens 之一（二者互斥，见 #3014）。
     * 其余供应商特判（o-series / GPT-5 采样参数、Moonshot 固定温度等）仍由
     * {@link ProviderAdapter#shapeRequest} 在事后施加，见 {@link ProviderAdapters}。</p>
     */
    public ObjectNode buildChatCompletionRequest(List<ChatMessage> messages, ChatOptions opts, boolean isStream) {
        return buildBodyFromConverted(convertMessages(messages), opts, isStream);
    }

    /**
     * 对照 Go shapedRequest：标准请求 + 适配器的消息变换与参数整形
     * （不含 thinking——它可能包一层 body）。
     */
    public ObjectNode shapedRequest(List<ChatMessage> messages, ChatOptions opts, boolean isStream) {
        List<ChatMessage> converted = convertMessages(messages);
        converted = service.adapter().transformMessages(converted);
        ObjectNode body = buildBodyFromConverted(converted, opts, isStream);
        service.adapter().shapeRequest(body, opts, isStream);
        return body;
    }

    /** 已转消息 → 请求体（对照 BuildChatCompletionRequest 的主体）。 */
    private ObjectNode buildBodyFromConverted(List<ChatMessage> converted, ChatOptions opts, boolean isStream) {
        ObjectNode body = RemoteApiChat.MAPPER.createObjectNode();
        body.put("model", service.modelName);
        ArrayNode messagesNode = body.putArray("messages");
        for (ChatMessage m : converted) {
            messagesNode.add(messageToJson(m));
        }
        if (isStream) {
            body.put("stream", true);
            // 对照 Go：isStream 时 StreamOptions{IncludeUsage: true} → 末片带 usage
            body.putObject("stream_options").put("include_usage", true);
        }
        if (opts == null) {
            return body;
        }

        if (opts.getTemperature() != 0) {
            body.put("temperature", opts.getTemperature());
        }
        if (opts.getTopP() > 0) {
            body.put("top_p", opts.getTopP());
        }
        if (opts.getFrequencyPenalty() > 0) {
            body.put("frequency_penalty", opts.getFrequencyPenalty());
        }
        if (opts.getPresencePenalty() > 0) {
            body.put("presence_penalty", opts.getPresencePenalty());
        }

        // 恰好一个 token 字段（Go 同位置：applyCompletionBudget）
        CompletionBudget.wireField(service.provider, service.modelName).apply(body, opts.completionBudget());

        if (opts.getTools() != null && !opts.getTools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ChatTool tool : opts.getTools()) {
                FunctionDef fn = tool.getFunction() == null ? new FunctionDef() : tool.getFunction();
                ObjectNode toolNode = tools.addObject();
                // ⚠️ 键序对照 Go 的 map 序列化（字母序）：function < type；
                // description < name < parameters（2026-09-23 A/B 逐字节对拍修正）。
                ObjectNode fnNode = toolNode.putObject("function");
                if (fn.getDescription() != null && !fn.getDescription().isEmpty()) {
                    fnNode.put("description", fn.getDescription());
                }
                fnNode.put("name", fn.getName());
                // 对照 go-openai FunctionDefinition：parameters 无 omitempty → nil 时输出 null
                fnNode.set("parameters", fn.getParameters() == null ? NullNode.getInstance() : fn.getParameters());
                toolNode.put("type", tool.getType());
            }
        }

        if (opts.getParallelToolCalls() != null) {
            body.put("parallel_tool_calls", opts.getParallelToolCalls());
        }

        String toolChoice = opts.getToolChoice();
        if (toolChoice != null && !toolChoice.isEmpty()) {
            switch (toolChoice) {
                case "none", "required", "auto" -> body.put("tool_choice", toolChoice);
                default -> {
                    ObjectNode choice = body.putObject("tool_choice");
                    choice.put("type", "function");
                    choice.putObject("function").put("name", toolChoice);
                }
            }
        }

        JsonNode format = opts.getFormat();
        if (format != null && !format.isNull()) {
            body.putObject("response_format").put("type", "json_object");
            // 对照 Go：把 schema 拼到最后一条 message 的 content 尾部
            JsonNode messagesNodeRaw = body.get("messages");
            if (messagesNodeRaw instanceof ArrayNode arr && !arr.isEmpty()) {
                JsonNode last = arr.get(arr.size() - 1);
                if (last instanceof ObjectNode lastMsg) {
                    applyJsonSchemaHint(lastMsg, format);
                }
            }
        }
        return body;
    }

    /**
     * 对照 Go {@code req.Messages[len-1].Content += fmt.Sprintf("\nUse this JSON schema: %s", opts.Format)}。
     * Go 在 content 已是 multi-content 数组时会 marshal 失败（Content 与 MultiContent 同时非空）；
     * Java 侧退化为追加一个 text part，比整请求失败更稳。
     */
    private static void applyJsonSchemaHint(ObjectNode lastMessage, JsonNode format) {
        String hint = "\nUse this JSON schema: " + format;
        JsonNode content = lastMessage.get("content");
        if (content == null || content.isNull()) {
            lastMessage.put("content", hint);
            return;
        }
        if (content.isTextual()) {
            lastMessage.put("content", content.asText() + hint);
            return;
        }
        if (content instanceof ArrayNode arr) {
            ObjectNode part = arr.addObject();
            part.put("type", MessageContentPart.TYPE_TEXT);
            part.put("text", hint);
        }
    }

    /**
     * 消息 → OpenAI 线上 JSON（对照 go-openai {@code ChatCompletionMessage.MarshalJSON}）。
     *
     * <p>注意与域对象序列化的差别：multi_content 在线上是 <b>content 数组</b>，
     * 且 content 为空串时整个键省略（go-openai 的 omitempty），tool_calls 的厂商特有状态
     * 由 {@link ProviderAdapter#injectToolCallMetadata} 就地注入（对照 Go 的
     * buildProviderOpenAIRequest——Java 单路径下总是注入，等价于 Go 里 ForceRawHTTP 的行为）。</p>
     */
    private ObjectNode messageToJson(ChatMessage msg) {
        ObjectNode node = RemoteApiChat.MAPPER.createObjectNode();
        node.put("role", msg.getRole());

        List<MessageContentPart> multi = msg.getMultiContent();
        if (multi != null && !multi.isEmpty()) {
            ArrayNode parts = node.putArray("content");
            for (MessageContentPart part : multi) {
                ObjectNode p = parts.addObject();
                if (part.getType() != null && !part.getType().isEmpty()) {
                    p.put("type", part.getType());
                }
                if (part.getText() != null && !part.getText().isEmpty()) {
                    p.put("text", part.getText());
                }
                if (part.getImageUrl() != null) {
                    ObjectNode img = p.putObject("image_url");
                    String url = part.getImageUrl().getUrl();
                    if (url != null && !url.isEmpty()) {
                        img.put("url", url);
                    }
                    String detail = part.getImageUrl().getDetail();
                    if (detail != null && !detail.isEmpty()) {
                        img.put("detail", detail);
                    }
                }
            }
        } else if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            node.put("content", msg.getContent());
        }

        if (msg.getName() != null && !msg.getName().isEmpty()) {
            node.put("name", msg.getName());
        }
        if (msg.getReasoningContent() != null && !msg.getReasoningContent().isEmpty()) {
            node.put("reasoning_content", msg.getReasoningContent());
        }

        List<ToolCall> toolCalls = msg.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall tc : toolCalls) {
                ObjectNode call = calls.addObject();
                if (tc.getId() != null && !tc.getId().isEmpty()) {
                    call.put("id", tc.getId());
                }
                // go-openai ToolCall.Type 无 omitempty（空串也上线）
                call.put("type", tc.getType() == null ? "" : tc.getType());
                ObjectNode fn = call.putObject("function");
                FunctionCall fc = tc.getFunction();
                if (fc != null) {
                    if (fc.getName() != null && !fc.getName().isEmpty()) {
                        fn.put("name", fc.getName());
                    }
                    if (fc.getArguments() != null && !fc.getArguments().isEmpty()) {
                        fn.put("arguments", fc.getArguments());
                    }
                }
                service.adapter().injectToolCallMetadata(call, tc.getProviderMetadata());
            }
        }

        if (msg.getToolCallId() != null && !msg.getToolCallId().isEmpty()) {
            node.put("tool_call_id", msg.getToolCallId());
        }
        return node;
    }
}
