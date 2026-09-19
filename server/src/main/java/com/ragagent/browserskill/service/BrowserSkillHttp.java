package com.ragagent.browserskill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * browserskill 的 HTTP 写出与限长读取助手——对照 Go 标准库语义：
 * <ul>
 *   <li>{@link #plainError} = {@code http.Error}：状态码 + 消息+"\n" +
 *       Content-Type "text/plain; charset=utf-8"（Go 原样带空格）+
 *       X-Content-Type-Options: nosniff。</li>
 *   <li>{@link #writeJson} = {@code json.NewEncoder(w).Encode(...)}：尾部补 "\n"
 *       （Encoder 语义——golden bs-authz-pair-ok.json 的字节以换行结尾）。
 *       Content-Type 由调用方先 setHeader（authorize/internal 是标准库形态
 *       "application/json"，无 charset）。</li>
 *   <li>{@link #readJsonLimited} = MaxBytesReader + json.Decoder：超限/解码失败
 *       统一返回 null（调用方按各自的 400 文案处理）；解码器只读所需字节，
 *       尾随垃圾不触发超限——与 Go 的 Decoder 行为一致。</li>
 *   <li>{@link #readAllLimited} = io.ReadAll(MaxBytesReader(...))：超限返回 null。</li>
 *   <li>{@link #formatHttpTime} = http.TimeFormat（GMT、零填充——Java 的
 *       RFC_1123_DATE_TIME 日期不补零，不可用）。</li>
 * </ul>
 * JSON 序列化一律用应用统一 mapper（GoJsonEscapes/GoWriterJsonFactory 全局生效）。
 */
public final class BrowserSkillHttp {

    private static final DateTimeFormatter HTTP_TIME =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);

    private BrowserSkillHttp() {}

    /** 对照 http.Error：纯文本错误体（消息 + 换行） */
    public static void plainError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body);
        }
    }

    /**
     * 对照 json.NewEncoder(...).Encode(...)：Content-Type 沿用调用方先前
     * setHeader 的值（缺省 application/json），字节尾随一个 "\n"。
     */
    public static void writeJson(HttpServletResponse response, int status, ObjectMapper mapper,
                                 Object payload) throws IOException {
        response.setStatus(status);
        String contentType = response.getHeader("Content-Type");
        if (contentType == null) {
            response.setHeader("Content-Type", "application/json");
        }
        byte[] body = mapper.writeValueAsBytes(payload);
        byte[] withNewline = new byte[body.length + 1];
        System.arraycopy(body, 0, withNewline, 0, body.length);
        withNewline[body.length] = '\n';
        response.setContentLength(withNewline.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(withNewline);
        }
    }

    /** 二进制写出（对照 http.ServeFile 的字节流；Content-Length 显式化） */
    public static void writeBytes(HttpServletResponse response, int status, byte[] body)
            throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body);
        }
    }

    /** 限长流：读到超限即抛 IOException（对照 MaxBytesReader 的错误语义） */
    private static final class LimitedStream extends InputStream {
        private final InputStream delegate;
        private int remaining;

        LimitedStream(InputStream delegate, int limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("http: request body too large");
            }
            int b = delegate.read();
            if (b != -1) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("http: request body too large");
            }
            int n = delegate.read(buffer, offset, Math.min(length, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }

    /**
     * 对照 MaxBytesReader + json.Decoder.Decode：超限或解码失败返回 null；
     * 空 body（EOF）也按解码失败处理（Go 的 Decode 返回 EOF 错误）。
     */
    public static <T> T readJsonLimited(HttpServletRequest request, int limit, ObjectMapper mapper,
                                        Class<T> type) throws IOException {
        try (InputStream in = new LimitedStream(request.getInputStream(), limit)) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            // 超限与解码失败同出口（对照 Go：Decode != nil → 400）
            return null;
        }
    }

    /** 对照 io.ReadAll(MaxBytesReader(...))：全量读到 limit，超限返回 null */
    public static byte[] readAllLimited(HttpServletRequest request, int limit) throws IOException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            if (buf.size() + n > limit) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** http.TimeFormat 输出（GMT、零填充） */
    public static String formatHttpTime(OffsetDateTime t) {
        return HTTP_TIME.format(t.toInstant());
    }

    /** 解析 http.TimeFormat / RFC 1123（If-Modified-Since 条件请求用） */
    public static OffsetDateTime parseHttpTime(String value) {
        try {
            return OffsetDateTime.parse(value,
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                            .withZone(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
