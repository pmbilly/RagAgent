package com.ragagent.llm.chat;

import java.util.concurrent.BlockingQueue;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;

/**
 * "先思考、后回答"的交接器。
 *
 * <p>每个流式 Chat 实现都共享这条纪律：thinking 分片随到随转发，并且<b>恰好一个</b>
 * thinking-done 标记会在第一个答案 token 之前（或流在没有答案的情况下结束时）发出。
 * 把记账集中在这里，OpenAI 兼容路径与 Ollama 路径才不会各自漂移。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * ThinkingEmitter thinking = new ThinkingEmitter();
 * thinking.emit(ch, reasoningDelta);   // 收到 reasoning_content 时
 * thinking.finish(ch);                 // 收到首个答案 token 前，或流结束时
 * }</pre>
 *
 * <p>非线程安全：一个流循环里只由一个线程使用。</p>
 */
public final class ThinkingEmitter {

    /** 是否还欠一个 thinking-done 标记。 */
    private boolean active;

    /**
     * 转发一个 reasoning 分片，并记下"还欠一个 done"。
     *
     * <p>通道写入是阻塞的；线程被中断时抛出
     * {@link InterruptedException}，流循环随之退出。</p>
     */
    public void emit(BlockingQueue<StreamResponse> ch, String content) throws InterruptedException {
        active = true;
        ch.put(StreamResponse.of(ResponseType.THINKING, content, false));
    }

    /**
     * 还欠标记时发出<b>唯一</b>一个 thinking-done。
     * 可安全重复调用——emit 之后的第一次调用发，之后什么都不发。
     *
     * <p>注意终态响应只有 type=thinking + done=true，content 为空。</p>
     */
    public void finish(BlockingQueue<StreamResponse> ch) throws InterruptedException {
        if (!active) {
            return;
        }
        active = false;
        ch.put(StreamResponse.of(ResponseType.THINKING, "", true));
    }

    /** 是否还欠一个 thinking-done。 */
    public boolean isActive() {
        return active;
    }
}
