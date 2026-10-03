package com.ragagent.datasource.connector.ima;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * IMA 连接器的**纯函数与常量**（身份键、媒体类型枚举、扩展名/MIME 映射、
 * 文件名净化）。
 *
 * <p>集中在一个不可实例化的类里：测试 {@code ImaFormatsTest} 逐条钉住这些
 * 纯函数——放在连接器主体里会让它们被 HTTP 逻辑淹没。</p>
 *
 * <h2>⚠️ 身份契约：{@link #logicalKey}</h2>
 * <p>它算出来的字符串会作为 {@code FetchedItem.external_id} 落进
 * {@code knowledges.external_id}，必须与既有数据逐字节一致：分隔符是
 * {@code \x1f}（单字节），哈希对象是
 * {@code "%s\x1f%s\x1f%s"} 的 UTF-8 字节，取 SHA-256 十六进制小写的前 32 位。
 * 测试里钉了 14 组真值，任何"顺手改成 {@code |} 分隔"或"改成
 * {@code HexFormat} 大写"的改动都会被立刻抓到。</p>
 *
 * <h2>{@code sanitizeFileName} 与飞书的那一份**写法不同**</h2>
 * <p>飞书连接器的同名函数带"保留扩展名"的逻辑，IMA 与语雀都没有——
 * 三份刻意各自独立，不要统一。</p>
 */
public final class ImaFormats {

    /** 用户只配 host 时回落的地址。 */
    public static final String DEFAULT_BASE_URL = "https://ima.qq.com";

    private ImaFormats() {
    }

    // ── 媒体类型枚举 ───────────────────────────────────────────────────────

    public static final int MEDIA_TYPE_PDF = 1;
    public static final int MEDIA_TYPE_WEB = 2;
    public static final int MEDIA_TYPE_WORD = 3;
    public static final int MEDIA_TYPE_PPT = 4;
    public static final int MEDIA_TYPE_EXCEL = 5;
    public static final int MEDIA_TYPE_MP_ARTICLE = 6;
    public static final int MEDIA_TYPE_MARKDOWN = 7;
    public static final int MEDIA_TYPE_IMAGE = 9;
    public static final int MEDIA_TYPE_NOTE = 11;
    public static final int MEDIA_TYPE_AI_SESSION = 12;
    public static final int MEDIA_TYPE_TXT = 13;
    public static final int MEDIA_TYPE_XMIND = 14;
    public static final int MEDIA_TYPE_AUDIO = 15;
    public static final int MEDIA_TYPE_VIDEO = 16;
    public static final int MEDIA_TYPE_HTML = 20;
    public static final int MEDIA_TYPE_EPUB = 21;

    // ── 纯函数 ────────────────────────────────────────────────────────────

    /**
     * IMA 媒体类型 → 文件扩展名。
     * 无固定扩展名的类型（网页 / 笔记 / AI 会话 / 视频解析）回空串。
     */
    public static String extensionForMediaType(int mediaType) {
        switch (mediaType) {
            case MEDIA_TYPE_PDF: return "pdf";
            case MEDIA_TYPE_WORD: return "docx";
            case MEDIA_TYPE_PPT: return "pptx";
            case MEDIA_TYPE_EXCEL: return "xlsx";
            case MEDIA_TYPE_MARKDOWN: return "md";
            case MEDIA_TYPE_IMAGE: return "png";
            case MEDIA_TYPE_TXT: return "txt";
            case MEDIA_TYPE_XMIND: return "xmind";
            case MEDIA_TYPE_AUDIO: return "mp3";
            case MEDIA_TYPE_HTML: return "html";
            case MEDIA_TYPE_EPUB: return "epub";
            default: return "";
        }
    }

    /**
     * 响应 {@code Content-Type} → 扩展名，
     * 忽略 {@code "; charset=..."} 尾巴。
     *
     * <p>IMA 把所有图片都报成 {@code media_type=9}，只有下载响应的
     * Content-Type 才能分辨 JPEG 与 PNG——把 JPEG 命名成 {@code .png} 会让下游
     * 基于扩展名的类型判定出错。</p>
     */
    public static String extensionForContentType(String contentType) {
        String mediaType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        int semi = mediaType.indexOf(';');
        if (semi >= 0) {
            mediaType = mediaType.substring(0, semi).trim();
        }
        switch (mediaType) {
            case "image/png": return "png";
            case "image/jpeg":
            case "image/jpg": return "jpg";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "image/bmp": return "bmp";
            default: return "";
        }
    }

    /**
     * 扩展名 → 规范 MIME。
     * 下载响应的 Content-Type 缺失（或被报成 {@code application/octet-stream}）时用它补齐。
     */
    public static String mimeForExtension(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (e.startsWith(".")) {
            e = e.substring(1);
        }
        switch (e) {
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "md": return "text/markdown";
            case "txt": return "text/plain";
            case "html":
            case "htm": return "text/html";
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "epub": return "application/epub+zip";
            case "xmind": return "application/x-xmind";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/x-m4a";
            case "wav": return "audio/wav";
            case "aac": return "audio/aac";
            default: return "application/octet-stream";
        }
    }

    /**
     * IMA 根本不提供读该类型内容的途径。
     * AI 会话只有 session_id、视频解析在桌面端之外连加都加不进去。
     * <b>笔记不在此列</b>——它走 note 命名空间读，见 {@code fetchNote}。
     */
    public static boolean isSkippableMediaType(int mediaType) {
        return mediaType == MEDIA_TYPE_AI_SESSION || mediaType == MEDIA_TYPE_VIDEO;
    }

    /**
     * 空白判定（含 U+00A0 与 U+3000）。
     *
     * <p>{@code String.isBlank()} / {@code strip()} 走 {@code Character.isWhitespace}
     * （<b>不含</b> U+00A0），所以这里显式补上。凭据字段实际只可能是 ASCII，
     * 但这条判据同时服务笔记正文的"空内容"判定。</p>
     */
    public static boolean isGoBlank(String s) {
        if (s == null) {
            return true;
        }
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp)
                || cp == 0x00A0 || cp == 0x3000);
    }

    /** 限定在 ASCII 字符集内的 SHA-256 十六进制（小写）。 */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * 能扛住"同名替换"的稳定身份（IMA 在服务端就地替换
     * 同名文件时会重新分配 media_id）。
     *
     * <p>键是 {@code (kb_id, parent_folder_id, title)} 的短 SHA-256 十六进制，
     * 输出 {@code "ima_" + 前 32 个十六进制字符}（共 36 字符）——塞得进 Postgres 的
     * {@code varchar(64)}，对任何现实规模的 KB 也足够抗碰撞。</p>
     *
     * <p><b>media_type 刻意不进键</b>：{@code get_knowledge_list} 不返回它
     * （只有 {@code get_media_info} 才给），折进去意味着 IMA 哪天开始填这个字段时
     * 每个 external_id 都会变——读起来就像"所有文档被删除又重新添加"。</p>
     */
    public static String logicalKey(String kbId, String parentFolderId, String title) {
        String composite = nullSafe(kbId) + '\u001f' + nullSafe(parentFolderId) + '\u001f' + nullSafe(title);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须带 SHA-256；走到这里说明运行环境坏了。
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] hash = digest.digest(composite.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(36);
        sb.append("ima_");
        for (int i = 0; i < 16; i++) {
            sb.append(HEX_DIGITS[(hash[i] >> 4) & 0xF]);
            sb.append(HEX_DIGITS[hash[i] & 0xF]);
        }
        return sb.toString();
    }

    /** null 与空串等价。 */
    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 剔掉文件系统敌意字符、在安全 UTF-8 边界上截断。
     *
     * <p><b>长度上限是 200 字节而不是 200 个字符</b>。
     * 中文标题按字符数截会放宽三倍；截断点之后必须剥掉残缺的多字节序列，
     * 保证结果始终是合法 UTF-8。</p>
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '/': case '\\': case ':': case '*': case '?':
                case '"': case '<': case '>': case '|':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
            }
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        final int maxBytes = 200;
        if (bytes.length > maxBytes) {
            bytes = truncateToValidUtf8(bytes, maxBytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 把字节前缀切成"仍是合法 UTF-8"的最长前缀（一个码点最多 4 字节，
     * 所以要剥的尾巴不超过 3 字节）。
     */
    static byte[] truncateToValidUtf8(byte[] bytes, int maxBytes) {
        int end = Math.min(maxBytes, bytes.length);
        while (end > 0) {
            if (isValidUtf8Prefix(bytes, end)) {
                break;
            }
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(bytes, 0, out, 0, end);
        return out;
    }

    private static boolean isValidUtf8Prefix(byte[] bytes, int end) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes, 0, end));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
