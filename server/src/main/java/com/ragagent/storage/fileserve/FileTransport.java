package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.web.ContentTypeByFilename;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 已授权文件的流式响应（收尾批 W5c）。
 *
 * <p>对照 Go {@code internal/filetransport/response.go Serve}——本类不做任何资源
 * 查找或权限推断，边界归调用方。分派到两支：</p>
 * <ul>
 *   <li><b>可 seek</b>（本地盘，Go 的 {@code *os.File}）→ {@code http.ServeContent}
 *       的 Java 移植：Accept-Ranges: bytes、Content-Length、Range→206/416、
 *       If-Match/If-None-Match 预判（零 modtime、无 ETag 形态）。</li>
 *   <li><b>仅流式</b>（Go 的 SDK reader）→ Accept-Ranges: none，不缓冲整个对象
 *       来支持 seek（RFC 9110 §14.2，Go 注释原文）。W5c 只有 local 一支可达；
 *       流式分支保留完整语义。</li>
 * </ul>
 *
 * <p><b>多段 Range（multipart/byteranges）的边界是随机的</b>——Go 侧也随机，
 * A/B 无法逐字节比对，该分支按 RFC 结构实现、整体缓冲、不作字节锚。</p>
 *
 * <p><b>304/416 的头部形态</b>：Go 在写头之后才跑 ServeContent 的预判，304 时
 * <b>删</b> Content-Type/Content-Length（其余头保留）、416 时把 Content-Type
 * <b>改写</b>成 text/plain。Java 侧等价实现为"先判条件，再按分支设置头部集合"，
 * 字节形态相同。</p>
 */
public final class FileTransport {

    private FileTransport() {
    }

    /** 对照 Go {@code filetransport.Options}。 */
    public record Options(String filename, boolean download, String contentType,
            String disposition, String cacheControl, long size) {

        public static Options of(String filename, String cacheControl) {
            return new Options(filename, false, "", "", cacheControl, 0);
        }
    }

    /**
     * 已打开的存储对象：{@code seekable} 在位时走 ServeContent 支路（Go 的
     * ReadSeeker），否则流式支路（bytes 一次性读出）。
     */
    public record OpenedFile(Path seekable, byte[] bytes, long size) {

        public static OpenedFile ofSeekable(Path path, long size) {
            return new OpenedFile(path, null, size);
        }

        public static OpenedFile ofBytes(byte[] data) {
            return new OpenedFile(null, data, data.length);
        }
    }

    /** 对照 Go Serve：关闭 reader、派生 Content-Type/inline、写头、写体（HEAD 跳过）。 */
    public static void serve(HttpServletResponse response, HttpServletRequest request,
            OpenedFile reader, Options options) throws IOException {
        try {
            ContentTypeByFilename.Record byName = ContentTypeByFilename.safe(options.filename());
            boolean inline = byName.inline();
            if (options.download()) {
                inline = false;
            }
            String contentType = byName.contentType();
            if (!options.contentType().isEmpty()) {
                contentType = options.contentType();
            }
            String disposition = inline ? "inline" : "attachment";
            String dispositionValue;
            if (!options.disposition().isEmpty()) {
                dispositionValue = options.disposition();
            } else if (!options.filename().isEmpty()) {
                String base = Path.of(options.filename()).getFileName() == null
                        ? options.filename()
                        : Path.of(options.filename()).getFileName().toString();
                dispositionValue = formatMediaType(disposition, base);
            } else {
                dispositionValue = disposition;
            }

            if (reader.seekable() != null) {
                serveContent(response, request, options, contentType, dispositionValue, reader.seekable(),
                        reader.size());
                return;
            }
            // ── 流式支路（对照 Go 的非 seekable 分支）──
            response.setHeader("Content-Type", contentType);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", dispositionValue);
            if (!options.cacheControl().isEmpty()) {
                response.setHeader("Cache-Control", options.cacheControl());
            }
            response.setHeader("Accept-Ranges", "none");
            if (options.size() > 0) {
                response.setContentLengthLong(options.size());
            }
            response.setStatus(HttpServletResponse.SC_OK);
            if ("HEAD".equals(request.getMethod())) {
                return;
            }
            try (InputStream in = new java.io.ByteArrayInputStream(reader.bytes())) {
                in.transferTo(response.getOutputStream());
            }
        } finally {
            closeReader(reader);
        }
    }

