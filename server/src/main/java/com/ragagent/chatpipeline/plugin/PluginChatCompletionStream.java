package com.ragagent.chatpipeline.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ragagent.chatpipeline.ChatManage;
import com.ragagent.chatpipeline.PipelineCommon;
import com.ragagent.chatpipeline.PipelineEventType;
import com.ragagent.chatpipeline.PipelineLog;
import com.ragagent.chatpipeline.PipelinePorts;
import com.ragagent.chatpipeline.support.ReferencesSupport;
import com.ragagent.event.Event;
import com.ragagent.event.EventBusInterface;
import com.ragagent.event.EventType;
import com.ragagent.event.payload.AgentFinalAnswerData;
import com.ragagent.event.payload.AgentThoughtData;
import com.ragagent.event.payload.ErrorData;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.modelcontext.StreamDecoder;

/**
 * CHAT_COMPLETION_STREAM 阶段插件（对照 Go chat_pipeline/chat_completion_stream.go）：
 * 流式生成——组消息 → 建流 → 消费流 chunk 并直接 emit 事件。
 *
 * <h2>chunk → 事件路由（实录组 stream ×6 钉住）</h2>
 * <ul>
 *   <li>ERROR chunk → EventError（stage=chat_completion_stream）后继续消费；</li>
 *   <li>THINKING chunk → 经独立 StreamDecoder 馈给 EventAgentThought（同一 thinkingID）；
 *       done 时 flush 后 closeThinking（open=false 才不发 done 事件）；</li>
 *   <li>ANSWER chunk → 经独立 StreamDecoder 馈给 EventAgentFinalAnswer（同一 answerID）；
 *       done=true 后后续 ANSWER 重复完成被丢弃（complete 之后不得再出答案）。</li>
 * </ul>
 *
 * <p>流终止约定（4.6b 标准）：Java BlockingQueue 无 channel 关闭——消费在
 * 「done=true 且非 THINKING」的元素处理完处收束（4.0 生产者的终态元素恒
 * ANSWER/ERROR+done；THINKING+done 是生产者中途补的 thinking-done 标记，其后仍有
 * 分片，照 Go 继续消费）。收束路径 flushDecoders + closeThinking（悬挂句柄尾巴不能丢）。</p>
 */
public final class PluginChatCompletionStream implements Plugin {

    private final PipelinePorts.ModelService modelService;

