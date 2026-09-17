package com.ragagent.llm.chat;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.Release;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台 LLM 调用的按模型并发闸门装饰器（对照 Go chat.concurrencyChat，
 * internal/models/chat/concurrency_wrapper.go:27-72）。
 *
 * 设计意图（照抄 Go 注释）：模型侧配额是所有 LLM 后台阶段（摘要/问题生成/图谱/
 * 多模态富化）共享的真正瓶颈，它们都打同一个模型。把闸门放在**客户端层**——
 * 唯一能看到全部任务类型的地方——而不是 asynq 队列层（那里的权重是调度优先级，
 * 不是限流）。
 *
 * 只有后台（worker）调用被节流；交互式聊天不受影响（见
 * {@link ConcurrencyGovernor} 对 background task 标记的判断），因此文档摄取风暴
 * 不会耗尽 provider，而用户可见的延迟永远不会排在信号量后面。
 *
 * 它是最外层装饰器：信号量只包住真正的 provider 往返，等待时间不计入内层
 * debug/langfuse 计时。
 */
public class ConcurrencyChatClient implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyChatClient.class);

    /**
     * 消费者放弃读取的判定阈值（秒）。Go 靠 ctx.Done() 退出阻塞的发送；Java 没有 ctx，
     * 改用"发送阻塞超过该阈值"作为等价的放弃信号（见 chatStream 注释）。
     */
    private static final long DEFAULT_ABANDON_TIMEOUT_SECONDS = 120;

    private final LlmChatClient delegate;
    /** 该模型配置的后台并发上限；0 回退到进程级默认（见 ConcurrencyGovernor.gateNamedN）。 */
    private final int limit;
    private final ConcurrencyGovernor governor;
    private final long abandonTimeoutSeconds;

    public ConcurrencyChatClient(LlmChatClient delegate, int limit, ConcurrencyGovernor governor) {
        this(delegate, limit, governor, DEFAULT_ABANDON_TIMEOUT_SECONDS);
    }

    /** 可配置放弃阈值——供测试用短超时验证释放语义。 */
    public ConcurrencyChatClient(LlmChatClient delegate, int limit, ConcurrencyGovernor governor,
                                 long abandonTimeoutSeconds) {
        this.delegate = delegate;
        this.limit = limit;
        this.governor = governor;
        this.abandonTimeoutSeconds = abandonTimeoutSeconds;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, ChatOptions options) {
        Release release = governor.gateNamedN(delegate.getModelId(), delegate.getModelName(), limit);
        try {
            return delegate.chat(messages, options);
        } finally {
            release.close();
        }
    }

    @Override
    public BlockingQueue<StreamResponse> chatStream(List<ChatMessage> messages, ChatOptions options) {
        Release release = governor.gateNamedN(delegate.getModelId(), delegate.getModelName(), limit);
        BlockingQueue<StreamResponse> inner;
        try {
            inner = delegate.chatStream(messages, options);
        } catch (RuntimeException e) {
            release.close();
            throw e;
        }
        if (inner == null) {
            release.close();
            return null;
        }
        // 持有信号量直到流完全排干再释放。
        // 若消费者放弃读取（不再从 out 取），我们会永远阻塞在发送上、永不释放信号量——
        // Go 用 select + ctx.Done() 解决，Java 侧改用 offer 超时判定放弃，
        // 放弃后后台排干内层队列，让上游生产者也能退出（对照 Go 的
        // `go func(){ for range ch {} }()`）。
        //
        // out 必须是 SynchronousQueue（= Go 的无缓冲 channel）：有界队列会让 offer
        // 在消费者不读时仍然成功，放弃检测就永远不触发。
        BlockingQueue<StreamResponse> out = new java.util.concurrent.SynchronousQueue<>();
        Thread.ofVirtual().name("llm-concurrency-" + delegate.getModelId()).start(() -> {
            boolean released = false;
            try {
                for (;;) {
                    StreamResponse resp = inner.take();
                    boolean accepted;
                    try {
                        accepted = out.offer(resp, abandonTimeoutSeconds, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        accepted = false;
                    }
                    if (!accepted) {
                        log.debug("stream consumer abandoned model={}, releasing slot and draining",
                                delegate.getModelId());
                        release.close();
                        released = true;
                        drain(inner);
                        return;
                    }
                    if (resp.isDone()) {
                        return;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("stream forwarding failed for model {}: {}", delegate.getModelId(), e.toString());
            } finally {
                if (!released) {
                    release.close();
                }
            }
        });
        return out;
    }

    /** 排干内层队列，让上游生产者可以正常退出（不消费内容，只腾出空间）。 */
    private static void drain(BlockingQueue<StreamResponse> inner) {
        try {
            for (;;) {
                StreamResponse r = inner.poll(1, TimeUnit.SECONDS);
                if (r == null || r.isDone()) {
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getModelId() {
        return delegate.getModelId();
    }
}