    private static void closeReader(OpenedFile reader) {
        // OpenedFile 持有的是 Path/bytes——没有需要关闭的句柄。
        // Go 的 defer reader.Close() 对应物是下面的 FileChannel try-with-resources。
    }

    // ── http.ServeContent 的移植（零 modtime / 无 ETag 形态）────────────────

    private static void serveContent(HttpServletResponse response, HttpServletRequest request,
            Options options, String contentType, String dispositionValue, Path content, long size)
            throws IOException {
        // checkPreconditions（零 modtime、无 ETag）——本服务从不产出 ETag，所以：
        // If-Match 携带 → 永不匹配 → 412（checkIfMatch condFalse）；
        // If-None-Match 携带 → etagWeakMatch(请求 etag, "") 恒 false →
        //   checkIfNoneMatch 返回 condTrue（"未命中 If-None-Match"）→ **照常 200**
        //   （go1.26 实录：If-None-Match: "x" → 200 全量；304 只在 etag 命中时发生）。
        if (request.getHeader("If-Match") != null) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", dispositionValue);
            if (!options.cacheControl().isEmpty()) {
                response.setHeader("Cache-Control", options.cacheControl());
            }
            response.setStatus(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }

        String rangeReq = request.getHeader("Range");
        int code = HttpServletResponse.SC_OK;
        long sendSize = size;
        List<HttpRange> ranges = null;

        ParseRange parsed = parseRange(rangeReq, size);
        if (parsed.error() != null) {
            if (parsed.noOverlap() && size == 0) {
                // 客户端总带 Range 头时对空文件的宽容（Go 注释原文）→ 200 全量
            } else if (parsed.noOverlap()) {
                // errNoOverlap：先写 Content-Range: bytes *//size 再 416
                response.setHeader("Content-Type", contentType);
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Content-Disposition", dispositionValue);
                if (!options.cacheControl().isEmpty()) {
                    response.setHeader("Cache-Control", options.cacheControl());
                }
                response.setHeader("Content-Range", "bytes */" + size);
                httpErrorOverride(response, request, parsed.error());
                return;
            } else {
                response.setHeader("Content-Type", contentType);
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Content-Disposition", dispositionValue);
                if (!options.cacheControl().isEmpty()) {
                    response.setHeader("Cache-Control", options.cacheControl());
                }
                httpErrorOverride(response, request, parsed.error());
                return;
            }
        } else {
            ranges = parsed.ranges();
        }

        boolean multipart = false;
        byte[] multipartBody = null;
        if (ranges != null && sumRangesSize(ranges) > size) {
            // 各段总长超过文件本体——多半是攻击或呆客户端：忽略 Range（Go 注释原文）
            ranges = null;
        }
        if (ranges != null && ranges.size() == 1) {
            sendSize = ranges.get(0).length();
            code = HttpServletResponse.SC_PARTIAL_CONTENT;
            // RFC 7233 §4.1：单段 206 必须带 Content-Range
            response.setHeader("Content-Range", ranges.get(0).contentRange(size));
        } else if (ranges != null && ranges.size() > 1) {
            String boundary = randomBoundary();
            multipart = true;
            code = HttpServletResponse.SC_PARTIAL_CONTENT;
            multipartBody = buildMultipartBody(boundary, contentType, ranges, size, content);
            sendSize = multipartBody.length;
            contentType = "multipart/byteranges; boundary=" + boundary;
        }

        response.setHeader("Content-Type", contentType);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", dispositionValue);
        if (!options.cacheControl().isEmpty()) {
            response.setHeader("Cache-Control", options.cacheControl());
        }
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Content-Length", Long.toString(sendSize));
        response.setStatus(code);
        if ("HEAD".equals(request.getMethod())) {
            return;
        }
        if (multipart) {
            response.getOutputStream().write(multipartBody);
            response.getOutputStream().flush();
            return;
        }
        long start = ranges != null && ranges.size() == 1 ? ranges.get(0).start() : 0;
        try (FileChannel ch = FileChannel.open(content, StandardOpenOption.READ)) {
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            long remaining = sendSize;
            while (remaining > 0) {
                buf.clear();
                buf.limit((int) Math.min(buf.capacity(), remaining));
                int n = ch.read(buf);
                if (n < 0) {
                    break;
                }
                buf.flip();
                response.getOutputStream().write(buf.array(), 0, n);
                remaining -= n;
            }
            response.getOutputStream().flush();
        }
    }

