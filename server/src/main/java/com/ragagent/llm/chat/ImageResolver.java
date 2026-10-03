package com.ragagent.llm.chat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.ragagent.common.storage.StorageRuntimeEnv;
import com.ragagent.llm.domain.ChatMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把"存储态的图片路径"转换成各 LLM API 能消费的形态。
 *
 * <p>四类输入：</p>
 * <ul>
 *   <li>{@code data:} URI / {@code http(s)://} —— 原样返回（OpenAI 系），
 *       或解 base64 成原始字节（Ollama 系）；</li>
 *   <li>{@code resource://} / {@code local://} / {@code storage://} —— 走应用层
 *       {@link LocalImageResolver}（带租户存储配置），没有则回退到
 *       {@code LOCAL_STORAGE_BASE_DIR} 环境变量的本地路径；</li>
 *   <li>其余原样返回（LLM API 侧自行报错）。</li>
 * </ul>
 */
public final class ImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageResolver.class);

    /** 本地存储根目录的默认值。 */
    public static final String DEFAULT_LOCAL_STORAGE_BASE_DIR = "/data/files";

    /** 远程图片读取上限（超出部分丢弃，不报错）。 */
    private static final int MAX_REMOTE_IMAGE_BYTES = 20 * 1024 * 1024;

    /** 远程抓取超时。 */
    private static final Duration REMOTE_FETCH_TIMEOUT = Duration.ofSeconds(30);

    /** 远程抓取重定向上限。 */
    private static final int REMOTE_FETCH_MAX_REDIRECTS = 5;

    /**
     * 由应用层在启动时装配：用归属租户的存储配置把 resource:// 或云存储 URL 解析成字节。
     *
     * <p>返回 {@code null} 表示"未解析"；返回空数组是合法的
     * "解析成功但内容为空"。</p>
     */
    @FunctionalInterface
    public interface LocalImageResolver {
        byte[] resolve(String storageUrl);
    }

    private static volatile LocalImageResolver localImageResolver;

    private ImageResolver() {
    }

    /** 装配应用层解析器。 */
    public static void setLocalImageResolver(LocalImageResolver resolver) {
        localImageResolver = resolver;
    }

    // ------------------------------------------------------------------
    // 对外解析
    // ------------------------------------------------------------------

    /**
     * 把存储路径转成 LLM API 可消费的格式。
     * data: URI 与 http(s):// URL 原样返回；resource:// 及各 provider 托管路径
     * 经应用解析器读成字节后转 base64 data URI。
     */
    public static String resolveImageUrlForLlm(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        if (imageUrl.startsWith("data:") || imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return imageUrl;
        }
        if (isApplicationStoredImage(imageUrl)) {
            byte[] data = readLocalStorageBytes(imageUrl);
            if (data != null) {
                // 按内容嗅探 MIME，而不是信文件名
                return "data:" + detectContentType(data) + ";base64," + Base64.getEncoder().encodeToString(data);
            }
        }
        return imageUrl;
    }

    /**
     * 把存储路径转成 Ollama API 要的原始字节。
     * 只认 data: URI 与应用托管路径；http(s) URL 返回 null
     * （远程抓取在 {@link #resolveImageForOllama} 里做，且必须过 SSRF 校验）。
     */
    public static byte[] resolveImageUrlForOllama(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        if (imageUrl.startsWith("data:")) {
            int idx = imageUrl.indexOf(";base64,");
            if (idx < 0) {
                return null;
            }
            try {
                return Base64.getDecoder().decode(imageUrl.substring(idx + 8));
            } catch (IllegalArgumentException e) {
                return null; // base64 解不开
            }
        }
        if (isApplicationStoredImage(imageUrl)) {
            return readLocalStorageBytes(imageUrl);
        }
        return null;
    }

    /**
     * 先走本地/内联解析，
     * 再对 http(s) 做<b>带 SSRF 校验</b>的远程抓取（校验不过或抓取失败一律返回 null）。
     *
     * <p>注意：<b>不检查 HTTP 状态码</b>——4xx/5xx 的响应体同样会被当作图片字节返回
     * （Ollama 侧后续会自行拒绝）。远程读取上限 20MB（超出部分丢弃，不报错）。</p>
     *
     * <p>Ollama 客户端直接调用本方法即可。</p>
     */
    public static byte[] resolveImageForOllama(String imageUrl) {
        byte[] local = resolveImageUrlForOllama(imageUrl);
        if (local != null) {
            return local;
        }
        if (imageUrl == null || !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            return null;
        }
        try {
            LlmTransport.validateUrlForSsrf(imageUrl);
        } catch (RuntimeException e) {
            log.debug("[image-resolve] remote image rejected by SSRF validation: {}", imageUrl);
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .timeout(REMOTE_FETCH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = LlmTransport.send(request, REMOTE_FETCH_MAX_REDIRECTS);
            try (InputStream body = response.body()) {
                return body.readNBytes(MAX_REMOTE_IMAGE_BYTES);
            }
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("[image-resolve] failed to fetch remote image {}: {}", imageUrl, e.toString());
            return null;
        }
    }

    /** 本应用的托管存储协议。 */
    public static boolean isApplicationStoredImage(String imageUrl) {
        if (imageUrl == null) {
            return false;
        }
        return imageUrl.startsWith("resource://")
                || imageUrl.startsWith("local://")
                || imageUrl.startsWith("storage://");
    }

    /**
     * 把 local:// 存储路径解析成磁盘字节。
     *
     * <p>先问应用解析器（它能带上租户配置的 PathPrefix——存储里的 local:// URL 是相对
     * 存储根目录的，不带租户前缀，单纯用环境变量拼路径会漏掉前缀）；没有解析器
     * （如单测环境）则回退到 {@code LOCAL_STORAGE_BASE_DIR}（默认 /data/files）。</p>
     */
    public static byte[] readLocalStorageBytes(String storagePath) {
        if (storagePath == null) {
            return null;
        }
        LocalImageResolver resolver = localImageResolver;
        if (resolver != null) {
            byte[] data = resolver.resolve(storagePath);
            if (data != null) {
                return data;
            }
        }
        String relPath = storagePath.startsWith("local://")
                ? storagePath.substring("local://".length())
                : storagePath;
        // 与 storage 侧 StoragePaths.localStorageBaseDir 同语义（原始串 trim；空 → /data/files），
        // 但直接读 common 的快照 holder——llm 不得依赖 storage（能力层禁直连业务域，包结构守卫）。
        String baseDir = StorageRuntimeEnv.localStorageBaseDir().trim();
        if (baseDir.isEmpty()) {
            baseDir = DEFAULT_LOCAL_STORAGE_BASE_DIR;
        }
        Path localPath;
        try {
            // 用字符串拼接再 normalize，避免 Path.resolve 遇绝对路径直接替换掉 baseDir
            localPath = Paths.get(baseDir + File.separator
                    + relPath.replace('/', File.separatorChar)).normalize();
        } catch (InvalidPathException e) {
            log.warn("[image-resolve] invalid local storage path {}: {}", storagePath, e.toString());
            return null;
        }
        try {
            return Files.readAllBytes(localPath);
        } catch (IOException e) {
            log.warn("[image-resolve] failed to read local file {}: {}", localPath, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 多模态降级
    // ------------------------------------------------------------------

    /**
     * 错误信息是否表示模型不支持图片输入。
     * 判定 = 命中 (multimodal|image|vision) 且命中 (not support|unsupported|400)。
     */
    public static boolean isMultimodalNotSupportedError(Throwable err) {
        if (err == null) {
            return false;
        }
        return isMultimodalNotSupportedMessage(err.getMessage());
    }

    /** 便于调用方只拿到消息字符串时使用。 */
    public static boolean isMultimodalNotSupportedMessage(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String msg = errorMessage.toLowerCase(Locale.ROOT);
        boolean subject = msg.contains("multimodal") || msg.contains("image") || msg.contains("vision");
        boolean unsupported = msg.contains("not support") || msg.contains("unsupported") || msg.contains("400");
        return subject && unsupported;
    }

    /**
     * 返回一份剥掉全部图片的副本
     * （原消息对象不变，显式复制）。
     */
    public static List<ChatMessage> stripImagesFromMessages(List<ChatMessage> messages) {
        if (messages == null) {
            return null;
        }
        List<ChatMessage> cleaned = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            ChatMessage copy = new ChatMessage(msg.getRole(), msg.getContent());
            copy.setMultiContent(msg.getMultiContent());
            copy.setName(msg.getName());
            copy.setToolCallId(msg.getToolCallId());
            copy.setToolCalls(msg.getToolCalls());
            copy.setReasoningContent(msg.getReasoningContent());
            copy.setKind(msg.getKind());
            copy.setImages(null); // 剥掉图片
            cleaned.add(copy);
        }
        return cleaned;
    }

    // ------------------------------------------------------------------
    // MIME 嗅探
    // ------------------------------------------------------------------

    /** 最多看前 512 字节。 */
    public static final int SNIFF_LEN = 512;

    /**
     * 按 WHATWG mimesniff 第 6 节的简化版做内容嗅探：
     * 永远返回一个合法 MIME；认不出来就 {@code application/octet-stream}。
     *
     * <p>只保留"用得上"的那部分签名表——图片、UTF BOM、"&lt;?" 开头的 XML、
     * 以及 textSig/octet-stream 兜底（图片解析路径不会产出
     * PDF / 音频视频 / 字体 / 压缩包等类型）。命中顺序即 {@link #SNIFF_SIGNATURES} 表内顺序。</p>
     */
    public static String detectContentType(byte[] input) {
        byte[] data = input == null ? new byte[0] : input;
        if (data.length > SNIFF_LEN) {
            data = Arrays.copyOf(data, SNIFF_LEN);
        }
        int firstNonWs = 0;
        while (firstNonWs < data.length && isWs(data[firstNonWs])) {
            firstNonWs++;
        }
        for (SniffSig sig : SNIFF_SIGNATURES) {
            String ct = sig.match(data, firstNonWs);
            if (ct != null && !ct.isEmpty()) {
                return ct;
            }
        }
        return "application/octet-stream";
    }

    /** 嗅探用的空白字节判定。 */
    private static boolean isWs(byte b) {
        return b == '\t' || b == '\n' || b == 0x0c || b == '\r' || b == ' ';
    }

    /** 单条嗅探签名。 */
    private interface SniffSig {
        String match(byte[] data, int firstNonWs);
    }

    /** 精确字节签名。 */
    private record ExactSig(byte[] sig, String ct) implements SniffSig {
        @Override
        public String match(byte[] data, int firstNonWs) {
            if (data.length < sig.length) {
                return "";
            }
            for (int i = 0; i < sig.length; i++) {
                if (data[i] != sig[i]) {
                    return "";
                }
            }
            return ct;
        }
    }

    /** 带掩码的字节签名（mask 为 0 的位被忽略；skipWS 时先把前导空白去掉）。 */
    private record MaskedSig(byte[] mask, byte[] pat, boolean skipWs, String ct) implements SniffSig {
        @Override
        public String match(byte[] data, int firstNonWs) {
            if (skipWs) {
                data = Arrays.copyOfRange(data, Math.min(firstNonWs, data.length), data.length);
            }
            if (pat.length != mask.length) {
                return "";
            }
            if (data.length < pat.length) {
                return "";
            }
            for (int i = 0; i < pat.length; i++) {
                if ((data[i] & mask[i]) != pat[i]) {
                    return "";
                }
            }
            return ct;
        }
    }

    /** 前 512 字节内出现二进制控制字符就不算文本。 */
    private static final class TextSig implements SniffSig {
        @Override
        public String match(byte[] data, int firstNonWs) {
            for (int i = firstNonWs; i < data.length; i++) {
                int b = data[i] & 0xFF;
                if (b <= 0x08 || b == 0x0B || (b >= 0x0E && b <= 0x1A) || (b >= 0x1C && b <= 0x1F)) {
                    return "";
                }
            }
            return "text/plain; charset=utf-8";
        }
    }

    private static final SniffSig[] SNIFF_SIGNATURES = {
            // XML
            new MaskedSig(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF), bytes('<', '?', 'x', 'm', 'l'), true,
                    "text/xml; charset=utf-8"),
            // UTF BOM
            new MaskedSig(bytes(0xFF, 0xFF, 0x00, 0x00), bytes(0xFE, 0xFF, 0x00, 0x00), false,
                    "text/plain; charset=utf-16be"),
            new MaskedSig(bytes(0xFF, 0xFF, 0x00, 0x00), bytes(0xFF, 0xFE, 0x00, 0x00), false,
                    "text/plain; charset=utf-16le"),
            new MaskedSig(bytes(0xFF, 0xFF, 0xFF, 0x00), bytes(0xEF, 0xBB, 0xBF, 0x00), false,
                    "text/plain; charset=utf-8"),
            // 图片
            new ExactSig(bytes(0x00, 0x00, 0x01, 0x00), "image/x-icon"),
            new ExactSig(bytes(0x00, 0x00, 0x02, 0x00), "image/x-icon"),
            new ExactSig(bytes('B', 'M'), "image/bmp"),
            new ExactSig(bytes('G', 'I', 'F', '8', '7', 'a'), "image/gif"),
            new ExactSig(bytes('G', 'I', 'F', '8', '9', 'a'), "image/gif"),
            new MaskedSig(
                    bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
                    bytes('R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P'),
                    false, "image/webp"),
            new ExactSig(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), "image/png"),
            new ExactSig(bytes(0xFF, 0xD8, 0xFF), "image/jpeg"),
            // 兜底：文本 / 二进制
            new TextSig(),
    };

    /** 字节字面量工具（Java 没有无符号字节字面量）。 */
    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
