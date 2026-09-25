package com.ragagent.sandbox.runtime.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.sandbox.runtime.SandboxException;

/**
 * envd 数据面的**零依赖 Connect 传输**（W5δ 传输层 spike 的骨架，2026-09-25）。
 *
 * <h2>为什么它就能回答"zerodep stdin 无半关闭"这个阻塞点</h2>
 *
 * <p>勘察 Cube 官方 SDK（它自己就是手写 Connect、不依赖 connect-go：{@code connect.go} 全文 +
 * {@code envd.go:90}/{@code pty.go:368}/{@code pty.go:432}）后，PTY 的四个操作**没有一个是双向流**：</p>
 *
 * <table border="1">
 *   <caption>envd PTY 的 RPC 形态（Cube SDK {@code pty.go}）</caption>
 *   <tr><th>操作</th><th>RPC</th><th>方向</th><th>请求体</th></tr>
 *   <tr><td>建/重附 PTY</td><td>{@code POST /process.Process/Start|Connect}</td>
 *       <td><b>服务端流</b></td><td><b>完整</b>的帧化 JSON（发完即止）</td></tr>
 *   <tr><td>喂键击</td><td>{@code POST /process.Process/SendInput}</td>
 *       <td>一元</td><td>裸 JSON</td></tr>
 *   <tr><td>改窗口</td><td>{@code POST /process.Process/Resize}</td><td>一元</td><td>裸 JSON</td></tr>
 * </table>
 *
 * <p>⇒ <b>输入不走"半关闭的 stdin 通道"，而是一条条一元 POST</b>（Go 侧还要用
 * {@code ptyInputCoalescer} 聚合突发，正因为每次都是独立往返）；输出是一条"请求先发完、再读响应体"的
 * 服务端流。**既不需要 TCP 半关闭，也不需要全双工**——{@code java.net.http.HttpClient}
 * （{@code BodyHandlers.ofInputStream()} + 并发一元请求）就是够用的实现面。E2B 侧同理（其 SDK 走
 * connect-go 生成的 {@code Start/Connect/SendInput/Resize}，{@code pty.go:115,158,186,194}，
 * 形态与 Cube 完全一致）。</p>
 *
 * <h2>协议细节（照 Cube SDK 逐条复刻）</h2>
 *
 * <ul>
 *   <li><b>帧</b>（{@code connect.go:35-62}）：5 字节头 = 1 字节 flag + 大端 uint32 长度，后接 payload；
 *       flag {@code 0x01} = 压缩、{@code 0x02} = end-stream（payload 是 {@code {"error":{code,message}}}）；
 *       上限 {@code 64MB}（{@code connect.go:20}）。</li>
 *   <li><b>流式请求/响应</b>：{@code Content-Type: application/connect+json}；
 *       <b>一元请求</b>：{@code application/json} + 裸 JSON 体；两者都带 {@code Connect-Protocol-Version: 1}
 *       （{@code connect.go:17-18}、{@code pty.go:432-437}）。</li>
 *   <li><b>超时</b>：{@code Connect-Timeout-Ms} 是<b>服务端</b>流上限（{@code envd.go:setConnectTimeout}）；
 *       Cube 用 24h（{@code cube_terminal.go:cubeTerminalTimeout}），SDK 的 60s 默认会静默掐掉空闲终端。
 *       客户端 <b>不设</b> HTTP 级超时（{@code cube_terminal.go} 注释：stream 只受 ctx 约束）。</li>
 *   <li><b>认证</b>：{@code Authorization: Basic base64(user + ":")}（{@code envd.go:basicAuthUser}，
 *       空 user 默认 root）；外加 {@code X-Access-Token} 与 traffic-token 头（{@code envd.go:newEnvdRequest}）。</li>
 *   <li><b>压缩</b>：SDK 直接拒绝压缩帧（{@code pty.go:605-607}）——本类同。</li>
 * </ul>
 *
 * <p><b>本类只做传输</b>（spike 范围）：不含会话生命周期、TTL 刷新、PTY 事件语义、provider 控制面
 * （Cube/E2B 的 Connect/建沙箱）；这些属后续 ~1.3k 行执行体批。真机验证清单见
 * {@code docs/w5delta-terminal-spike.md}。</p>
 */