    /** 对照 Go http.Error（416/解析失败分支）：改写 Content-Type 为 text/plain 并带尾换行体。 */
    private static void httpErrorOverride(HttpServletResponse response, HttpServletRequest request,
            String error) throws IOException {
        response.setHeader("Content-Type", "text/plain; charset=utf-8");
        response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
        if ("HEAD".equals(request.getMethod())) {
            // Go 的 HEAD 会丢体但保留头（net/http 服务器行为）
            return;
        }
        response.getWriter().write(error + "\n");
    }

    // ── Go parseRange 的移植（错误文案逐字）────────────────────────────────

    record ParseRange(List<HttpRange> ranges, String error, boolean noOverlap) {
    }

    private static final String ERR_NO_OVERLAP = "invalid range: failed to overlap";

    static ParseRange parseRange(String s, long size) {
        if (s == null || s.isEmpty()) {
            return new ParseRange(null, null, false);
        }
        String b = "bytes=";
        if (!s.startsWith(b)) {
            return new ParseRange(null, "invalid range", false);
        }
        List<HttpRange> ranges = new ArrayList<>();
        boolean noOverlap = false;
        for (String raw : s.substring(b.length()).split(",")) {
            String ra = raw.trim();
            if (ra.isEmpty()) {
                continue;
            }
            int dash = ra.indexOf('-');
            if (dash < 0) {
                return new ParseRange(null, "invalid range", false);
            }
            String start = ra.substring(0, dash).trim();
            String end = ra.substring(dash + 1).trim();
            long rStart;
            long rLength;
            if (start.isEmpty()) {
                // <suffix-length>：非负整数（RFC 7233 §2.1）
                if (end.isEmpty() || end.charAt(0) == '-') {
                    return new ParseRange(null, "invalid range", false);
                }
                long i;
                try {
                    i = Long.parseLong(end);
                } catch (NumberFormatException e) {
                    return new ParseRange(null, "invalid range", false);
                }
                if (i < 0) {
                    return new ParseRange(null, "invalid range", false);
                }
                if (i > size) {
                    i = size;
                }
                rStart = size - i;
                rLength = size - rStart;
            } else {
                long i;
                try {
                    i = Long.parseLong(start);
                } catch (NumberFormatException e) {
                    return new ParseRange(null, "invalid range", false);
                }
                if (i < 0) {
                    return new ParseRange(null, "invalid range", false);
                }
                if (i >= size) {
                    // 起点越过文件末尾 → 不重叠
                    noOverlap = true;
                    continue;
                }
                rStart = i;
                if (end.isEmpty()) {
                    rLength = size - rStart;
                } else {
                    long j;
                    try {
                        j = Long.parseLong(end);
                    } catch (NumberFormatException e) {
                        return new ParseRange(null, "invalid range", false);
                    }
                    if (rStart > j) {
                        return new ParseRange(null, "invalid range", false);
                    }
                    if (j >= size) {
                        j = size - 1;
                    }
                    rLength = j - rStart + 1;
                }
            }
            ranges.add(new HttpRange(rStart, rLength));
        }
        if (noOverlap && ranges.isEmpty()) {
            return new ParseRange(null, ERR_NO_OVERLAP, true);
        }
        return new ParseRange(ranges, null, noOverlap);
    }

    record HttpRange(long start, long length) {

        /** 对照 Go httpRange.contentRange。 */
        String contentRange(long size) {
            return "bytes " + start + "-" + (start + length - 1) + "/" + size;
        }
    }

    private static long sumRangesSize(List<HttpRange> ranges) {
        long n = 0;
        for (HttpRange r : ranges) {
            n += r.length();
        }
        return n;
    }

