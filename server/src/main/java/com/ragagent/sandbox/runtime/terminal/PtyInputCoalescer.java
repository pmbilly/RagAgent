package com.ragagent.sandbox.runtime.terminal;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * PTY 输入聚合器（对照 Go internal/sandbox/pty_input_coalesce.go 全文，波 5 W5δ
 * 逐行翻译）：把大量小 Write 合并成更少的 send() RPC，Write 永不阻塞在 RPC 上
 * （除非队列满）。
 *
 * <p>首个排队 chunk 立即发送；RPC 在途期间到达的键击被 drain 成一次追加发送——
 * 突发打字共享 RPC，且首字符没有 Nagle 式停顿。</p>
 */
public final class PtyInputCoalescer {

    private static final int PTY_INPUT_COALESCE_MAX = 4096;
    private static final int PTY_INPUT_QUEUE_DEPTH = 64;
    private static final long FLUSH_TIMEOUT_SECONDS = 5;

    /** 对照 errPtyInputClosed。 */
    public static final String ERR_PTY_INPUT_CLOSED = "terminal input closed";

    /** flush 的实际发送通道（provider send；失败静默——Go 的 _ = send）。 */
    public interface Sender {
        void send(byte[] data) throws Exception;
    }

    private final Sender send;
    private final BlockingQueue<byte[]> ch = new LinkedBlockingQueue<>(PTY_INPUT_QUEUE_DEPTH);
    private volatile boolean closed;
    private final Thread loopThread;

    public PtyInputCoalescer(Sender send) {
        this.send = send;
        this.loopThread = Thread.ofVirtual().name("pty-input-coalescer").unstarted(this::loop);
        this.loopThread.start();
    }

    /** 对照 Write：空数据忽略；满队列/已关闭抛出（Go 的 error 通道）。 */
    public void write(byte[] data) throws PtyInputClosedException, InterruptedException {
        if (data == null || data.length == 0) {
            return;
        }
        byte[] chunk = data.clone();
        if (closed) {
            throw new PtyInputClosedException(ERR_PTY_INPUT_CLOSED);
        }
        if (!ch.offer(chunk, PTY_INPUT_QUEUE_DEPTH * 50L, TimeUnit.MILLISECONDS)) {
            // Go 的 select default：队列满立即返回错误（行为面一致：不无限阻塞）
            throw new PtyInputClosedException("terminal input queue full");
        }
    }

    /** 对照 loop：取首个 chunk → drain 到阈值内 → flush。 */
    private void loop() {
        try {
            while (true) {
                if (closed && ch.isEmpty()) {
                    return;
                }
                byte[] first = ch.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                byte[] buf = drain(first.clone());
                flush(buf);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 对照 drain：队列还有就继续拼，直到 4096 上限或队列暂空。 */
    private byte[] drain(byte[] buf) {
        while (buf.length < PTY_INPUT_COALESCE_MAX) {
            byte[] more = ch.poll();
            if (more == null) {
                return buf;
            }
            byte[] next = new byte[buf.length + more.length];
            System.arraycopy(buf, 0, next, 0, buf.length);
            System.arraycopy(more, 0, next, buf.length, more.length);
            buf = next;
        }
        return buf;
    }

    /** 对照 flush：空/无 sender 忽略；5s 超时发送，失败静默（Go 的 _ =）。 */
    private void flush(byte[] buf) {
        if (buf.length == 0 || send == null) {
            return;
        }
        if (closed) {
            return;
        }
        try {
            send.send(buf);
        } catch (Exception ignored) {
            // Go：_ = c.send(rctx, buf)
        }
    }

    /** 关闭聚合器（对照 done 通道语义：后续 Write 报 closed，循环退出）。 */
    public void close() {
        closed = true;
        loopThread.interrupt();
    }

    /** 对照 errPtyInputClosed。 */
    public static final class PtyInputClosedException extends Exception {
        public PtyInputClosedException(String message) {
            super(message);
        }
    }
}
