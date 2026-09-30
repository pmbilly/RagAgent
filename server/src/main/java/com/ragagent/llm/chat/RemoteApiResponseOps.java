package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.error.BizException;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ToolCall;

/**
 * 响应解析协作者（对照 Go openai_stream.go 前半的非流式解析，自 {@link RemoteApiChat}
 * 机械搬出）：choices[0] 解析、<think> 标签剥离与厂商工具调用元数据回填。
 * 持门面回引用 adapter（可变，测试可替换）与 provider，共享 static {@code textOrEmpty}
 * 经门面类名访问。
 */
final class RemoteApiResponseOps {

    private final RemoteApiChat service;

    RemoteApiResponseOps(RemoteApiChat service) {
        this.service = service;
    }

    // ------------------------------------------------------------------
    // 非流式响应解析（对照 openai_stream.go 前半）
    // ------------------------------------------------------------------

    /** 对照 Go parseCompletionResponse：取 choices[0]，剥 thinking 标签，带出 tool_calls 与 usage。 */
    ChatResponse parseCompletionResponse(JsonNode resp) {
        JsonNode choices = resp == null ? null : resp.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw BizException.internal("no response from API");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        ChatResponse response = new ChatResponse();
        response.setContent(removeThinkingContent(RemoteApiChat.textOrEmpty(message.get("content"))));
        response.setFinishReason(RemoteApiChat.textOrEmpty(choice.get("finish_reason")));
        response.setUsage(PromptCache.tokenUsageFromOpenAI(resp.get("usage"), service.provider));

        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<ToolCall> out = new ArrayList<>(toolCalls.size());
            for (JsonNode tc : toolCalls) {
                ToolCall call = new ToolCall();
                call.setId(RemoteApiChat.textOrEmpty(tc.get("id")));
                call.setType(RemoteApiChat.textOrEmpty(tc.get("type")));
                JsonNode fn = tc.path("function");
                call.getFunction().setName(RemoteApiChat.textOrEmpty(fn.get("name")));
                call.getFunction().setArguments(RemoteApiChat.textOrEmpty(fn.get("arguments")));
                out.add(call);
            }
            response.setToolCalls(out);
        }
        return response;
    }

    /**
     * 对照 Go applyCompletionToolCallMetadata：用**原始响应体**里的 tool_call 对象抽取
     * 厂商特有状态（Gemini 的 extra_content），按 index 回填。
     */
    void applyCompletionToolCallMetadata(JsonNode body, ChatResponse result) {
        if (result == null || result.getToolCalls() == null || result.getToolCalls().isEmpty()) {
            return;
        }
        JsonNode choices = body == null ? null : body.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode toolCalls = choices.get(0).path("message").get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        int fallbackIndex = 0;
        for (JsonNode rawToolCall : toolCalls) {
            int idx = rawToolCall.hasNonNull("index") ? rawToolCall.get("index").asInt() : fallbackIndex;
            if (idx >= 0 && idx < result.getToolCalls().size()) {
                result.getToolCalls().get(idx)
                        .setProviderMetadata(service.adapter().extractToolCallMetadata(rawToolCall));
            }
            fallbackIndex++;
        }
    }

    /**
     * 对照 Go removeThinkingContent：移除思考模型输出里的 {@code <think>...</think>}。
     * 仅当内容以 {@code <think>} 开头才处理；取**最后一个** {@code </think>}（容忍嵌套）；
     * 找不到闭标签（思考被截断）返回空串。
     */
    static String removeThinkingContent(String content) {
        final String thinkStartTag = "<think>";
        final String thinkEndTag = "</think>";
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith(thinkStartTag)) {
            return content;
        }
        int lastEndIdx = trimmed.lastIndexOf(thinkEndTag);
        if (lastEndIdx != -1) {
            String result = trimmed.substring(lastEndIdx + thinkEndTag.length()).trim();
            return result.isEmpty() ? "" : result;
        }
        return "";
    }

}
