package com.ragagent.session.service;



import java.util.ArrayList;




import java.util.List;



import org.slf4j.Logger;

import org.slf4j.LoggerFactory;






import com.ragagent.chatpipeline.ChatManage;















import com.ragagent.event.EventBusInterface;








import com.ragagent.llm.LlmChatClient;

import com.ragagent.llm.domain.ChatMessage;

import com.ragagent.llm.domain.ChatOptions;


import com.ragagent.llm.domain.StreamResponse;



import com.ragagent.modelcontext.Registry;






/**

 * 知识问答降级协作者:固定文案兜底与模型兜底的消息准备与响应。

 *

 * <p>持有 {@link SessionKnowledgeQaService} 回引以访问其依赖与共享 helpers;本类不得独立实例化。</p>

 */

final class SessionQaFallback {



    private static final Logger log = LoggerFactory.getLogger(SessionQaFallback.class);




    private final SessionKnowledgeQaService service;



    SessionQaFallback(SessionKnowledgeQaService service) {

        this.service = service;

    }



    void handleFallbackResponse(ChatManage chatManage) {

        if ("model".equals(chatManage.getFallbackStrategy())) {

            handleModelFallback(chatManage);

        } else {

            handleFixedFallback(chatManage);

        }

    }



    void handleFixedFallback(ChatManage chatManage) {

        String fallbackContent = chatManage.getFallbackResponse();

        com.ragagent.llm.domain.ChatResponse response = new com.ragagent.llm.domain.ChatResponse();

        response.setContent(fallbackContent);

        chatManage.setChatResponse(response);

        service.emitFallbackAnswer(chatManage, fallbackContent);

    }



    void handleModelFallback(ChatManage chatManage) {

        if (chatManage.getFallbackPrompt().isEmpty()) {

            log.warn("Fallback strategy is 'model' but FallbackPrompt is empty, falling back to fixed response");

            handleFixedFallback(chatManage);

            return;

        }

        String promptContent = service.renderFallbackPrompt(chatManage);



        EventBusInterface eventBus = chatManage.getEventBus();

        if (eventBus == null) {

            log.warn("EventBus not available for streaming fallback, falling back to fixed response");

            handleFixedFallback(chatManage);

            return;

        }



        LlmChatClient chatModel;

        try {

            chatModel = service.pipelineModelService.getChatModel(chatManage.getChatModelId());

        } catch (RuntimeException e) {

            log.error("Failed to get chat model for fallback: {}, falling back to fixed response", e.toString());

            handleFixedFallback(chatManage);

            return;

        }



        ChatOptions opt = new ChatOptions();

        opt.setTemperature(chatManage.getSummaryConfig().getTemperature());

        opt.setMaxCompletionTokens(chatManage.getSummaryConfig().getMaxCompletionTokens());

        opt.setThinking(Boolean.FALSE);



        var prepared = prepareFallbackMessages(chatManage, promptContent);

        java.util.concurrent.BlockingQueue<StreamResponse> responseQueue;

        try {

            responseQueue = chatModel.chatStream(prepared.messages(), opt);

        } catch (RuntimeException e) {

            log.error("Failed to start streaming fallback response: {}, falling back to fixed response", e.toString());

            handleFixedFallback(chatManage);

            return;

        }

        if (responseQueue == null) {

            log.error("Chat stream returned nil channel, falling back to fixed response");

            handleFixedFallback(chatManage);

            return;

        }

        Thread.ofVirtual().start(() -> service.consumeFallbackStream(chatManage, responseQueue, prepared.registry()));

    }



    record FallbackPrepared(List<ChatMessage> messages, Registry registry) {}



    /** prepareFallbackMessages + buildFallbackMessages（Go L1004-1052）。 */

    FallbackPrepared prepareFallbackMessages(ChatManage chatManage, String promptContent) {

        List<ChatMessage> messages = new ArrayList<>();

        if (!promptContent.trim().isEmpty()) {

            ChatMessage system = new ChatMessage();

            system.setRole("system");

            system.setContent(promptContent + "\n\n" + com.ragagent.agent.PromptInstructions.SOURCE_DATA_BOUNDARY_PROMPT

                    + "\n\n" + com.ragagent.agent.PromptInstructions.SOURCED_ANSWER_OUTPUT_PROMPT);

            messages.add(system);

        }

        com.ragagent.chatpipeline.PipelineCommon.appendHistoryMessages(messages, chatManage.getHistory());



        String query = chatManage.getQuery();

        String rq = chatManage.getRewriteQuery() == null ? "" : chatManage.getRewriteQuery().trim();

        if (!rq.isEmpty()) {

            query = rq;

        }

        ChatMessage userMsg = new ChatMessage();

        userMsg.setRole("user");

        userMsg.setContent(query);

        if (chatManage.isChatModelSupportsVision() && chatManage.getImages() != null) {

            userMsg.setImages(chatManage.getImages());

        }

        messages.add(userMsg);



        boolean citationsEnabled = chatManage.citationsEnabled();

        Registry registry = new Registry(citationsEnabled);

        if (!messages.isEmpty() && "system".equals(messages.get(0).getRole())) {

            ChatMessage first = messages.get(0);

            first.setContent(SessionKnowledgeQaService.trailTrim(first.getContent()) + registry.protocolPrompt());

        } else {

            ChatMessage system = new ChatMessage();

            system.setRole("system");

            system.setContent(registry.protocolPrompt().trim());

            messages.add(0, system);

        }

        return new FallbackPrepared(registry.encodeMessages(messages), registry);

    }

}
