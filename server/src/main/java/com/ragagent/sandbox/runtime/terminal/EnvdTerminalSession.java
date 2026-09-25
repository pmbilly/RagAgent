package com.ragagent.sandbox.runtime.terminal;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.sandbox.runtime.SandboxException;

/**
 * envd PTY 的会话句柄（对照 Go {@code cubeTerminalSession}+{@code PtyHandle.readLoop}+{@code Wait}
 * 的合成语义，{@code cube_terminal.go:126-183}、{@code pty.go:250-320}；E2B 侧同构，
 * {@code e2b_terminal.go:141-208}）。
 *
 * <p>契约与 Go 一一对应：</p>
 * <ul>
 *   <li><b>输出队列</b>：容量 {@link TerminalTypes#TERMINAL_OUTPUT_BUFFER}（256），慢消费者对
 *       envd 流施加<b>背压</b>而不是丢字节（Go 的带缓冲 channel）；</li>
 *   <li><b>收尾恰好一次</b>：流结束时按 Go {@code Wait} 的顺序判定——① {@code end} 事件的
 *       {@code error} 文本非空 → 错误事件；② 从没见过 {@code end} → 错误事件
 *       {@code "PTY stream ended without an end event"}；③ 否则 exited（退出码解析见
 *       {@link EnvdPtyProtocol#exitCodeOf}，缺省 0）；</li>
 *   <li><b>Close 是"断开"不是"杀"</b>：只关本端流（远端 shell 留着，可经 {@code Connect(pid)}
 *       重附）；幂等；关闭后 {@link #write} 抛 {@link PtyInputClosedException}；</li>
 *   <li><b>TTL 刷新</b>：开会话即刷一次，之后每 {@link TerminalTypes#terminalTtlRefreshInterval}
 *       一次，每次 8s 超时（照 Go {@code startTerminalTTLRefresh}，terminal.go:244-283）——Cube 的
 *       Connect 不顺手续 TTL，所以"立即刷"是语义的一部分。</li>
 * </ul>
 */
