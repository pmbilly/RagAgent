package com.ragagent.session.service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.sandbox.runtime.terminal.TerminalTypes;

/**
 * 终端桥（对照 Go internal/handler/session/sandbox_terminal_bridge.go 全文 340 行）：
 * 一条升级后的 WS 连接与沙箱 PTY 之间的泵组。
 *
 * <p>协议（文件头注释逐条对应）：</p>
 * <ul>
 *   <li>binary 帧 双向：客户端按键裸流 → PTY；PTY 输出 → 客户端；</li>
 *   <li>text 帧 双向：JSON 控制帧（resize/ping/ready/error/exited）。</li>
 * </ul>
 *
 * <p>五个 goroutine 的对应物（虚拟线程）：输出泵（PTY 输出 → 写帧）、输入泵
 * （读帧 → PTY/控制）、心跳（30s ping；写失败=断连）、空闲看门狗
 * （idleDisconnect 无活动 → IDLE_DISCONNECTED）、鉴权复查（60s；
 * DENIED → AUTH_REVOKED，其它错误只 WARN 重试）。哪个先观察到终结，
 * 哪个触发一次 teardown（AtomicBoolean once）。</p>
 *
 * <h2>dev 接缝（XDEP）</h2>
 * <p>PTY 会话端（{@link PtySession}）的生产实现需要 provider 终端执行体（波 5）。
 * dev 的 openTerminal 在此之前就落 SANDBOX_NOT_BOUND/TERMINAL_UNSUPPORTED/INTERNAL
 * 关闭帧（与 Go 逐字节对齐，A/B 已验），bridge.run() 本体只在真部署可达——
 * 协议形态已按 Go 全文移植，等待 PtySession 的生产实现。</p>
 */
public final class TerminalBridge {

    /** 协议常量（对照 sandbox_terminal_ws.go L39-78）。 */
    public static final Duration PING_INTERVAL = Duration.ofSeconds(30);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
    public static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
    public static final int MAX_INPUT_BYTES = 4096;
    public static final int MAX_GEOMETRY = 500;
    public static final int MAX_PER_SESSION = 5;
    public static final Duration AUTH_RECHECK_INTERVAL = Duration.ofMinutes(1);
    public static final Duration AUTH_RECHECK_TIMEOUT = Duration.ofSeconds(10);

    public static final String ERR_NOT_BOUND = "SANDBOX_NOT_BOUND";
    public static final String ERR_PAUSED = "SANDBOX_PAUSED";
    public static final String ERR_UNSUPPORTED = "TERMINAL_UNSUPPORTED";
    public static final String ERR_INTERNAL = "INTERNAL";
    public static final String ERR_IDLE = "IDLE_DISCONNECTED";
    public static final String ERR_AUTH = "AUTH_REVOKED";

    private static final Logger log = LoggerFactory.getLogger(TerminalBridge.class);

    /** PTY 会话窄接口（对照 service.SessionTerminal 的 Session 消费面）。 */
    public interface PtySession {
        /** 输出事件（对照 RemoteTerminalSession 的 Output() 流）。 */
        record OutputEvent(byte[] data, boolean exited, Integer exitCode, RuntimeException err) {}

        OutputEvent next() throws InterruptedException;

        void write(byte[] data) throws IOException;

        void resize(int cols, int rows) throws IOException;

        void close();
    }

    /**
     * 中性会话 → 桥窄接口（W5δ 执行体批补的装配件；生产接线点在
     * {@code SandboxTerminalController:300} 一带，此前注释为"随波 5 的 provider 执行体接线"）。
     *
     * <p>终结语义：中性会话若实现 {@link TerminalTypes.TerminalSessionState}，则"已终结且队列取空"
     * 翻译成 <b>null</b>（= Go {@code for event := range Output()} 的 channel 关闭）。收尾事件
     * （{@code exited}/{@code err}）由会话在此前投递，桥按既有分支发帧并拆除。</p>
     */
    public static PtySession adapt(TerminalTypes.RemoteTerminalSession session) {
        return new PtySession() {

            @Override
            public OutputEvent next() throws InterruptedException {
                while (true) {
                    TerminalTypes.RemoteTerminalEvent event =
                            session.output().poll(200, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        Throwable err = event.err;
                        return new OutputEvent(event.data, event.exited,
                                event.exited ? event.exitCode : null,
                                err == null ? null
                                        : (err instanceof RuntimeException re ? re
                                                : new RuntimeException(err.getMessage(), err)));
                    }
                    if (session instanceof TerminalTypes.TerminalSessionState state
                            && state.finished()) {
                        return null;
                    }
                }
            }

            @Override
            public void write(byte[] data) throws IOException {
                try {
                    session.write(data);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e.getMessage(), e);
                }
            }

            @Override
            public void resize(int cols, int rows) throws IOException {
                try {
                    session.resize(cols, rows);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e.getMessage(), e);
                }
            }