public final class EnvdConnectTransport {

    /** 流式 RPC 的 Content-Type（{@code connect.go:18}）。 */
    public static final String CONTENT_TYPE_STREAM = "application/connect+json";
    /** 一元 RPC 的 Content-Type（{@code pty.go:434}）。 */
    public static final String CONTENT_TYPE_UNARY = "application/json";
    /** {@code Connect-Protocol-Version}（{@code connect.go:17}）。 */
    public static final String PROTOCOL_VERSION = "1";

    /** 压缩帧标志（{@code connect.go:19}）。 */
    public static final byte FLAG_COMPRESSED = 0x01;
    /** end-stream 帧标志（{@code connect.go:18}）。 */
    public static final byte FLAG_END_STREAM = 0x02;

    /** 单帧上限（{@code connect.go:20}）。 */
    public static final int MAX_ENVELOPE_BYTES = 64 * 1024 * 1024;
    /** 错误体读取上限（照 SDK 的 {@code 64<<10}）。 */
    private static final int ERROR_BODY_LIMIT = 64 << 10;

    /** PTY 的 RPC 路径前缀（{@code envd_compat_transport.go:isEnvdDataPlaneRequest}）。 */
    public static final String PROCESS_SERVICE_PREFIX = "/process.Process/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI baseUri;
    private final Map<String, String> headers;

