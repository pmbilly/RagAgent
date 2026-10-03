package com.ragagent.llm;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.StreamResponse;

/**
 * 聊天客户端。
 *
 * 流式语义：{@link #chatStream} 是**先同步建立连接、返回通道**，
 * 建立阶段的错误立即抛出；进入流之后的错误通过流内的 ERROR 型 StreamResponse 传递
 * （FinishReason 记为 "incomplete"）。
 *
 * Java 侧用 {@link BlockingQueue} 承载（内部跑虚拟线程），与既有
 * KnowledgeProcessWorker 的"asyncio → 虚拟线程队列"取舍同类。
 */
public interface LlmChatClient {

    /** 非流式聊天 */
    ChatResponse chat(List<ChatMessage> messages, ChatOptions options);

    /**
     * 流式聊天：建立连接后返回响应队列；消费到 done=true 的元素即流结束。
     * 队列元素由生产者补齐，消费者不必关心关闭，流以 done 标记收尾。
     */
    BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options);

    /** 模型名称 */
    String getModelName();

    /** 模型 ID */
    String getModelId();
}
