package com.ragagent.memory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;

/**
 * 一次蒸馏运行的剩余时间预算（整个运行套一个截止时间）。
 *
 * <h2>它解决什么问题</h2>
 * <p>租约（lease）是"同一个主体同时只有一个 worker"的保证。若一次运行里的模型调用
 * 慢到超过租约 TTL，第二个 worker 会认为租约已过期并**接管同一个主体**，
 * 于是两边的 checkpoint 互相打架——晚到的那个会撞上
 * {@code MemoryExtractionLeaseLostException} 而丢掉成果。预算在
 * 租约过期**之前**掐掉模型调用，让这一次运行干净地失败并被重投。</p>
 *
 * <h2>实现方式</h2>
 * <p>这里没有 context 取消机制，所以：</p>
 * <ul>
 *   <li><b>调用前判到期</b>：{@link #expired()} 为真时直接抛
 *       {@link RunExpiredException}，一个模型调用都不发；</li>
 *   <li><b>调用时给上限</b>：{@link #chat} 把阻塞的 {@code chat} 放到虚拟线程上，
 *       用剩余预算 {@code get(timeout)}。超时就 {@code cancel(true)} 并抛同一个异常。</li>
 * </ul>
 * <p>⚠️ <b>已知局限</b>：{@code cancel(true)} 只是把等待者唤醒，底层连接可能仍在跑（
 * 它最终会被 {@code LlmChatClient} 自己的传输超时收掉）。
 * 后果是**可能多占一条连接**，不是行为分叉。</p>
 *
 * <h2>降级口径</h2>
 * <ul>
 *   <li>抽取模型：预算耗尽 = 运行失败 → 上抛，让队列重投；</li>
 *   <li>归并模型 / 话题裁决：预算耗尽 = "模型不可用" → 只记日志并降级
 *       （落进各自的 warn 分支）。</li>
 * </ul>
 * <p>后两条由调用方 {@code catch (RunExpiredException)} 表达——所以它是
 * {@code public} 的嵌套类型，而不是"只有这里知道"的实现细节。</p>
 */
final class MemoryRunBudget {

    /** 没有预算上限（对照 HTTP 请求路径上的 ctx：{@code ConsolidateNow} 走这个）。 */
    static final MemoryRunBudget UNBOUNDED = new MemoryRunBudget(null);

    /** 单次模型调用的兜底上限，仅在无上限预算下用。 */
    private static final Duration UNBOUNDED_CALL_CEILING = Duration.ofMinutes(5);

    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();

    /** {@code null} = 无上限。 */
    private final Instant deadline;

    private MemoryRunBudget(Instant deadline) {
        this.deadline = deadline;
    }

    /** {@code null} / 非正数 → 无上限。 */
    static MemoryRunBudget of(Duration budget) {
        if (budget == null || budget.isZero() || budget.isNegative()) {
            return UNBOUNDED;
        }
        return new MemoryRunBudget(Instant.now().plus(budget));
    }

    /** 预算是否已耗尽。 */
    boolean expired() {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /** 还剩多少时间；无上限时回 {@link #UNBOUNDED_CALL_CEILING}。 */
    Duration remaining() {
        if (deadline == null) {
            return UNBOUNDED_CALL_CEILING;
        }
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * 在预算内发一次聊天调用。
     *
     * @throws RunExpiredException 预算已耗尽
     */
    ChatResponse chat(LlmChatClient client, List<ChatMessage> messages, ChatOptions options) {
        if (expired()) {
            throw new RunExpiredException();
        }
        Future<ChatResponse> future = CALLS.submit(() -> client.chat(messages, options));
        try {
            return future.get(remaining().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RunExpiredException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunExpiredException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** 预算耗尽异常。 */
    static final class RunExpiredException extends RuntimeException {
        RunExpiredException() {
            super("memory: run deadline exceeded");
        }
    }
}
