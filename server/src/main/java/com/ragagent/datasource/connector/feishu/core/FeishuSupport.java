package com.ragagent.datasource.connector.feishu.core;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.ConnectorException;
import com.ragagent.datasource.ConnectorHttp;
import com.ragagent.datasource.domain.DataSourceConfig;

/**
 * 飞书连接器的共享纯函数（对照 Go {@code core/shared.go} 的 L143-301 段：
 * 附件白名单、图片嗅探、配置解析、支持的类型判定、时间戳解析、文件名净化）。
 *
 * <h2>为什么集中在一个类</h2>
 * <p>这些函数 wiki 与 drive 两个连接器都要用，而它们<b>没有任何状态</b>——
 * Go 侧就是包级函数。Java 侧收在一个 final 类里，与
 * {@code SubtreeChildIds} 的处置同族（描述跨连接器共用的规则，不属于任何一侧）。</p>
 *
 * <h2>⚠️ 字节 vs 字符：本项目最贵的翻译陷阱之一</h2>
 * <p>Go 的 {@code string} 按 UTF-8 计长，Java 的 {@code String} 按 UTF-16。
 * 凡是 Go 里拿 {@code len(s)} 做截断/比较的地方，Java 侧<b>必须</b>走
 * {@code getBytes(UTF_8)}——直接当字符数用会让中文尾巴的预算放宽 3 倍
 * （{@link #sanitizeFileName} / {@link #truncateUtf8} 就是这类，与 §9
 * 「{@code maxHeldBytes} 是字节不是字符」同一条）。</p>
 */
public final class FeishuSupport {

    /** 对照 Go {@code FeishuWikiNodeResourceSeparator}。 */
    public static final String FEISHU_WIKI_NODE_RESOURCE_SEPARATOR = ":";

    /** 对照 Go {@code types.ChannelFeishu}（knowledge.source 标签）。 */
    public static final String CHANNEL_FEISHU = "feishu";

    /** 对照 Go {@code types.ChannelFeishuDrive}：云盘文档的独立渠道标签。 */
    public static final String CHANNEL_FEISHU_DRIVE = "feishu_drive";

    /** 对照 Go {@code types.ChannelLarkDrive}。 */
    public static final String CHANNEL_LARK_DRIVE = "lark_drive";

    /**
     * 值得作为<b>独立知识条目</b>灌入的附件扩展名；其余文件（图标、装饰图）跳过。
     *
     * <p>键是小写扩展名（含点）。Go 侧是一个包级 map。</p>
     */
    public static final Map<String, Boolean> PARSEABLE_ATTACHMENT_EXTS = Map.ofEntries(
            Map.entry(".pdf", true), Map.entry(".doc", true), Map.entry(".docx", true),
            Map.entry(".xls", true), Map.entry(".xlsx", true),
            Map.entry(".ppt", true), Map.entry(".pptx", true),
            Map.entry(".txt", true), Map.entry(".md", true), Map.entry(".csv", true));

    /** 对照 Go {@code MinAttachmentBytes}：过滤掉装饰性微文件。 */
    public static final int MIN_ATTACHMENT_BYTES = 2 * 1024;

    /**
     * 凭据解析用的 mapper。
     *
     * <p>必须容忍未知属性：Go 的 {@code json.Unmarshal} 默认忽略未知字段，
     * Jackson 默认抛错——credentials 里多一个前端留下的键就整条配置读不出来
     * （§9「jsonb 回读的 ObjectMapper 要容忍未知属性」是同一条）。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FeishuSupport() {
    }

    // ──────────────────────────────────────────────────────────────────
    // 配置解析（shared.go L172-214）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code ParseFeishuConfig}：从 {@code DataSourceConfig} 的
     * credentials 里解出飞书配置并校验。
     *
     * <p><b>{@code base_url} 刻意保留为显式覆写</b>：早在 lark 连接器存在之前就有
     * 数据源把 "feishu" 连接器指向 {@code open.larksuite.com}，那条路径必须继续可用；
     * 没配时用 region 自己的 host 填上，让下游拿到的 {@code Config.BaseURL} 是具体值。</p>
     *
     * @throws ConnectorException {@code "config is nil"} /
     *                            {@code "<type> app_id and app_secret are required"} /
     *                            base_url 的 SSRF 拒绝
     */
    public static FeishuConfig parseFeishuConfig(DataSourceConfig config, FeishuRegion region) {
        if (config == null) {
            throw new ConnectorException("config is nil");
        }

        FeishuConfig feishuConfig = new FeishuConfig();
        Map<String, Object> credentials = config.getCredentials();
        if (credentials != null) {
            try {
                FeishuConfig parsed = MAPPER.convertValue(credentials, FeishuConfig.class);
                if (parsed != null) {
                    feishuConfig = parsed;
                }
            } catch (IllegalArgumentException e) {
                throw new ConnectorException(
                        "parse " + region.connectorType() + " credentials: " + e.getMessage(), e);
            }
        }

        if (feishuConfig.getAppId().isEmpty() || feishuConfig.getAppSecret().isEmpty()) {
            throw new ConnectorException(
                    region.connectorType() + " app_id and app_secret are required");
        }

        if (feishuConfig.getBaseUrl().isEmpty()) {
            feishuConfig.setBaseUrl(region.openBaseUrl());
        }

        // Timezone 是显示设置（多维表格日期渲染）而不是凭据，所以住在 Settings 里。
        // 空值由 resolveLocation 回落 GMT+8。
        if (feishuConfig.getTimezone().isEmpty() && config.getSettings() != null) {
            Object tz = config.getSettings().get("timezone");
            if (tz instanceof String s) {
                feishuConfig.setTimezone(s.trim());
            }
        }

        ConnectorHttp.validateConnectorBaseUrl(feishuConfig.resolveBaseUrl());
        return feishuConfig;
    }

