package com.ragagent.sandbox.runtime.terminal;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * envd 的 <b>PTY 线上消息</b>：请求体构造 + 事件解析（照 Cube SDK {@code pty.go}/{@code envd.go}
 * 的结构体 JSON 标签与常量，逐字对齐）。
 *
 * <h2>RPC 方法名（易错：Resize 与 Kill 的名字不是字面）</h2>
 *
 * <table border="1">
 *   <caption>方法 → 路径（{@code envd.go:90}、{@code pty.go:107,122,142,159,368,432}）</caption>
 *   <tr><th>动作</th><th>方法名</th><th>请求体</th></tr>
 *   <tr><td>建 PTY</td><td>{@code Start}（流式）</td>
 *       <td>{@code {"process":{"cmd":"/bin/bash","args":["-i","-l"],"envs":{…},"cwd":…},
 *       "pty":{"size":{"rows":R,"cols":C}}}}</td></tr>
 *   <tr><td>重附 PTY</td><td>{@code Connect}（流式）</td><td>{@code {"process":{"pid":P}}}</td></tr>
 *   <tr><td>喂键击</td><td>{@code SendInput}（一元）</td>
 *       <td>{@code {"process":{"pid":P},"input":{"pty":"&lt;base64&gt;"}}}</td></tr>
 *   <tr><td>改窗口</td><td><b>{@code Update}</b>（一元）</td>
 *       <td>{@code {"process":{"pid":P},"pty":{"size":{"rows":R,"cols":C}}}}</td></tr>
 *   <tr><td>杀 shell</td><td><b>{@code SendSignal}</b>（一元）</td>
 *       <td>{@code {"process":{"pid":P},"signal":"SIGNAL_SIGKILL"}}</td></tr>
 * </table>
 *
 * <p>{@code Resize}/{@code Kill} 只是 SDK 的门面方法名（{@code Pty.Resize} 发的是 {@code Update}，
 * {@code Pty.Kill} 发的是 {@code SendSignal}，信号常量值是 {@code SIGNAL_SIGKILL} 而不是
 * {@code SIGKILL}）——照字面写会 404。</p>
 *
 * <h2>事件（{@code {"event":{…}}} 包一层，{@code envd.go:45-72}）</h2>
 *
 * <ul>
 *   <li>{@code {"start":{"pid":N}}} —— PID 就在这里（{@code readPtyStartPID} 读到它才返回）；</li>
 *   <li>{@code {"data":{"pty":"<base64>"}}}（另有 {@code stdout}/{@code stderr}，PTY 流只取 {@code pty}）；</li>
 *   <li>{@code {"end":{…}}} —— 退出信息：{@code exitCode} / {@code exit_code} / {@code status} /
 *       {@code exited} / {@code error} 五个字段的<b>解析优先级</b>见 {@link #exitCodeOf}；</li>
 *   <li>{@code {"keepalive":{}}} —— 保活，跳过。</li>
 * </ul>
 */
public final class EnvdPtyProtocol {

    /** 建 PTY（流式）。 */
    public static final String METHOD_START = "Start";
    /** 重附 PTY（流式）。 */
    public static final String METHOD_CONNECT = "Connect";
    /** 喂键击（一元）。 */
    public static final String METHOD_SEND_INPUT = "SendInput";
    /** 改窗口（一元）——注意方法名是 {@code Update}。 */
    public static final String METHOD_UPDATE = "Update";
    /** 发信号（一元）——注意方法名是 {@code SendSignal}。 */
    public static final String METHOD_SEND_SIGNAL = "SendSignal";

    /** 信号常量值（{@code pty.go:29}：不是 {@code SIGKILL}）。 */
    public static final String SIGNAL_SIGKILL = "SIGNAL_SIGKILL";

    /** shell 与参数（照 {@code Pty.Create}：交互式登录 bash）。 */
    public static final String SHELL_CMD = "/bin/bash";
    public static final String[] SHELL_ARGS = {"-i", "-l"};

    /** SDK 在 Create 时补的交互默认（{@code pty.go:setDefaultEnv}）。 */
    public static final String DEFAULT_TERM = "xterm-256color";
    public static final String DEFAULT_LANG = "C.UTF-8";

    private static final Pattern EXIT_STATUS = Pattern.compile("(?:exit status|exited with code)\\s+(-?\\d+)");
    private static final Pattern SIGNAL_STATUS = Pattern.compile("(?:signal|terminated by signal)\\s+(\\d+)");

    private EnvdPtyProtocol() {
    }

    /** 建 PTY 的请求体（{@code ptyStartRequest}）。 */
    public static Map<String, Object> createBody(TerminalTypes.RemoteTerminalOptions opts,
            int cols, int rows) {
        Map<String, String> envs = new LinkedHashMap<>();
        if (opts.envs() != null) {
            envs.putAll(opts.envs());
        }
        // SDK 的 setDefaultEnv：缺失才补（不降级调用方值）
        envs.putIfAbsent("TERM", DEFAULT_TERM);
        envs.putIfAbsent("LANG", DEFAULT_LANG);
        envs.putIfAbsent("LC_ALL", DEFAULT_LANG);

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("cmd", SHELL_CMD);
        process.put("args", java.util.List.of(SHELL_ARGS));
        process.put("envs", envs);
        if (!opts.cwd().isEmpty()) {
            process.put("cwd", opts.cwd());
        }

        Map<String, Object> pty = new LinkedHashMap<>();
        pty.put("size", size(cols, rows));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("process", process);
        body.put("pty", pty);
        return body;
    }

    /** 重附 PTY 的请求体（{@code ptySelectorRequest}）。 */
    public static Map<String, Object> connectBody(int pid) {
        return Map.of("process", Map.of("pid", pid));
    }

    /** 喂键击的请求体（{@code ptyInputRequest}，数据 base64）。 */
    public static Map<String, Object> inputBody(int pid, byte[] data) {
        return Map.of(
                "process", Map.of("pid", pid),
                "input", Map.of("pty", Base64.getEncoder().encodeToString(data)));
    }

    /** 改窗口的请求体（{@code ptyUpdateRequest}）。 */
    public static Map<String, Object> updateBody(int pid, int cols, int rows) {
        return Map.of("process", Map.of("pid", pid), "pty", Map.of("size", size(cols, rows)));
    }

    /** 发信号的请求体（{@code ptySignalRequest}）。 */
    public static Map<String, Object> signalBody(int pid, String signal) {
        return Map.of("process", Map.of("pid", pid), "signal", signal);
    }

    private static Map<String, Object> size(int cols, int rows) {
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("rows", rows);
        size.put("cols", cols);
        return size;
    }

    /**
     * 解析一帧的 payload（外层是 {@code {"event":{…}}}）；keepalive / 未知事件返回 null。
     */
    public static Event parse(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode event = payload.path("event");
        if (event.isMissingNode() || event.isNull()) {
            return null;
        }
        JsonNode start = event.path("start");
        if (start.has("pid")) {
            return new Event(start.path("pid").asInt(), null, null);
        }
        JsonNode data = event.path("data");
        if (data.has("pty")) {
            String encoded = data.path("pty").asText("");
            byte[] raw = encoded.isEmpty() ? new byte[0] : Base64.getDecoder().decode(encoded);
            return new Event(null, raw, null);
        }
        JsonNode end = event.path("end");
        if (!end.isMissingNode() && !end.isNull()) {
            return new Event(null, null, end);
        }
        return null;
    }

    /**
     * 退出码解析（照 {@code PtyHandle.recordEnd}）：{@code exitCode} → {@code exit_code} →
     * {@code status} 文本（{@code exit status N} / {@code signal N} → 128+N / {@code exited} → 0）→
     * {@code exited:true} → 0；都没有则 absent。
     */
    public static Integer exitCodeOf(JsonNode end) {
        if (end.hasNonNull("exitCode")) {
            return end.path("exitCode").asInt();
        }
        if (end.hasNonNull("exit_code")) {
            return end.path("exit_code").asInt();
        }
        String status = end.path("status").asText("");
        Integer fromStatus = exitCodeFromStatus(status);
        if (fromStatus != null) {
            return fromStatus;
        }
        if (end.path("exited").asBoolean(false)) {
            return 0;
        }
        return null;
    }

    /** 照 {@code exitCodeFromStatus}（{@code pty.go:577-594}）。 */
    public static Integer exitCodeFromStatus(String status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        Matcher exit = EXIT_STATUS.matcher(status);
        if (exit.find()) {
            return Integer.parseInt(exit.group(1));
        }
        Matcher signal = SIGNAL_STATUS.matcher(status);
        if (signal.find()) {
            return 128 + Integer.parseInt(signal.group(1));
        }
        if ("exited".equals(status)) {
            return 0;
        }
        return null;
    }

    /** 一条事件（三态之一 + keepalive/未知 → null）。 */
    public record Event(Integer startPid, byte[] data, JsonNode end) {
    }
}
