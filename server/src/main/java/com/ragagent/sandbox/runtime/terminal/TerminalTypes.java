package com.ragagent.sandbox.runtime.terminal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交互终端（PTY）能力的中性类型（对照 Go internal/sandbox/terminal.go 全文，
 * 波 5 W5δ 翻译）。provider 适配器（cube/e2b）把这些参数翻译成各自 SDK 的旋钮；
 * WebSocket 处理层经 {@code TerminalBridge} 把浏览器终端桥接到它。
 */
public final class TerminalTypes {

    private TerminalTypes() {
    }

    /** 慢消费端对 envd 流施加背压而非丢字节（对照 terminalOutputBuffer）。 */
    public static final int TERMINAL_OUTPUT_BUFFER = 256;

    /** 对照 DefaultSandboxExecUser（exec 用户缺省）。 */
    public static final String DEFAULT_SANDBOX_EXEC_USER = "root";

    /** 对照 DefaultTerminalIdleDisconnect：无输入无输出多久后关 WS（停 TTL 刷新让沙箱可自行暂停）。 */
    public static final Duration DEFAULT_TERMINAL_IDLE_DISCONNECT = Duration.ofMinutes(15);
    private static final Duration MIN_TERMINAL_IDLE_DISCONNECT = Duration.ofSeconds(60);
    private static final Duration MAX_TERMINAL_IDLE_DISCONNECT = Duration.ofHours(24);

    private static final Duration TERMINAL_TTL_REFRESH_MIN = Duration.ofSeconds(15);
    private static final Duration TERMINAL_TTL_REFRESH_MAX = Duration.ofMinutes(2);

    /** 中性打开参数（对照 Go {@code RemoteTerminalOptions}，terminal.go L17-44）。 */
    public static final class RemoteTerminalOptions {
        /** 初始终端大小；0 回落 80x24。 */
        public int cols;
        public int rows;
        /** shell 工作目录；空 = /workspace。 */
        public String cwd = "";
        /** 运行账户；空 = DefaultSandboxExecUser。 */
        public String user = "";
        /** 额外环境变量；TERM 恒被适配器强制为 256 色。 */
        public Map<String, String> envs = new LinkedHashMap<>();
        /** 非零 = 重附到既有 PTY；失败回落 Create。 */
        public int attachPid;
        /** 允许 Connect（唤醒）已暂停沙箱：确认创建路径 true，纯打开 false。 */
        public boolean allowResume;

        public int cols() {
            return cols > 0 ? cols : 80;
        }

        public int rows() {
            return rows > 0 ? rows : 24;
        }

        public String cwd() {
            String c = cwd == null ? "" : cwd.strip();
            return c.isEmpty() ? "/workspace" : c;
        }

        public String user() {
            String u = user == null ? "" : user.strip();
            return u.isEmpty() ? DEFAULT_SANDBOX_EXEC_USER : u;
        }

        /**
         * 调用方 envs 合并到交互默认（TERM/LANG/LC_ALL）之上，绝不降级 TERM
         * （对照 terminalEnvs，terminal.go L146-158）。
         */
        public Map<String, String> envs() {
            if (envs == null || envs.isEmpty()) {
                return null;
            }
            Map<String, String> merged = new LinkedHashMap<>(envs);
            if (merged.get("TERM") == null || merged.get("TERM").isEmpty()) {
                merged.put("TERM", "xterm-256color");
            }
            return merged;
        }
    }

    /** 终端输出流上的一条事件（对照 Go {@code RemoteTerminalEvent}，terminal.go L49-62）。 */
    public static final class RemoteTerminalEvent {
        /** 原始 PTY 输出字节；非数据事件为 null。 */
        public final byte[] data;
        /** true = 远端 shell 进程终止（exitCode 才有意义）。 */
        public final boolean exited;
        /** shell 退出码（不可得时 -1 兜底）。 */
        public final int exitCode;
        /** 异常结束（传输失败/provider 错误）；正常退出为 null。 */
        public final Throwable err;

