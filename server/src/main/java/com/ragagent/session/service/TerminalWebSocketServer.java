package com.ragagent.session.service;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Enumeration;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 服务端 WebSocket（RFC 6455）最小实现——沙箱终端专用（收尾批 W5d）。
 *
 * <p>对照 Go gorilla/websocket v1.5.3 的 Upgrader/Conn 语义（项目无 spring-websocket
 * 依赖，波 3 browserskill 批的"既有形态"是 HTTP 层手写解析，本类沿用同一打法）：</p>
 * <ul>
 *   <li>升级失败族（对照 Upgrade 的检查序 + returnError）：一律
 *       {@code Sec-Websocket-Version: 13} + http.Error 形态——400/405 纯文本
 *       {@code "Bad Request\n"} / {@code "Method Not Allowed\n"}（含尾换行，12/20 字节）+
 *       {@code text/plain; charset=utf-8} + {@code X-Content-Type-Options: nosniff}；
 *       判定序：Connection 含 "upgrade" → Upgrade 含 "websocket" → GET → 版本 13 →
 *       Key 为 16 字节 base64。CheckOrigin 恒 true（Upgrader 配置——鉴权走 ticket，
 *       严格同源会破坏 vite 代理的 dev 布局，Go 注释原文）；</li>
 *   <li>升级成功：status 101 + {@code Upgrade: websocket} + {@code Connection: Upgrade}
 *       + {@code Sec-WebSocket-Accept}（SHA-1(key+GUID) base64）。无子协议协商
 *       （Upgrader.Subprotocols 空——客户端给什么都不回显）；</li>
 *   <li>帧读写：服务端→客户端的帧**不掩码**（RFC 6455 §5.1），客户端帧必须掩码
 *       （否则按协议错误断连）；控制帧 ≤125 字节、close 帧带 2 字节大端状态码。</li>
 * </ul>
 *
 * <p>传输通道：Servlet 3.1 升级（{@code request.upgrade(TerminalWebSocketUpgradeHandler.class)}）
 * 后的 {@code WebConnection} 裸流——容器层面等价 gorilla 的 Hijack。⚠️ 不能对
 * {@code HttpServletResponse} 裸流直写：Tomcat 视 1xx 响应无实体，101 之后的写入被静默吞掉
 * （W5d 实测，2026-09-21）。bridge 以阻塞读驱动生命周期。</p>
 */
public final class TerminalWebSocketServer {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final int OP_CONT = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** 对照 websocket.ClosePolicyViolation = 1008（open 失败/空闲/吊销的关闭码）。 */
    public static final int CLOSE_POLICY_VIOLATION = 1008;

    private final ServletInputStream in;
    private final ServletOutputStream out;
    private boolean closed;

    private TerminalWebSocketServer(ServletInputStream in, ServletOutputStream out) {
        this.in = in;
        this.out = out;
    }

    // ── 升级 ────────────────────────────────────────────────────────────────

    /**
     * 对照 Upgrader.Upgrade 的**判定段**。升级失败时已写完整 HTTP 错误响应、返回
     * false；判定通过返回 true（调用方随后 {@code writeUpgradeHeaders} +
     * {@code request.upgrade(TerminalWebSocketUpgradeHandler.class)}）。
     *
     * <p>⚠️ Tomcat 实测（W5d，2026-09-21）：{@code setStatus(101)+flushBuffer} 后
     * 101 与 Accept 头能正常到达客户端，但 Tomcat 按 HTTP 语义视 1xx 响应**无实体**，
     * 之后对 response 裸流的写入被静默吞掉（flush 成功、客户端收不到）——容器层面
     * 不等价 gorilla 的 Hijack。正路是 Servlet 3.1 升级：
     * {@code request.upgrade(HttpUpgradeHandler)}，帧走 WebConnection 裸流。</p>
     */
    public static boolean preUpgradeCheck(HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        // 判定序逐条对照 gorilla Upgrade L128-159（错误形态实测钉住：所有失败路径
        // 都带 Sec-Websocket-Version: 13，Go v1.5.3 returnError L82 无条件写）。
        if (!tokenListContains(request.getHeaders("Connection"), "upgrade")
                || !tokenListContains(request.getHeaders("Upgrade"), "websocket")) {
            handshakeError(response, 400);
            return false;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            handshakeError(response, 405);
            return false;
        }
        if (!tokenListContains(request.getHeaders("Sec-WebSocket-Version"), "13")) {
            handshakeError(response, 400);
            return false;
        }
        String key = request.getHeader("Sec-WebSocket-Key");
        if (!isValidChallengeKey(key)) {
            handshakeError(response, 400);
            return false;
        }
        // CheckOrigin 恒 true（对照 terminalUpgrader.CheckOrigin）
        return true;
    }