            @Override
            public void close() {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 收尾失败不影响桥的拆除（照 Go 忽略 close 错误）
                }
            }
        };
    }

    /** 控制帧（对照 terminalControlFrame：omitempty 键省略）。 */
    @JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ControlFrame {
        @JsonProperty("type")
        public String type;
        @JsonProperty("code")
        public String code;
        @JsonProperty("message")
        public String message;
        @JsonProperty("pty_id")
        public Long pid;
        @JsonProperty("backend")
        public String backend;
        @JsonProperty("exit_code")
        public Integer exitCode;
        @JsonProperty("cols")
        public Integer cols;
        @JsonProperty("rows")
        public Integer rows;
    }

    private final TerminalWebSocketServer conn;
    private final PtySession pty;
    private final String session;
    private final String backend;
    private final long pid;
    private final Duration idleDisconnect;
    private final AuthCheck authCheck;
    private final Runnable teardownAction;
    private final ObjectMapper mapper;

    private final AtomicLong lastActivity = new AtomicLong();
    private final AtomicBoolean tornDown = new AtomicBoolean(false);
    private volatile String teardownReason = "";

    /** 鉴权复查回调（对照 authCheck func；null=禁用）。 */
    public interface AuthCheck {
        /** DENIED → 抛 TerminalAuthDeniedException；查找失败 → 其它 RuntimeException。 */
        void check() throws RuntimeException;
    }

    public TerminalBridge(TerminalWebSocketServer conn, PtySession pty, String session,
            String backend, long pid, Duration idleDisconnect, AuthCheck authCheck,
            Runnable teardownAction, ObjectMapper mapper) {
        this.conn = conn;
        this.pty = pty;
        this.session = session;
        this.backend = backend;
        this.pid = pid;
        this.idleDisconnect = idleDisconnect;
        this.authCheck = authCheck;
        this.teardownAction = teardownAction;
        this.mapper = mapper;
    }

    private void teardownWith(String reason) {
        if (tornDown.compareAndSet(false, true)) {
            teardownReason = reason;
            teardownAction.run();
            try {
                pty.close();
            } catch (RuntimeException e) {
                log.debug("[sandbox-terminal] close failed session={}: {}", session, e.toString());
            }
            conn.close();
            log.info("[sandbox-terminal] closed session={} reason={}", session, teardownReason);
        }
    }

    /** 对照 bridge.run()：ready 帧 → 启泵 → 输入泵阻塞 → teardown。 */
    public void run() {
        sendControl(control("ready", f -> {
            f.pid = pid;
            f.backend = backend;
        }));
        touchActivity();
        Thread.Builder.OfVirtual vb = Thread.ofVirtual();
        vb.start(this::pumpOutput);
        vb.start(this::heartbeat);
        vb.start(this::watchIdle);
        if (authCheck != null) {
            vb.start(this::watchAuth);
        }
        pumpInput();
        teardownWith("client_disconnected");
    }

    private interface FrameSetup {
        void apply(ControlFrame f);
    }

    private static ControlFrame control(String type, FrameSetup setup) {
        ControlFrame f = new ControlFrame();
        f.type = type;
        setup.apply(f);
        return f;
    }

    /** 对照 sendControl：JSON 序列化 + 写锁内写 text 帧。 */
    private void sendControl(ControlFrame frame) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(frame);
        } catch (IOException e) {
            return;
        }
        try {
            conn.writeMessage(TerminalWebSocketServer.OP_TEXT, payload);
        } catch (IOException e) {
            log.debug("[sandbox-terminal] control write failed session={}: {}", session,
                    e.toString());
        }
    }

    /** 对照 pumpOutput：PTY 输出 → binary 帧；exit → exited 帧；流错 → error 帧。 */
    private void pumpOutput() {
        try {
            while (true) {
                PtySession.OutputEvent event = pty.next();
                if (event == null) {
                    // 中性会话终结（Go 的 `for range Output()` 在 channel 关闭时退出）——
                    // 收尾事件（exited/err）已在此前投递过，这里静默收工
                    teardownWith("stream_closed");
                    return;
                }
                if (event.err() != null) {
                    log.warn("[sandbox-terminal] stream error session={}: {}", session,
                            event.err().toString());
                    sendControl(control("error", f -> {
                        f.code = ERR_INTERNAL;
                        f.message = "terminal stream failed";
                    }));
                    teardownWith("stream_error");
                    return;
                }
                if (event.exited()) {
                    Integer code = event.exitCode();
                    Integer boxed = code == null ? -1 : code;
                    sendControl(control("exited", f -> f.exitCode = boxed));
                    teardownWith("shell_exited");
                    return;
                }
                byte[] data = event.data();
                if (data != null && data.length > 0) {
                    touchActivity();
                    try {
                        conn.writeMessage(TerminalWebSocketServer.OP_BINARY, data);
                    } catch (IOException e) {
                        log.debug("[sandbox-terminal] output write failed session={}: {}",
                                session, e.toString());
                        teardownWith("output_write_failed");
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            teardownWith("stream_closed");
        }
    }

    /** 对照 pumpInput：binary=按键、text=resize/ping。 */
    private void pumpInput() {
        try {
            while (true) {
                TerminalWebSocketServer.Frame frame = conn.readMessage();
                if (frame.opcode() == TerminalWebSocketServer.OP_BINARY) {
                    touchActivity();
                    pty.write(frame.payload());
                } else if (frame.opcode() == TerminalWebSocketServer.OP_TEXT) {
                    ControlFrame ctrl;
                    try {
                        ctrl = mapper.readValue(frame.payload(), ControlFrame.class);
                    } catch (IOException e) {
                        continue;
                    }
                    if ("resize".equals(ctrl.type)) {
                        int cols = ctrl.cols == null ? 0 : ctrl.cols;
                        int rows = ctrl.rows == null ? 0 : ctrl.rows;
                        if (cols <= 0 || rows <= 0 || cols > MAX_GEOMETRY || rows > MAX_GEOMETRY) {
                            continue;
                        }
                        touchActivity();
                        pty.resize(cols, rows);
                    } else if ("ping".equals(ctrl.type)) {
                        sendControl(control("pong", f -> {}));
                    }
                }
            }
        } catch (IOException e) {
            // 正常关闭或传输失败——都终结会话
        }
    }

    private void touchActivity() {
        lastActivity.set(System.nanoTime());
    }

    private boolean idleExpired(Duration idle) {
        if (idle == null || idle.isZero() || idle.isNegative()) {
            return false;
        }
        long last = lastActivity.get();
        if (last == 0) {
            return false;
        }
        long elapsedNanos = System.nanoTime() - last;
        return elapsedNanos >= idle.toNanos();
    }

    /** 对照 watchIdle（L209-236）。 */
    private void watchIdle() {
        if (idleDisconnect == null || idleDisconnect.isZero() || idleDisconnect.isNegative()) {
            return;
        }
        Duration tick = idleDisconnect.dividedBy(12);
        if (tick.toMillis() < 200) {
            tick = Duration.ofMillis(200);
        }
        if (tick.toMillis() > 5_000) {
            tick = Duration.ofSeconds(5);
        }
        while (true) {
            try {
                Thread.sleep(tick.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (idleExpired(idleDisconnect)) {
                sendControl(control("error", f -> {
                    f.code = ERR_IDLE;
                    f.message = "terminal idle disconnect";
                }));
                conn.writeClose(TerminalWebSocketServer.CLOSE_POLICY_VIOLATION, ERR_IDLE);
                teardownWith("idle_disconnected");
                return;
            }
        }
    }

    /** 对照 watchAuth（L252-282）：DENIED → AUTH_REVOKED；其它失败 WARN 后继续。 */
    private void watchAuth() {
        while (true) {
            try {
                Thread.sleep(AUTH_RECHECK_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                authCheck.check();
            } catch (RuntimeException e) {
                if (e instanceof SandboxTerminalAuthService.TerminalAuthDeniedException) {
                    sendControl(control("error", f -> {
                        f.code = ERR_AUTH;
                        f.message = "terminal authorization revoked";
                    }));
                    conn.writeClose(TerminalWebSocketServer.CLOSE_POLICY_VIOLATION, ERR_AUTH);
                    teardownWith("auth_revoked");
                    return;
                }
                log.warn("[sandbox-terminal] auth recheck failed session={}: {}", session,
                        e.toString());
            }
        }
    }

    /** 对照 heartbeat：30s ping，写失败=断连（对照 L300-325 的注释语义）。 */
    private void heartbeat() {
        while (true) {
            try {
                Thread.sleep(PING_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                conn.writeControl(TerminalWebSocketServer.OP_PING, new byte[0]);
            } catch (IOException e) {
                teardownWith("ping_failed");
                return;
            }
        }
    }
}