    // ──────────────────────────────────────────────────────────────────
    // 类型与时间
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code IsSupportedDocType}：这个 obj_type 能不能同步。
     *
     * <p>mindnote 与 slides 没有内容读取 API，一律跳过（它们由
     * {@link FetchTally#skip} 计数，最终体现在汇总日志里）。</p>
     */
    public static boolean isSupportedDocType(String objType) {
        return switch (objType == null ? "" : objType) {
            case "docx", "doc", "sheet", "bitable", "file" -> true;
            default -> false;
        };
    }

    /**
     * 对照 Go {@code ParseFeishuTimestamp}：把飞书 unix 秒时间戳字符串转成时间。
     *
     * <p>Go 返回 {@code time.Time}：<b>空串/解析失败 → 零值</b>，而 {@code "0"} 是<b>合法</b>的
     * （1970-01-01，不是零值）。Java 用 {@code null} 表达 Go 的零值时间，
     * 赋值处经 {@link #orGoZero} 回落。</p>
     *
     * <p>时区用 JVM 默认——Go 的 {@code time.Unix(sec, 0)} 带的是 Local location，
     * 两边表示的是同一个瞬时。</p>
     */
    public static OffsetDateTime parseFeishuTimestamp(String ts) {
        if (ts == null || ts.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(ts)), ZoneId.systemDefault());
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    /**
     * Go 的 {@code time.Time} 是值类型：没有"缺省"这回事，零值也输出
     * {@code "0001-01-01T00:00:00Z"}。Java 侧用 {@code null} 表达"零值/未知"，
     * 赋值到领域对象时统一经这里回落（§9「Jackson 对 null 值根本不调 @JsonSerialize」）。
     */
    public static OffsetDateTime orGoZero(OffsetDateTime v) {
        return v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    // ──────────────────────────────────────────────────────────────────
    // 文件名净化（shared.go L240-285）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code SanitizeFileName}：去掉文件名里的非法字符，并在
     * <b>UTF-8 字符边界</b>上截断。
     *
     * <p>按字节裸截会劈开一个多字节码点（中文 3 字节），产出非法 UTF-8，
     * 被下游的 {@code utf8.ValidString} 校验拒绝。</p>
     *
     * <p><b>扩展名跨越截断被保留</b>：只裁基名，所以
     * {@code "很长的名字….pdf"} 这种超长附件名能保住 {@code ".pdf"}——
     * 下游的文件类型判定就是看扩展名的。</p>
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) {
            return "untitled";
        }
        String result = name
                .replace("/", "_").replace("\\", "_").replace(":", "_").replace("*", "_")
                .replace("?", "_").replace("\"", "_").replace("<", "_").replace(">", "_")
                .replace("|", "_");
        final int maxBytes = 200;
        byte[] all = result.getBytes(StandardCharsets.UTF_8);
        if (all.length <= maxBytes) {
            return result;
        }
        String ext = fileExt(result);
        if (utf8Length(ext) >= maxBytes) {
            // 病态输入：扩展名自己就超预算 → 丢掉它（对照 Go 的 ext = ""）
            ext = "";
        }
        // 对照 Go 的 result[:len(result)-len(ext)]：按字节切掉扩展名那一段。
        // ext 是 result 的后缀，所以按字节回构是安全的。
        String baseStr = new String(all, 0, all.length - utf8Length(ext), StandardCharsets.UTF_8);
        String base = truncateUtf8(baseStr, maxBytes - utf8Length(ext));
        return base + ext;
    }

    /**
     * 对照 Go {@code truncateUTF8}：把 s 截到至多 {@code maxBytes} <b>字节</b>，
     * 且不劈开多字节码点（硬截之后把尾巴上的残缺码点整段丢掉）。
     */
    public static String truncateUtf8(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) {
            return s;
        }
        if (maxBytes <= 0) {
            return "";
        }
        int end = maxBytes;
        while (end > 0) {
            int start = end - 1;
            while (start > 0 && (b[start] & 0xC0) == 0x80) {
                start--; // 回退到该码点的起始字节
            }
            int c = b[start] & 0xFF;
            int len;
            if (c < 0x80) {
                len = 1;
            } else if (c < 0xC0) {
                len = -1; // 不该出现（上面已回退过），保守丢弃
            } else if (c < 0xE0) {
                len = 2;
            } else if (c < 0xF0) {
                len = 3;
            } else if (c < 0xF8) {
                len = 4;
            } else {
                len = -1; // 非法起始字节 → 对照 Go 的 RuneError,size=1 → 丢一个字节
            }
            if (len > 0 && start + len <= end) {
                break;
            }
            end = start;
        }
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    /** 对照 Go {@code filepath.Ext}：最后一个 {@code '.'} 起的后缀；没有点则空串。 */
    public static String fileExt(String path) {
        if (path == null) {
            return "";
        }
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i);
    }

    /** 字符串的 UTF-8 <b>字节</b>长度（对照 Go 的 {@code len(s)}）。 */
    public static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ──────────────────────────────────────────────────────────────────
    // 图片嗅探（shared.go L159-170）
    // ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code SupportedImageExt} 的三返回值。 */
    public record ImageExt(String ext, String contentType, boolean ok) {
    }

    /**
     * 对照 Go {@code SupportedImageExt}：嗅探图片字节，返回 WeKnora 接受的
     * 独立图片知识条目能用的扩展名与 content type（png/jpg/gif ——
     * {@code isValidFileType} 认可的图片集合）。
     *
     * <p>非图片或不支持的格式（webp/bmp…）返回 {@code ok=false}，调用方据此跳过
     * 而不是贴错标签——错误扩展名会在解析阶段失败。<b>即便 ok=false 也返回探测到的
     * content type</b>，好让调用方不用重新嗅一次就能记日志。</p>
     */
    public static ImageExt supportedImageExt(byte[] data) {
        String ct = detectContentType(data);
        return switch (ct) {
            case "image/png" -> new ImageExt(".png", ct, true);
            case "image/jpeg" -> new ImageExt(".jpg", ct, true);
            case "image/gif" -> new ImageExt(".gif", ct, true);
            default -> new ImageExt("", ct, false);
        };
    }

    /** 一条字节签名。 */
    private record Sig(byte[] pattern, String contentType) {
    }

    /**
     * {@code net/http.DetectContentType} 的等价物（只覆盖本项目真实会遇到的签名）。
     *
     * <p>Go 的嗅探算法只读前 512 字节，本实现同样只读前 512 字节。签名表按
     * Go {@code src/net/http/sniff.go} 抄（含 {@code RIFF????WEBPVP} 那个通配模式）；
     * 未命中任何签名时走 Go 的"文本检查"：出现控制字符即
     * {@code application/octet-stream}，否则 {@code text/plain; charset=utf-8}。</p>
     *
     * <p><b>已知差异</b>：Go 的 {@code htmlSig} 掩码表还容忍标签内部的空白与
     * {@code text/xml} 的部分写法；本实现只做显式前缀匹配，未命中的会落到 text/plain。
     * 该字符串只用于日志，不影响任何分支决策（分支只看 png/jpg/gif）。</p>
     */
    public static String detectContentType(byte[] data) {
        byte[] d = data == null ? new byte[0] : data;
        int n = Math.min(d.length, 512);
        if (n == 0) {
            return "text/plain; charset=utf-8";
        }
        for (Sig sig : EXACT_SIGS) {
            if (hasPrefix(d, n, sig.pattern())) {
                return sig.contentType();
            }
        }
        if (n >= 14 && hasPrefix(d, n, ascii("RIFF")) && matchesAt(d, ascii("WEBPVP"), 8)) {
            return "image/webp";
        }
        String lower = new String(d, 0, n, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        for (String sig : HTML_SIGS) {
            if (lower.startsWith(sig)) {
                return "text/html; charset=utf-8";
            }
        }
        if (lower.startsWith("<?xml ")) {
            return "text/xml; charset=utf-8";
        }
        // Go 的文本检查：任何 <=0x08、0x0B、0x0E..0x1A、0x1C..0x1F 的字节即判为二进制
        for (int i = 0; i < n; i++) {
            int b = d[i] & 0xFF;
            if (b <= 0x08 || b == 0x0B || (b >= 0x0E && b <= 0x1A) || (b >= 0x1C && b <= 0x1F)) {
                return "application/octet-stream";
            }
        }
        return "text/plain; charset=utf-8";
    }

    /** 显式转义的字节字面量（源码里不出现裸控制字节）。 */
    private static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final List<Sig> EXACT_SIGS = List.of(
            new Sig(b(0x00, 0x00, 0x01, 0x00), "image/x-icon"),
            new Sig(b(0x00, 0x00, 0x02, 0x00), "image/x-icon"),
            new Sig(ascii("BM"), "image/bmp"),
            new Sig(ascii("GIF87a"), "image/gif"),
            new Sig(ascii("GIF89a"), "image/gif"),
            new Sig(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "image/png"),
            new Sig(b(0xFF, 0xD8, 0xFF), "image/jpeg"),
            new Sig(ascii("%PDF-"), "application/pdf"),
            new Sig(ascii("%!PS-Adobe-"), "application/postscript"),
            new Sig(b(0x50, 0x4B, 0x03, 0x04), "application/zip"));

    private static final List<String> HTML_SIGS = List.of(
            "<!doctype html", "<html", "<head", "<script", "<iframe", "<h1", "<div", "<font",
            "<table", "<a", "<style", "<title", "<b", "<body", "<br", "<p", "<!--");

    private static boolean hasPrefix(byte[] d, int n, byte[] sig) {
        if (sig.length > n) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (d[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAt(byte[] d, byte[] sig, int offset) {
        if (offset + sig.length > d.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (d[offset + i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    // ──────────────────────────────────────────────────────────────────
    // 资源 ID 拆分（wiki: "spaceID:nodeToken"；drive: "folderToken:fileToken"）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code strings.Cut(s, sep)}：在<b>第一个</b>分隔符处切分。
     * 没找到分隔符时第二段是空串（不是 null）。
     */
    public static String[] cut(String s, String sep) {
        if (s == null) {
            return new String[]{"", ""};
        }
        int i = s.indexOf(sep);
        if (i < 0) {
            return new String[]{s, ""};
        }
        return new String[]{s.substring(0, i), s.substring(i + sep.length())};
    }

    // ──────────────────────────────────────────────────────────────────
    // URL 转义（对照 net/url 的 PathEscape / QueryEscape）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code url.PathEscape}（即 {@code escape(s, encodePathSegment)}）。
     *
     * <p>不转义的字符：{@code A-Za-z0-9} 与 {@code - _ . ~}，外加子分隔符里的
     * {@code $ & + : = @}。{@code / ; , ?} 与其余一切（含空格 → {@code %20}）都转义。</p>
     *
     * <p>飞书文档/表格/media token 都是字母数字，实践中两者结果相同；
     * 照抄是为了任何 token 内容都不出岔子（Go 的 {@code url.PathEscape} 与
     * Java 的 {@code URLEncoder} 在 {@code +}、{@code ~}、{@code *} 上确实不同）。</p>
     */
    public static String pathEscape(String s) {
        return escape(s, false);
    }

    /**
     * 对照 Go {@code url.QueryEscape}（即 {@code escape(s, encodeQueryComponent)}）。
     * 不转义的只有 {@code A-Za-z0-9-_.~}；空格写成 {@code '+'}；其余 {@code %XX}（大写十六进制）。
     */
    public static String queryEscape(String s) {
        return escape(s, true);
    }

    private static String escape(String s, boolean query) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int c = value & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else if (query && c == ' ') {
                sb.append('+');
            } else if (!query && (c == '$' || c == '&' || c == '+' || c == ':' || c == '=' || c == '@')) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(HEX[(c >> 4) & 0xF]);
                sb.append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 对照 Go {@code truncate(s, maxLen)}：超长时截断并补 {@code "..."}。
     *
     * <p><b>已知差异</b>：Go 按<b>字节</b>截（{@code s[:maxLen]}），Java 的
     * {@link String#substring} 按<b>字符</b>。该串只进日志与错误分类，
     * 且真实场景里前 500/1000 字节多半是 ASCII 的 JSON 骨架，差异不可见。</p>
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