    /**
     * 写 101 响应行与升级头（不 flush——Tomcat 在 service 返回、容器接管升级时提交）。
     * 容器会补它自己的 Date；这是既有的头级容器差异（非正文契约）。
     */
    public static void writeUpgradeHeaders(HttpServletResponse response, String challengeKey) {
        response.setStatus(101);
        response.setHeader("Upgrade", "websocket");
        response.setHeader("Connection", "Upgrade");
        response.setHeader("Sec-WebSocket-Accept", computeAcceptKey(challengeKey));
    }

    /** 用 WebConnection 裸流装配连接（由 TerminalWebSocketUpgradeHandler.init 调用）。 */
    public static TerminalWebSocketServer wrap(ServletInputStream in, ServletOutputStream out) {
        return new TerminalWebSocketServer(in, out);
    }

    /** 对照 returnError：版本头 + http.Error（纯文本 + 尾换行 + nosniff）。 */
    private static void handshakeError(HttpServletResponse response, int status)
            throws IOException {
        response.setHeader("Sec-Websocket-Version", "13");
        response.setStatus(status);
        response.setContentType("text/plain; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        byte[] body = (StatusText.of(status) + "\n").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.flushBuffer();
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }

    /** http.StatusText(400)="Bad Request"、(405)="Method Not Allowed"。 */
    private static final class StatusText {
        static String of(int status) {
            return switch (status) {
                case 400 -> "Bad Request";
                case 405 -> "Method Not Allowed";
                default -> "Status " + status;
            };
        }
    }

    /** 对照 tokenListContainsValue：逗号分 token、trim、大小写不敏感。 */
    private static boolean tokenListContains(java.util.Enumeration<String> headers,
            String value) {
        if (headers == null) {
            return false;
        }
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) {
                continue;
            }
            for (String token : header.split(",")) {
                if (value.equalsIgnoreCase(token.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 对照 isValidChallengeKey：16 字节 base64（Std 编码、非空）。 */
    private static boolean isValidChallengeKey(String key) {
        if (key == null) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(key).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 对照 computeAcceptKey：base64(SHA-1(key + GUID))。 */
    static String computeAcceptKey(String challengeKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((challengeKey + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 帧写出 ──────────────────────────────────────────────────────────────

    /**
     * 写一帧（对照 conn.WriteMessage：服务端帧不掩码；len≤125 短形、≤65535 扩展 16 位、
     * 其余 64 位）。
     */
    public synchronized void writeMessage(int opcode, byte[] payload) throws IOException {
        if (closed) {
            throw new EOFException("websocket: closed");
        }
        out.write(0x80 | opcode); // FIN=1
        int len = payload == null ? 0 : payload.length;
        if (len <= 125) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((long) len >> shift) & 0xFF);
            }
        }
        if (len > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /**
     * 写控制帧（对照 WriteControl：控制帧负载 ≤125 字节）。
     */
    public synchronized void writeControl(int opcode, byte[] payload) throws IOException {
        writeMessage(opcode, payload);
    }

    /** 对照 FormatCloseMessage(ClosePolicyViolation, code) + WriteControl(CloseMessage)。 */
    public void writeClose(int closeCode, String reason) {
        try {
            byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((closeCode >> 8) & 0xFF);
            payload[1] = (byte) (closeCode & 0xFF);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            writeControl(OP_CLOSE, payload);
        } catch (IOException e) {
            // 对照 Go 的 `_ = conn.WriteControl(...)`：失败忽略（连接已死）
        }
    }

    /** 对照 conn.Close()（关闭底层流）。 */
    public synchronized void close() {
        closed = true;
        try {
            out.close();
        } catch (IOException ignored) {
            // 流已被容器关闭
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ── 帧读入 ──────────────────────────────────────────────────────────────

    /** 读到的客户端帧（客户端帧必须掩码，读侧解掩码）。 */
    public record Frame(int opcode, byte[] payload) {}

    /**
     * 阻塞读一帧。对照 conn.ReadMessage：客户端帧 FIN/分片按 RFC 合并到 opcode
     * （终端协议里客户端只发无分片的完整帧；分片输入按协议错误断连——
     * gorilla 的 ReadMessage 同样拒绝交错分片）。
     *
     * @throws EOFException 连接关闭（正常或传输失败——bridge 统一按 client 断开处理）
     * @throws IOException  协议错误（未掩码/超长控制帧）
     */
    public Frame readMessage() throws IOException {
        int opcode = -1;
        java.io.ByteArrayOutputStream message = new java.io.ByteArrayOutputStream();
        while (true) {
            int b1 = in.read();
            if (b1 == -1) {
                throw new EOFException("websocket: closed");
            }
            int b2 = in.read();
            if (b2 == -1) {
                throw new EOFException("websocket: closed");
            }
            boolean fin = (b1 & 0x80) != 0;
            int frameOpcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long length = b2 & 0x7F;
            if (length == 126) {
                length = ((long) in.read() << 8) | in.read();
                if (length < 0) {
                    throw new EOFException("websocket: closed");
                }
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    int b = in.read();
                    if (b == -1) {
                        throw new EOFException("websocket: closed");
                    }
                    length = (length << 8) | b;
                }
            }
            if (length > Integer.MAX_VALUE - 8) {
                throw new IOException("websocket: frame too large");
            }
            byte[] mask = null;
            if (masked) {
                mask = readExact(4);
                if (mask == null) {
                    throw new EOFException("websocket: closed");
                }
            }
            byte[] payload = new byte[(int) length];
            if (length > 0) {
                byte[] chunk = readExact((int) length);
                if (chunk == null) {
                    throw new EOFException("websocket: closed");
                }
                payload = chunk;
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
            }
            switch (frameOpcode) {
                case OP_CLOSE -> {
                    // 响应 close 确认由 close() 时的 TCP 关闭承担（gorilla 默认回 close 帧；
                    // bridge 的读循环收到 close 即退出、统一 teardown）
                    throw new EOFException("websocket: close received");
                }
                case OP_PING -> writeControl(OP_PONG, payload);
                case OP_PONG -> {
                    // 对照 SetPongHandler：读侧的 deadline 刷新由 bridge 在返回后统一做；
                    // 单独的 pong 帧不进入消息流
                    if (fin) {
                        continue;
                    }
                    throw new IOException("websocket: fragmented control frame");
                }
                case OP_TEXT, OP_BINARY, OP_CONT -> {
                    if (opcode == -1) {
                        if (frameOpcode == OP_CONT) {
                            throw new IOException("websocket: unexpected continuation");
                        }
                        opcode = frameOpcode;
                    }
                    message.writeBytes(payload);
                    if (fin) {
                        return new Frame(opcode, message.toByteArray());
                    }
                }
                default -> throw new IOException("websocket: unknown opcode " + frameOpcode);
            }
        }
    }

    private byte[] readExact(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int read = in.read(buf, off, n - off);
            if (read == -1) {
                return null;
            }
            off += read;
        }
        return buf;
    }
}
