package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.StreamResponse;

import org.junit.jupiter.api.Test;

/**
 * thinking-done 契约：<b>每个 thinking 突发恰好一个 done 标记</b>，且它必须排在
 * 第一个答案 token 之前（对照 Go stream_emit.go 的注释契约）。
 */
class ThinkingEmitterTest {

    /** 收到多个 reasoning 分片时，只在最后补一个 done。 */
    @Test
    void emitsExactlyOneDoneAfterThinkingChunks() throws InterruptedException {
        BlockingQueue<StreamResponse> ch = new ArrayBlockingQueue<>(16);
        ThinkingEmitter emitter = new ThinkingEmitter();

        emitter.emit(ch, "think-1");
        emitter.emit(ch, "think-2");
        emitter.finish(ch);

        List<StreamResponse> out = drain(ch);
        assertEquals(3, out.size());
        assertEquals(ResponseType.THINKING, out.get(0).getResponseType());
        assertEquals("think-1", out.get(0).getContent());
        assertFalse(out.get(0).isDone());
        assertEquals("think-2", out.get(1).getContent());
        assertFalse(out.get(1).isDone());
        assertTrue(out.get(2).isDone());
        assertEquals(ResponseType.THINKING, out.get(2).getResponseType());
        assertEquals("", out.get(2).getContent(), "done 标记不带内容（Go 的零值 StreamResponse）");
    }

    /** 没有任何 thinking 就不发 done（对照 Go finish 的 !active 短路）。 */
    @Test
    void noDoneWithoutThinking() throws InterruptedException {
        BlockingQueue<StreamResponse> ch = new ArrayBlockingQueue<>(16);
        ThinkingEmitter emitter = new ThinkingEmitter();

        emitter.finish(ch);
        emitter.finish(ch);

        assertTrue(drain(ch).isEmpty());
        assertFalse(emitter.isActive());
    }

    /** finish 可重复调用，只有第一次发得出东西（流循环里多处收尾的写法依赖它）。 */
    @Test
    void repeatedFinishEmitsOnlyOnce() throws InterruptedException {
        BlockingQueue<StreamResponse> ch = new ArrayBlockingQueue<>(16);
        ThinkingEmitter emitter = new ThinkingEmitter();

        emitter.emit(ch, "t");
        emitter.finish(ch);
        emitter.finish(ch);
        emitter.finish(ch);

        List<StreamResponse> out = drain(ch);
        assertEquals(2, out.size());
        assertTrue(out.get(1).isDone());
        assertFalse(emitter.isActive());
    }

    /** 两段 thinking 突发之间夹着答案时，各自补一个 done（与 Go 的 active 位翻转一致）。 */
    @Test
    void secondBurstGetsItsOwnDone() throws InterruptedException {
        BlockingQueue<StreamResponse> ch = new ArrayBlockingQueue<>(16);
        ThinkingEmitter emitter = new ThinkingEmitter();

        emitter.emit(ch, "burst-1");
        emitter.finish(ch);
        emitter.emit(ch, "burst-2");
        emitter.finish(ch);

        List<StreamResponse> out = drain(ch);
        assertEquals(4, out.size());
        assertFalse(out.get(0).isDone());
        assertTrue(out.get(1).isDone());
        assertEquals("burst-2", out.get(2).getContent());
        assertTrue(out.get(3).isDone());
    }

    private static List<StreamResponse> drain(BlockingQueue<StreamResponse> ch) {
        List<StreamResponse> out = new ArrayList<>();
        StreamResponse item;
        while ((item = ch.poll()) != null) {
            out.add(item);
        }
        return out;
    }
}
