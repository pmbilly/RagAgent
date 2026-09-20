package com.ragagent.chatpipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;

/**
 * CHAT_COMPLETION 阶段插件（对照 Go chat_pipeline/chat_completion.go）：
 * 非流式生成——组消息（历史 + 模型上下文句柄化）→ 调模型 → 解码响应 →
 * ChatResponse 挂回 ChatManage。
 *
 * <p>Go 的 withPromptCacheMetadata（PromptPrefixFingerprint + WithLLMCallMetadata）
 * 不翻（4.6b 备案③：Java 客户端无 ctx 形参、无消费点）。</p>
 */
public final class PluginChatCompletion implements Plugin {

    private final PipelinePorts.ModelService modelService;

    public PluginChatCompletion(PipelinePorts.ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHAT_COMPLETION};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("user_question", chatManage.getUserContent());
        in.put("history_rounds", chatManage.getHistory() == null ? 0 : chatManage.getHistory().size());
        in.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Completion", "input", in);

        // 模型与参数（失败 → GET_CHAT_MODEL）
        PipelineCommon.PreparedChatModel prepared;
        try {
            prepared = PipelineCommon.prepareChatModel(modelService, chatManage);
        } catch (RuntimeException e) {
            return PluginError.GET_CHAT_MODEL.withError(e);
        }
        ChatOptions opt = prepared.options();

        Map<String, Object> mr = new LinkedHashMap<>();
        mr.put("message_count",
                (chatManage.getHistory() == null ? 0 : chatManage.getHistory().size()) + 2);
        PipelineLog.info("Completion", "messages_ready", mr);

        LlmChatClient chatModel = prepared.chatModel();
        var assembly = ReferencesSupport.prepareMessagesWithModelContext(chatManage);
        var chatMessages = assembly.registry().encodeMessages(assembly.messages());

        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Completion", "model_call", mc);

        ChatResponse chatResponse;
        try {
            chatResponse = chatModel.chat(chatMessages, opt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chat_model", chatManage.getChatModelId());
            f.put("error", e.getMessage());
            PipelineLog.error("Completion", "model_call", f);
            return PluginError.MODEL_CALL.withError(e);
        }

        assembly.registry().decodeResponse(chatResponse);
        var orphans = assembly.registry().orphanResourceHandles(chatResponse.getContent());
        if (orphans != null && !orphans.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            f.put("handles", orphans);
            PipelineLog.warn("Completion", "orphan_resource_handles", f);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer_preview", chatResponse.getContent());
        out.put("finish_reason", chatResponse.getFinishReason());
        out.put("completion_tokens", chatResponse.getUsage() == null
                ? 0 : chatResponse.getUsage().getCompletionTokens());
        out.put("prompt_tokens", chatResponse.getUsage() == null
                ? 0 : chatResponse.getUsage().getPromptTokens());
        PipelineLog.info("Completion", "output", out);
        chatManage.setChatResponse(chatResponse);
        return next.next();
    }
}