final class EnvdTerminalSession
        implements TerminalTypes.RemoteTerminalSession, TerminalTypes.TerminalSessionState {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EnvdTerminalSession.class);

    /** 每次 TTL 刷新的超时（照 Go 的 {@code 8*time.Second}）。 */
    static final Duration TTL_REFRESH_TIMEOUT = Duration.ofSeconds(8);

    private final String provider;
    private final EnvdConnectTransport transport;
    private final EnvdConnectTransport.StreamingCall call;
    private final int pid;
    private final BlockingQueue<TerminalTypes.RemoteTerminalEvent> output;
    private final PtyInputCoalescer input;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread pump;
    private final ScheduledExecutorService ttlScheduler;

    private volatile Integer exitCode;
    private volatile String endErrorText;
    private volatile boolean sawEnd;
    private volatile boolean pumpDone;

    EnvdTerminalSession(String provider, EnvdConnectTransport transport,
            EnvdConnectTransport.StreamingCall call, int pid,
            Duration ttl, TtlRefresher refresher) {
        this.provider = provider;
        this.transport = transport;
        this.call = call;
        this.pid = pid;
        this.output = new ArrayBlockingQueue<>(TerminalTypes.TERMINAL_OUTPUT_BUFFER);
        this.input = new PtyInputCoalescer(
                data -> transport.unary(EnvdPtyProtocol.METHOD_SEND_INPUT,
                        EnvdPtyProtocol.inputBody(pid, data)));
        this.pump = Thread.ofVirtual().name("envd-pty-pump-" + pid).unstarted(this::pumpLoop);
        this.ttlScheduler = startTtlRefresh(ttl, refresher);
        this.pump.start();
    }

    /** TTL 刷新通道（provider 控制面的一元调用；null = 不刷）。 */
    public interface TtlRefresher {
        void refresh(Duration timeout) throws Exception;
    }

    @Override
    public BlockingQueue<TerminalTypes.RemoteTerminalEvent> output() {
        return output;
    }

    @Override
    public int pid() {
        return pid;
    }

    @Override
    public void write(byte[] data) throws Exception {
        input.write(data);
    }

    @Override
    public void resize(int cols, int rows) throws Exception {
        if (cols <= 0 || rows <= 0) {
            return;
        }
        transport.unary(EnvdPtyProtocol.METHOD_UPDATE, EnvdPtyProtocol.updateBody(pid, cols, rows));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        input.close();
        if (ttlScheduler != null) {
            ttlScheduler.shutdownNow();
        }
        try {
            call.close();
        } catch (IOException e) {
            log.debug("[{}] pty {} stream close failed: {}", provider, pid, e.toString());
        }
    }

    /** 是否已断开（测试/上层观察用）。 */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * 泵是否已收工（含收尾事件已投递）——{@link TerminalTypes.TerminalSessionState} 的实现，
     * 供消费方把"取不到事件且已终结"翻译成"流结束"（= Go 的 channel 关闭）。
     */
    @Override
    public boolean finished() {
        return pumpDone || closed.get();
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 输出泵（照 {@code PtyHandle.readLoop} + {@code Wait}）：读帧 → data 事件进队列；
     * 流结束 → 按 Go 的判定顺序投递<b>恰好一次</b>收尾事件。
     */
    private void pumpLoop() {
        try {
            while (true) {
                JsonNode payload = call.next();
                if (payload == null) {
                    finish();
                    return;
                }
                EnvdPtyProtocol.Event event = EnvdPtyProtocol.parse(payload);
                if (event == null) {
                    continue; // keepalive / 未知事件
                }
                if (event.data() != null) {
                    emit(TerminalTypes.RemoteTerminalEvent.data(event.data()));
                }
                if (event.end() != null) {
                    recordEnd(event.end());
                }
            }
        } catch (Exception e) {
            if (closed.get()) {
                return; // Close 拆流属正常收尾（照 Go 的 closedCh 判定）
            }
            emit(TerminalTypes.RemoteTerminalEvent.error(
                    SandboxException.internal("terminal stream: " + e.getMessage(), e)));
        } finally {
            pumpDone = true;
        }
    }

    /** 照 {@code recordEnd}：exitCode → exit_code → status → exited→0；error 文本单独记。 */
    private void recordEnd(JsonNode end) {
        sawEnd = true;
        Integer code = EnvdPtyProtocol.exitCodeOf(end);
        if (code != null) {
            exitCode = code;
        }
        String error = end.path("error").asText("");
        if (!error.isEmpty()) {
            endErrorText = error;
        }
    }

    /** 流结束时的收尾判定（照 {@code Wait}）。 */
    private void finish() {
        if (closed.get()) {
            return;
        }
        String streamError = call.endError();
        if (streamError != null) {
            emit(TerminalTypes.RemoteTerminalEvent.error(
                    SandboxException.internal("terminal stream: " + streamError)));
            return;
        }
        if (endErrorText != null && !endErrorText.isEmpty()) {
            emit(TerminalTypes.RemoteTerminalEvent.error(SandboxException.internal(endErrorText)));
            return;
        }
        if (!sawEnd) {
            emit(TerminalTypes.RemoteTerminalEvent.error(
                    SandboxException.internal("PTY stream ended without an end event")));
            return;
        }
        emit(TerminalTypes.RemoteTerminalEvent.exited(exitCode == null ? 0 : exitCode));
    }

    private void finishQuietly() {
        if (!closed.get()) {
            emit(TerminalTypes.RemoteTerminalEvent.error(
                    SandboxException.internal("terminal stream interrupted")));
        }
    }

    /** 照 {@code emitTerminalEvent}：慢消费者背压；Close 后立刻放弃（不再投递）。 */
    private void emit(TerminalTypes.RemoteTerminalEvent event) {
        while (!closed.get()) {
            try {
                if (output.offer(event, 100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 照 {@code startTerminalTTLRefresh}：null/非正 ttl 不刷；否则<b>立即刷一次</b>，再按
     * {@link TerminalTypes#terminalTtlRefreshInterval} 周期刷；每次 8s 超时、失败静默。
     */
    private ScheduledExecutorService startTtlRefresh(Duration ttl, TtlRefresher refresher) {
        if (refresher == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return null;
        }
        Duration interval = TerminalTypes.terminalTtlRefreshInterval(ttl);
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual()
                        .name("envd-pty-ttl-" + pid).unstarted(r));
        Runnable refresh = () -> {
            if (closed.get()) {
                return;
            }
            try {
                refresher.refresh(TTL_REFRESH_TIMEOUT);
            } catch (Exception e) {
                log.debug("[{}] pty {} ttl refresh failed: {}", provider, pid, e.toString());
            }
        };
        refresh.run(); // 立即（Cube Connect 不续 TTL）
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(refresh,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return scheduler;
    }
}