    /**
     * 多段 Range → multipart/byteranges 整体缓冲。边界随机（Go 侧也随机，无字节锚）。
     */
    private static byte[] buildMultipartBody(String boundary, String contentType, List<HttpRange> ranges,
            long size, Path content) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (FileChannel ch = FileChannel.open(content, StandardOpenOption.READ)) {
            for (HttpRange ra : ranges) {
                out.writeBytes(partHeader(boundary, contentType, ra, size));
                ch.position(ra.start());
                ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
                long remaining = ra.length();
                while (remaining > 0) {
                    buf.clear();
                    buf.limit((int) Math.min(buf.capacity(), remaining));
                    int n = ch.read(buf);
                    if (n < 0) {
                        break;
                    }
                    buf.flip();
                    out.write(buf.array(), 0, n);
                    remaining -= n;
                }
                out.writeBytes("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.writeBytes(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** 对照 Go ra.mimeHeader（Content-Range + Content-Type 两行）+ 开边框。 */
    private static byte[] partHeader(String boundary, String contentType, HttpRange ra, long size) {
        StringBuilder sb = new StringBuilder();
        sb.append("\r\n--").append(boundary).append("\r\n");
        if (!contentType.isEmpty()) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("Content-Range: ").append(ra.contentRange(size)).append("\r\n\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 随机边界（对照 Go mime/multipart randomBoundary：60-bit hex）。 */
    private static String randomBoundary() {
        return Long.toHexString(new java.security.SecureRandom().nextLong() & 0x0FFFFFFFFFFFFFFFL);
    }

    // ── Go mime.FormatMediaType 的移植（disposition 派生用）─────────────────

    private static final String UPPERHEX = "0123456789ABCDEF";

    /**
     * 对照 Go {@code mime.FormatMediaType(t, map[string]string{"filename": base})}：
     * token 安全 → 裸值；全 ASCII → 引号包裹（转义 " 与 \）；含非 ASCII →
     * RFC 2231 扩展形式 {@code filename*=utf-8''<percent-encoded>}（大写 hex）。
     */
    public static String formatMediaType(String t, String filenameValue) {
        StringBuilder b = new StringBuilder();
        int slash = t.indexOf('/');
        String major = slash < 0 ? t : t.substring(0, slash);
        String minor = slash < 0 ? "" : t.substring(slash + 1);
        if (!isToken(major)) {
            return "";
        }
        b.append(major.toLowerCase(java.util.Locale.ROOT));
        if (!minor.isEmpty()) {
            if (!isToken(minor)) {
                return "";
            }
            b.append('/');
            b.append(minor.toLowerCase(java.util.Locale.ROOT));
        }
        // 单属性 filename；Go 按 map 键排序，这里只有一个
        String attribute = "filename";
        String value = filenameValue == null ? "" : filenameValue;
        b.append(';').append(' ');
        b.append(attribute);
        boolean needEnc = needsEncoding(value);
        if (needEnc) {
            b.append('*'); // RFC 2231 §4
        }
        b.append('=');
        if (needEnc) {
            // ⚠️ Go 按 UTF-8 **字节**迭代（ch := value[index]），非 ASCII 字符按
            // 字节逐个百分号化（数 → %E6%95%B0）。Java 按 char 迭代会把 BMP 字符
            // 的 16 位值直接切 hex——必须先转字节。
            b.append("utf-8''");
            byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte item : raw) {
                int ch = item & 0xFF;
                if (ch <= ' ' || ch >= 0x7F || ch == '*' || ch == '\'' || ch == '%'
                        || isTSpecial((char) ch)) {
                    b.append('%');
                    b.append(UPPERHEX.charAt(ch >> 4));
                    b.append(UPPERHEX.charAt(ch & 0xF));
                } else {
                    b.append((char) ch);
                }
            }
            return b.toString();
        }
        if (isToken(value)) {
            b.append(value);
            return b.toString();
        }
        b.append('"');
        int offset = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                b.append(value, offset, index);
                offset = index;
                b.append('\\');
            }
        }
        b.append(value, offset, value.length());
        b.append('"');
        return b.toString();
    }

    /** 对照 Go mime isTSpecial：{@code ()<>@,;:\"/[]?=}。 */
    private static boolean isTSpecial(char c) {
        return switch (c) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=' -> true;
            default -> false;
        };
    }

    /** 对照 Go mime isTokenChar：US-ASCII 且非控制字符且非 tspecials。 */
    private static boolean isTokenChar(char c) {
        return c > 0x20 && c < 0x7F && !isTSpecial(c);
    }

    private static boolean isToken(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTokenChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 对照 Go mime needsEncoding（encodedword.go L42-49）：\t 被豁免。 */
    private static boolean needsEncoding(String s) {
        for (int i = 0; i < s.length(); i++) {
            char b = s.charAt(i);
            if ((b < ' ' || b > '~') && b != '\t') {
                return true;
            }
        }
        return false;
    }
}