    public PluginChatCompletionStream(PipelinePorts.ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public String[] activationEvents() {
        return new String[] {PipelineEventType.CHAT_COMPLETION_STREAM};
    }

    @Override
    public PluginError onEvent(String eventType, ChatManage chatManage, Plugin.Chain next) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("session_id", chatManage.getSessionId());
        in.put("user_question", chatManage.getUserContent());
        in.put("history_rounds", chatManage.getHistory() == null ? 0 : chatManage.getHistory().size());
        in.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Stream", "input", in);

        PipelineCommon.PreparedChatModel prepared;
        try {
            prepared = PipelineCommon.prepareChatModel(modelService, chatManage);
        } catch (RuntimeException e) {
            return PluginError.GET_CHAT_MODEL.withError(e);
        }
        LlmChatClient chatModel = prepared.chatModel();
        ChatOptions opt = prepared.options();

        var assembly = ReferencesSupport.prepareMessagesWithModelContext(chatManage);
        var chatMessages = assembly.registry().encodeMessages(assembly.messages());
        Map<String, Object> mr = new LinkedHashMap<>();
        mr.put("message_count", chatMessages.size());
        mr.put("system_prompt", chatMessages.get(0).getContent());
        PipelineLog.info("Stream", "messages_ready", mr);
        Map<String, Object> um = new LinkedHashMap<>();
        um.put("content", chatMessages.get(chatMessages.size() - 1).getContent());
        PipelineLog.info("Stream", "user_message", um);

        // 事件流必需 EventBus
        if (chatManage.getEventBus() == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("session_id", chatManage.getSessionId());
            PipelineLog.error("Stream", "eventbus_missing", f);
            return PluginError.MODEL_CALL.withError(new RuntimeException("EventBus is required for streaming"));
        }
        EventBusInterface eventBus = chatManage.getEventBus();

        Map<String, Object> er = new LinkedHashMap<>();
        er.put("session_id", chatManage.getSessionId());
        PipelineLog.info("Stream", "eventbus_ready", er);

        // 建流（建立失败立即抛错）
        BlockingQueue<StreamResponse> responseQueue;
        Map<String, Object> mc = new LinkedHashMap<>();
        mc.put("chat_model", chatManage.getChatModelId());
        PipelineLog.info("Stream", "model_call", mc);
        try {
            responseQueue = chatModel.chatStream(chatMessages, opt);
        } catch (RuntimeException e) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chat_model", chatManage.getChatModelId());
            f.put("error", e.getMessage());
            PipelineLog.error("Stream", "model_call", f);
            return PluginError.MODEL_CALL.withError(e);
        }
        if (responseQueue == null) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("chat_model", chatManage.getChatModelId());
            f.put("error", "nil_channel");
            PipelineLog.error("Stream", "model_call", f);
            return PluginError.MODEL_CALL.withError(new RuntimeException("chat stream returned nil channel"));
        }

        Map<String, Object> ms = new LinkedHashMap<>();
        ms.put("session_id", chatManage.getSessionId());
        PipelineLog.info("Stream", "model_started", ms);

        // 消费线程（对照 goroutine；虚拟线程）
        final ChatManage cm = chatManage;
        final BlockingQueue<StreamResponse> queue = responseQueue;
        final com.ragagent.modelcontext.Registry modelContext = assembly.registry();
        Thread.ofVirtual().start(() -> consumeStream(cm, eventBus, modelContext, queue));

        return next.next();
    }

    /** 流消费循环（对照 OnEvent 的 goroutine 体，逐行对应）。 */
    private static void consumeStream(ChatManage chatManage, EventBusInterface eventBus,
                                      com.ragagent.modelcontext.Registry modelContext,
                                      BlockingQueue<StreamResponse> responseQueue) {
        StreamDecoder answerDecoder = modelContext.streamDecoder();
        StreamDecoder thinkingDecoder = modelContext.streamDecoder();
        String thinkingID = String.format("%s-thinking", UUID.randomUUID().toString().substring(0, 8));
        String answerID = String.format("%s-answer", UUID.randomUUID().toString().substring(0, 8));
        AtomicBoolean thinkingOpen = new AtomicBoolean(false);
        AtomicBoolean answerCompleted = new AtomicBoolean(false);

        while (true) {
            StreamResponse response = takeQuietly(responseQueue);
            if (response == null) {
                // 对照 channel close / ctx.Done 收束：flush + closeThinking
                flushDecoders(eventBus, chatManage, thinkingID, answerID, thinkingDecoder, answerDecoder);
                closeThinking(eventBus, chatManage, thinkingID, thinkingOpen);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("session_id", chatManage.getSessionId());
                PipelineLog.info("Stream", "channel_close", f);
                return;
            }

            if (ResponseType.ERROR == response.getResponseType()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("session_id", chatManage.getSessionId());
                f.put("error", response.getContent());
                PipelineLog.error("Stream", "stream_error", f);
                Event errEvt = new Event();
                errEvt.setId(String.format("%s-error", UUID.randomUUID().toString().substring(0, 8)));
                errEvt.setType(EventType.EVENT_ERROR);
                errEvt.setSessionId(chatManage.getSessionId());
                ErrorData errData = new ErrorData();
                errData.setError(response.getContent());
                errData.setStage("chat_completion_stream");
                errData.setSessionId(chatManage.getSessionId());
                errEvt.setData(errData);
                try {
                    eventBus.emit(errEvt);
                } catch (RuntimeException ignored) {
                    // 对照 `_ = eventBus.Emit(...)`
                }
                if (response.isDone()) {
                    // ERROR+done 是终态元素：对照 channel close 收束
                    flushDecoders(eventBus, chatManage, thinkingID, answerID, thinkingDecoder, answerDecoder);
                    closeThinking(eventBus, chatManage, thinkingID, thinkingOpen);
                    return;
                }
                continue;
            }

            if (ResponseType.THINKING == response.getResponseType()) {
                String content = thinkingDecoder.feed(response.getContent());
                if (response.isDone()) {
                    content += thinkingDecoder.flush();
                }
                if (!content.isEmpty()) {
                    thinkingOpen.set(true);
                    Event evt = new Event();
                    evt.setId(thinkingID);
                    evt.setType(EventType.EVENT_AGENT_THOUGHT);
                    evt.setSessionId(chatManage.getSessionId());
                    AgentThoughtData data = new AgentThoughtData();
                    data.setContent(content);
                    data.setDone(false);
                    evt.setData(data);
                    try {
                        eventBus.emit(evt);
                    } catch (RuntimeException ignored) {
                    }
                }
                if (response.isDone()) {
                    closeThinking(eventBus, chatManage, thinkingID, thinkingOpen);
                }
                // THINKING+done 是生产者中途补的 thinking-done 标记，其后仍有分片，照 Go 继续
                continue;
            }

            if (ResponseType.ANSWER == response.getResponseType()) {
                // 重复的完成分片丢弃（终态之后不得再出答案）
                if (answerCompleted.get()) {
                    continue;
                }
                String content = answerDecoder.feed(response.getContent());
                if (response.isDone()) {
                    content += answerDecoder.flush();
                    answerCompleted.set(true);
                }
                closeThinking(eventBus, chatManage, thinkingID, thinkingOpen);
                Event evt = new Event();
                evt.setId(answerID);
                evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
                evt.setSessionId(chatManage.getSessionId());
                AgentFinalAnswerData data = new AgentFinalAnswerData();
                data.setContent(content);
                data.setDone(response.isDone());
                evt.setData(data);
                try {
                    eventBus.emit(evt);
                } catch (RuntimeException ignored) {
                }
                if (response.isDone()) {
                    // ANSWER+done 是终态元素：对照 channel close（flush 尾巴）
                    flushDecoders(eventBus, chatManage, thinkingID, answerID, thinkingDecoder, answerDecoder);
                    closeThinking(eventBus, chatManage, thinkingID, thinkingOpen);
                    return;
                }
            }
        }
    }

    /** 阻塞取队列元素（channel 读的等价物；2 分钟无新 chunk 视为收束）。 */
    private static StreamResponse takeQuietly(BlockingQueue<StreamResponse> queue) {
        try {
            return queue.poll(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void closeThinking(EventBusInterface eventBus, ChatManage chatManage,
                                      String thinkingID, AtomicBoolean thinkingOpen) {
        if (!thinkingOpen.compareAndSet(true, false)) {
            return;
        }
        Event evt = new Event();
        evt.setId(thinkingID);
        evt.setType(EventType.EVENT_AGENT_THOUGHT);
        evt.setSessionId(chatManage.getSessionId());
        AgentThoughtData data = new AgentThoughtData();
        data.setDone(true);
        evt.setData(data);
        try {
            eventBus.emit(evt);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 对照 flushDecoders：流解码器扣留的句柄尾巴在此放出（引用跨分片桥接）；
     * 正常收束与取消路径都必须调用。
     */
    private static void flushDecoders(EventBusInterface eventBus, ChatManage chatManage,
                                      String thinkingID, String answerID,
                                      StreamDecoder thinkingDecoder, StreamDecoder answerDecoder) {
        String thinkingTail = thinkingDecoder.flush();
        if (!thinkingTail.isEmpty()) {
            Event evt = new Event();
            evt.setId(thinkingID);
            evt.setType(EventType.EVENT_AGENT_THOUGHT);
            evt.setSessionId(chatManage.getSessionId());
            AgentThoughtData data = new AgentThoughtData();
            data.setContent(thinkingTail);
            evt.setData(data);
            try {
                eventBus.emit(evt);
            } catch (RuntimeException ignored) {
            }
        }
        String answerTail = answerDecoder.flush();
        if (!answerTail.isEmpty()) {
            Event evt = new Event();
            evt.setId(answerID);
            evt.setType(EventType.EVENT_AGENT_FINAL_ANSWER);
            evt.setSessionId(chatManage.getSessionId());
            AgentFinalAnswerData data = new AgentFinalAnswerData();
            data.setContent(answerTail);
            evt.setData(data);
            try {
                eventBus.emit(evt);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