        private RemoteTerminalEvent(byte[] data, boolean exited, int exitCode, Throwable err) {
            this.data = data;
            this.exited = exited;
            this.exitCode = exitCode;
            this.err = err;
        }

        public static RemoteTerminalEvent data(byte[] data) {
            return new RemoteTerminalEvent(data, false, 0, null);
        }

        public static RemoteTerminalEvent exited(int exitCode) {
            return new RemoteTerminalEvent(null, true, exitCode, null);
        }

        public static RemoteTerminalEvent error(Throwable err) {
            return new RemoteTerminalEvent(null, false, 0, err);
        }
    }

    /**
     * 一条活 PTY 的中性句柄（对照 Go {@code RemoteTerminalSession}，terminal.go
     * L67-85）。实现必须支持 Write/Resize 与 Output 消费并发。
     */
    public interface RemoteTerminalSession {
        /** 输出事件队列；终端结束时经 {@link #close()} 恰好一次收尾。 */
        java.util.concurrent.BlockingQueue<RemoteTerminalEvent> output();

        /** 远端 shell 的 PID（provider 不暴露时 0）。 */
        int pid();

        /** 喂入原始字节（键击）。 */
        void write(byte[] data) throws Exception;

        /** 改窗口大小。 */
        void resize(int cols, int rows) throws Exception;

        /** 断开 WeKnora 与 PTY 的连接但不杀远端进程（provider 原生重连可重附）。可重复调用。 */
        void close() throws Exception;
    }

    /**
     * 可选能力：会话是否<b>已终结</b>——Go 的 {@code Output() <-chan} 靠 <b>channel 关闭</b>表达
     * "没有更多事件"，Java 的 {@code BlockingQueue} 没有关闭语义，故用这个接口补上。
     *
     * <p>消费方（如 {@code TerminalBridge} 的窄接口）据此把"取不到事件且已终结"翻译成 null
     * （= 流结束），从而与 Go 的 {@code for event := range Output()} 等价。实现者应保证：
     * 终结前最后一次投递必是 {@code exited} 或 {@code err} 事件之一（照 Go 的 Wait 判定）。</p>
     */
    public interface TerminalSessionState {
        boolean finished();
    }

    /** 终端能力接口（对照 Go {@code RemoteTerminalManager}，terminal.go L174-177）。 */
    public interface RemoteTerminalManager {
        RemoteTerminalSession openTerminal(String sandboxId, RemoteTerminalOptions opts)
                throws Exception;
    }

    /**
     * 对照 EffectiveTerminalIdleDisconnect（terminal.go L214-225）：0 用内置默认，
     * 越界钳到 [60s, 24h]。
     */
    public static Duration effectiveTerminalIdleDisconnect(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            return DEFAULT_TERMINAL_IDLE_DISCONNECT;
        }
        if (d.compareTo(MIN_TERMINAL_IDLE_DISCONNECT) < 0) {
            return MIN_TERMINAL_IDLE_DISCONNECT;
        }
        if (d.compareTo(MAX_TERMINAL_IDLE_DISCONNECT) > 0) {
            return MAX_TERMINAL_IDLE_DISCONNECT;
        }
        return d;
    }

    /** 对照 terminalTTLRefreshInterval（terminal.go L233-242）：ttl/3 钳到 [15s, 2m]。 */
    public static Duration terminalTtlRefreshInterval(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return TERMINAL_TTL_REFRESH_MIN;
        }
        Duration interval = ttl.dividedBy(3);
        if (interval.compareTo(TERMINAL_TTL_REFRESH_MIN) < 0) {
            return TERMINAL_TTL_REFRESH_MIN;
        }
        if (interval.compareTo(TERMINAL_TTL_REFRESH_MAX) > 0) {
            return TERMINAL_TTL_REFRESH_MAX;
        }
        return interval;
    }
}