    /**
     * @param client  复用调用方的 HttpClient（<b>不要</b>设 request timeout：流受 Connect-Timeout-Ms 约束）
     * @param baseUri envd 数据面根（scheme://host:port），由 provider 控制面给出
     * @param headers 认证头（照 {@code newEnvdRequest}：Basic user: / X-Access-Token / traffic token）
     */
    public EnvdConnectTransport(HttpClient client, URI baseUri, Map<String, String> headers) {
        this.client = client;
        this.baseUri = baseUri;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 一元调用（照 {@code Pty.unary}）：裸 JSON 体，≥400 抛 {@link SandboxException}。 */
    public JsonNode unary(String method, Object body) throws IOException, InterruptedException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        HttpRequest request = base(method)
                .header("Content-Type", CONTENT_TYPE_UNARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() >= 400) {
                byte[] raw = readLimited(in, ERROR_BODY_LIMIT);
                throw SandboxException.internal(
                        method + " failed: " + extractErrorMessage(raw, response.statusCode()));
            }
            byte[] raw = readLimited(in, ERROR_BODY_LIMIT);
            return raw.length == 0 ? null : MAPPER.readTree(raw);
        }
    }

    /**
     * 流式调用（照 {@code Pty.openStream}）：请求体是<b>完整</b>的帧化 JSON（发完即止），
     * 响应体是帧序列，直到 end-stream 帧或 EOF。
     */
    public StreamingCall openStream(String method, Object body, Duration timeout)
            throws IOException, InterruptedException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        HttpRequest.Builder builder = base(method)
                .header("Content-Type", CONTENT_TYPE_STREAM)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encodeEnvelope(payload)));
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            builder.header("Connect-Timeout-Ms", Long.toString(timeout.toMillis()));
        }

        HttpResponse<InputStream> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            byte[] raw = readLimited(response.body(), ERROR_BODY_LIMIT);
            response.body().close();
            throw SandboxException.internal(
                    method + " failed: " + extractErrorMessage(raw, response.statusCode()));
        }
        return new StreamingCall(method, response.body());
    }

    private HttpRequest.Builder base(String method) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(baseUri.resolve(PROCESS_SERVICE_PREFIX + method))
                .header("Connect-Protocol-Version", PROTOCOL_VERSION);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder;
    }

    /** 帧化（{@code connect.go:35-42}）：1 字节 flag + 大端 uint32 长度 + payload。 */
    public static byte[] encodeEnvelope(byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = 0;
        out[1] = (byte) (payload.length >>> 24);
        out[2] = (byte) (payload.length >>> 16);
        out[3] = (byte) (payload.length >>> 8);
        out[4] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    /** 读一帧（{@code connect.go:44-62}）；流末尾返回 null。 */
    public static Envelope readEnvelope(InputStream in) throws IOException {
        byte[] header = readFullyOrNull(in, 5);
        if (header == null) {
            return null;
        }
        long size = ((long) (header[1] & 0xff) << 24) | ((header[2] & 0xff) << 16)
                | ((header[3] & 0xff) << 8) | (header[4] & 0xff);
        if (size > MAX_ENVELOPE_BYTES) {
            throw new IOException("Connect stream message too large: " + size + " bytes");
        }
        byte[] payload = readFullyOrNull(in, (int) size);
        if (payload == null) {
            throw new IOException("Connect stream truncated inside message of " + size + " bytes");
        }
        return new Envelope(header[0], payload);
    }

    /**
     * end-stream 帧的错误文本（{@code connect.go:64-88}）：无 error → null；
     * 有 code 时拼 {@code code: message}，message 空则回落 {@code Connect stream error}。
     */
    public static String endStreamError(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return null;
        }
        JsonNode error = MAPPER.readTree(payload).path("error");
        if (error.isMissingNode() || error.isNull()) {
            return null;
        }
        String message = error.path("message").asText("").strip();
        if (message.isEmpty()) {
            message = "Connect stream error";
        }
        String code = error.path("code").asText("");
        return code.isEmpty() ? message : code + ": " + message;
    }

    /**
     * 一条服务端流（{@code PtyHandle.readLoop} 的 Java 形态）：{@link #next()} 逐个吐数据帧，
     * 遇 end-stream 返回 null 并把错误文本留在 {@link #endError()}。
     */
    public static final class StreamingCall implements AutoCloseable {

        private final String method;
        private final InputStream body;
        private String endError;
        private boolean closed;

        private StreamingCall(String method, InputStream body) {
            this.method = method;
            this.body = body;
        }

        /** 下一个数据帧的 JSON；流结束（end-stream 或 EOF）返回 null。 */
        public JsonNode next() throws IOException {
            while (true) {
                Envelope envelope = readEnvelope(body);
                if (envelope == null) {
                    return null;
                }
                if ((envelope.flag() & FLAG_COMPRESSED) != 0) {
                    throw SandboxException.internal(
                            method + " failed: unsupported compressed Connect stream message");
                }
                if ((envelope.flag() & FLAG_END_STREAM) != 0) {
                    endError = endStreamError(envelope.payload());
                    return null;
                }
                return MAPPER.readTree(envelope.payload());
            }
        }

        /** end-stream 携带的错误文本；正常结束为 null。 */
        public String endError() {
            return endError;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            body.close();
        }

        public boolean isClosed() {
            return closed;
        }
    }

    /** 一帧。 */
    public record Envelope(byte flag, byte[] payload) {
    }

    private static byte[] readFullyOrNull(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buf, read, length - read);
            if (n < 0) {
                return read == 0 ? null : throwTruncated(read);
            }
            read += n;
        }
        return buf;
    }

    private static byte[] throwTruncated(int read) throws IOException {
        throw new IOException("Connect stream truncated after " + read + " bytes");
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while (total < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - total))) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /** 照 {@code extractErrorMessage}：优先 {@code message} 字段，回落 HTTP 状态文案。 */
    private static String extractErrorMessage(byte[] raw, int status) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            String message = node.path("message").asText("").strip();
            if (!message.isEmpty()) {
                return message;
            }
            String code = node.path("code").asText("").strip();
            if (!code.isEmpty()) {
                return code;
            }
        } catch (IOException ignored) {
            // 非 JSON 体：回落状态文案
        }
        String text = new String(raw == null ? new byte[0] : raw, StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "HTTP " + status : text;
    }

    /** 便捷构造：把 header 依次塞进 map（照 {@code newEnvdRequest} 的头集合）。 */
    public static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Iterator<String> it = java.util.Arrays.asList(keyValues).iterator(); it.hasNext(); ) {
            map.put(it.next(), it.next());
        }
        return map;
    }
}
