package com.ragagent.agent.tools;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.ragagent.event.CommandOutputData;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventType;

/**
 * shell 工具的命令输出预览（对照 Go {@code shell_command_output.go} 的 shellCommandOutput，
 * <b>本批唯一 emit 点</b>，对接 com.ragagent.event——波 4.5c 的 shell_exec 才真正触发它，
 * 本批只接线 + 单测）。这是有界的、人看的预览：不进模型会话、不改变最终工具结果。
 *
 * <p>行为锚点（Go 测试逐条对应）：</p>
 * <ul>
 *   <li>启动即 emit 一帧 {@code done=false}（command 已掩码、output 空）；</li>
 *   <li>输出累计尾量上限 <b>8192 字节</b>，截头时按 UTF-8 rune 起始字节重对齐；</li>
 *   <li>首块输出或距上次 emit ≥500ms 时立即 emit；短促输出靠 500ms 定时器兜底 flush；</li>
 *   <li>finish 双调用安全（closed 幂等），关闭后到达的 append 回调被丢弃；</li>
 *   <li>每帧全新 UUID（event id）——帧是完整状态，客户端无需重组。</li>
 * </ul>
 */
public final class ShellCommandOutput {

    private static final int TAIL_LIMIT = 8192;
    private static final long FLUSH_INTERVAL_MILLIS = 500;

    /** 全部实例共享的守护定时器（对照 Go 的 time.AfterFunc）。 */
    private static final ScheduledExecutorService FLUSH_SCHEDULER = new ScheduledThreadPoolExecutor(0, r -> {
        Thread t = new Thread(r, "shell-command-output-flush");
        t.setDaemon(true);
        return t;
    });

    private final ToolExecContext meta;
    private final EventBus bus;
    private final String maskedCommand;
    private final OffsetDateTime started;

    private final ReentrantLock mu = new ReentrantLock();
    private final ByteArrayOutputStream tail = new ByteArrayOutputStream();
    private Instant last;
    private ScheduledFuture<?> flushTimer;
    private boolean sentOutput;
    private boolean closed;

    private ShellCommandOutput(ToolExecContext meta, EventBus bus, String maskedCommand) {
        this.meta = meta;
        this.bus = bus;
        this.maskedCommand = maskedCommand;
        this.started = OffsetDateTime.now();
        this.last = Instant.now();
    }

    /**
     * 创建预览通道并发出第一帧。无 execMeta 或 EventBus 为空时返回 no-op 句柄
     * （对照 Go 的 {@code return nil, func() {}}）。
     */
    public static ShellCommandOutput start(ToolExecContext meta, String command) {
        if (meta == null || meta.eventBus() == null) {
            return new ShellCommandOutput(null, null, "");
        }
        String masked = ShellEnvExtractor.maskCommandAssignments(command == null ? "" : command);
        ShellCommandOutput out = new ShellCommandOutput(meta, meta.eventBus(), masked);
        out.emit(false);
        return out;
    }

    /** 输出到达（对照 appendOutput；stream 参数与 Go 一致地忽略——只保留累计尾量）。 */
    public void appendOutput(String stream, byte[] chunk) {
        mu.lock();
        try {
            if (closed || bus == null) {
                return;
            }
            tail.writeBytes(chunk);
            byte[] bytes = tail.toByteArray();
            if (bytes.length > TAIL_LIMIT) {
                int cut = bytes.length - TAIL_LIMIT;
                // 截头后对齐到 rune 起始字节（对照 utf8.RuneStart 循环）
                while (cut < bytes.length && !runeStart(bytes[cut])) {
                    cut++;
                }
                tail.reset();
                tail.writeBytes(Arrays.copyOfRange(bytes, cut, bytes.length));
            }
            Instant now = Instant.now();
            if (!sentOutput || Duration.between(last, now).toMillis() >= FLUSH_INTERVAL_MILLIS) {
                cancelFlushTimer();
                sentOutput = true;
                emit(false);
            } else if (flushTimer == null) {
                long delay = FLUSH_INTERVAL_MILLIS - Duration.between(last, now).toMillis();
                if (delay < 0) {
                    delay = 0;
                }
                flushTimer = FLUSH_SCHEDULER.schedule(() -> {
                    mu.lock();
                    try {
                        flushTimer = null;
                        if (!closed) {
                            emit(false);
                        }
                    } finally {
                        mu.unlock();
                    }
                }, delay, TimeUnit.MILLISECONDS);
            }
        } finally {
            mu.unlock();
        }
    }

    /** 结束并 emit 最后一帧 done=true（幂等；对照 finish）。 */
    public void finish() {
        mu.lock();
        try {
            if (bus == null || closed) {
                return;
            }
            closed = true;
            cancelFlushTimer();
            emit(true);
        } finally {
            mu.unlock();
        }
    }

    // ---- 内部 ----

    /** emit 一帧（调用方必须持有 mu 或处于 start 单线程期）。对照 Go 的闭包 emit(done)。 */
    private void emit(boolean done) {
        last = Instant.now();
        CommandOutputData data = new CommandOutputData(
                meta.toolCallId(),
                maskedCommand,
                started,
                decodeUtf8(tail.toByteArray()),
                done);
        Event event = new Event(UUID.randomUUID().toString(), EventType.EVENT_AGENT_COMMAND_OUTPUT,
                meta.sessionId(), data, null, null);
        bus.emit(event);
    }

    private void cancelFlushTimer() {
        if (flushTimer != null) {
            flushTimer.cancel(false);
            flushTimer = null;
        }
    }

    /** 对照 strings.ToValidUTF8(tail, "")：丢弃非法 UTF-8 序列（替代式解码会引入 U+FFFD）。 */
    private static String decodeUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            // 对照 strings.ToValidUTF8 永不失败（IGNORE 动作）——此处仅为受检异常收口
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** 对照 utf8.RuneStart：10xx 开头的字节是续字节，不是 rune 起点。 */
    private static boolean runeStart(byte b) {
        return (b & 0xC0) != 0x80;
    }
}
