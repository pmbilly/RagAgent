package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.limiter.BackgroundTaskContext;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.LocalLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 并发装饰器的验收基准（对照 Go internal/models/chat/concurrency_wrapper_test.go 的 2 个测试）。
 *
 * 这两条是并发语义的硬契约：
 * 1. 交互式调用完全绕过 governor（即使 limit=1 也不排队）——用户延迟不排在信号量后面
 * 2. 消费者放弃流后，持有的槽位必须释放而不是泄漏
 */
class ConcurrencyChatClientTest {

    @AfterEach
    void resetGovernor() {
        BackgroundTaskContext.clear();
    }

    /** 流式持续产出直到被放弃的最小 fake（对照 Go fakeChat）。 */
    private static class FakeChat implements LlmChatClient {
        private final String id;
        private final boolean streamForever;

        FakeChat(String id, boolean streamForever) {
            this.id = id;
            this.streamForever = streamForever;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
            return new ChatResponse();
        }

        @Override
        public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
            BlockingQueue<StreamResponse> q = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> {
                try {
                    if (streamForever) {
                        // 持续产出非终态块（模拟思考/长输出），永不发 done
                        for (;;) {
                            q.put(StreamResponse.of(ResponseType.ANSWER, "chunk", false));
                        }
                    }
                    q.put(StreamResponse.of(ResponseType.ANSWER, "done", true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return q;
        }

        @Override
        public String getModelName() {
            return id;
        }

        @Override
        public String getModelId() {
            return id;
        }
    }

    /** 对照 Go TestConcurrencyChatInteractiveNotGated：交互式调用在 limit=1 下也不被节流。 */
    @Test
    void interactiveCallsAreNotGated() {
        ConcurrencyGovernor governor = new ConcurrencyGovernor();
        governor.setGovernor(new LocalLimiter(), 1);
        ConcurrencyChatClient w = new ConcurrencyChatClient(new FakeChat("model-x", false), 0, governor);

        // 无后台标记：两次调用都不该被节流（若被节流，第二次会卡在信号量上）
        ChatResponse first = w.chat(List.of(), new ChatOptions());
        ChatResponse second = w.chat(List.of(), new ChatOptions());
        assertEquals("", first.getContent());
        assertEquals("", second.getContent());
    }

    /**
     * 对照 Go TestConcurrencyChatStreamReleasesOnAbandon：消费者停止读取后，
     * 持有的槽位必须释放。
     *
     * Go 用 ctx cancel 触发；Java 侧对应"发送阻塞超过放弃阈值"（测试里设成 1 秒）。
     */
    @Test
    void streamReleasesSlotOnAbandon() throws Exception {
        ConcurrencyGovernor governor = new ConcurrencyGovernor();
        governor.setGovernor(new LocalLimiter(), 1);
        ConcurrencyChatClient w = new ConcurrencyChatClient(
                new FakeChat("model-y", true), 0, governor, 1 /* abandonTimeoutSeconds */);

        BlockingQueue<StreamResponse> out;
        try (BackgroundTaskContext.Scope ignored = BackgroundTaskContext.mark()) {
            out = w.chatStream(List.of(), new ChatOptions());
        }

        // 消费一块，让转发线程跑起来并持有槽位
        assertTrue(out.poll(5, TimeUnit.SECONDS) != null, "应能取到第一块");

        // 槽位已被持有：后台获取必须阻塞（对照 Go 的 50ms select）
        CountDownLatch acquired = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (BackgroundTaskContext.Scope ignored = BackgroundTaskContext.mark()) {
                governor.gate("model-y").close();
                acquired.countDown();
            }
        });
        assertFalse(acquired.await(500, TimeUnit.MILLISECONDS), "槽位应由活跃流持有，后台获取不该成功");

        // 放弃：不再读取 out。转发线程应在放弃阈值后释放槽位并排干内层队列
        assertTrue(acquired.await(15, TimeUnit.SECONDS),
                "流槽位泄漏：消费者放弃后未释放");
    }
}
