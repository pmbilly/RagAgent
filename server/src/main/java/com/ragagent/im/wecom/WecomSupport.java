package com.ragagent.im.wecom;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.ragagent.common.security.SsrfGuard;

/**
 * 企业微信两个模式的公共件（webhook 与长连接共用）——对照 Go 的包级函数：
 * {@code validateEndpointURL} / {@code extraHostFromEndpoint}（webhook_adapter.go L53-81）、
 * {@code downloadFromURL} / {@code isAllowedIMAPIHost} / {@code contentTypeToExt}
 * （L568-705）、{@code decryptAESCBC}（ws_adapter.go L131-181）。
 */
final class WecomSupport {

    static final String DEFAULT_API_BASE_URL = "https://qyapi.weixin.qq.com";
    static final String DEFAULT_WS_ENDPOINT = "wss://openws.work.weixin.qq.com";

    /** 对照 Go {@code allowedIMAPIHosts}：平台自己回在回调里的下载域。 */
    static final List<String> ALLOWED_IM_API_HOSTS = List.of(
            "qyapi.weixin.qq.com",
            "api.weixin.qq.com",
            "open.work.weixin.qq.com",
            "novac2c.cdn.weixin.qq.com",
            "ilinkai.weixin.qq.com");

    private static final Pattern FILENAME_RE =
            Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private WecomSupport() {
    }

    // ── 端点校验 ────────────────────────────────────────────────────────────

    /**
     * 对照 {@code validateEndpointURL}：默认端点放行；自定义端点必须 {@code requiredScheme}
     * 且过 SSRF 校验（校验对象是把 scheme 换成 https 的形式——wss 与 https 同址）。
     */
    static void validateEndpointUrl(String endpoint, String defaultEndpoint, String requiredScheme,
                                    SsrfGuard ssrfGuard) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.equals(defaultEndpoint)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid endpoint URL: " + e.getMessage());
        }
        if (!requiredScheme.equals(uri.getScheme())) {
            throw new IllegalArgumentException("endpoint must use " + requiredScheme + ":// scheme,"
                    + " got " + uri.getScheme() + "://");
        }
        if (ssrfGuard != null && uri.getHost() != null) {
            String check = uri.getScheme().equals("wss") || uri.getScheme().equals("ws")
                    ? "https://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                            + (uri.getPath() == null ? "" : uri.getPath())
                    : endpoint;
            try {
                ssrfGuard.validateURLForSSRF(check);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(e.getMessage()
                        + " (for private deployments on internal networks, add the hostname to"
                        + " SSRF_WHITELIST)");
            }
        }
    }

    /** 对照 {@code extraHostFromEndpoint}：自定义端点的主机名（默认端点 → ""）。 */
    static String extraHostFromEndpoint(String endpoint, String defaultEndpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.equals(defaultEndpoint)) {
            return "";
        }
        try {
            String host = URI.create(endpoint).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    /** 下载结果（字节 + 解析后的文件名）——内部用，最终折成 FileDownloader 的记录。 */
    record Downloaded(byte[] content, String fileName) {
    }

    /**
     * 对照 {@code downloadFromURL}：白名单主机绕过 SSRF 校验；文件名三级推断
     * （Content-Disposition → URL 路径 basename → Content-Type 映射），并做百分号解码。
     */
    static Downloaded downloadFromUrl(HttpClient http, String rawUrl, String fileName,
                                      String extraAllowedHost, SsrfGuard ssrfGuard) throws Exception {
        if (!isAllowedImApiHost(rawUrl, extraAllowedHost)) {
            if (ssrfGuard != null) {
                try {
                    ssrfGuard.validateURLForSSRF(rawUrl);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("URL rejected for security reasons: "
                            + e.getMessage());
                }
            }
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download failed: status=" + response.statusCode());
        }
        String resolved = fileName;

        String disposition = response.headers().firstValue("Content-Disposition").orElse("");
        if (!disposition.isEmpty()) {
            Matcher matcher = FILENAME_RE.matcher(disposition);
            if (matcher.find()) {
                String extracted = matcher.group(1).trim();
                if (!extracted.isEmpty()) {
                    resolved = extracted;
                }
            }
        }
        if (resolved.contains("%")) {
            try {
                String decoded = URLDecoder.decode(resolved, StandardCharsets.UTF_8);
                if (!decoded.isEmpty()) {
                    resolved = decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // 保持原样（照 Go：QueryUnescape 失败则不改）
            }
        }
        if (!resolved.contains(".")) {
            String base = pathBase(rawUrl);
            if (base != null && !base.isEmpty() && !".".equals(base) && !"/".equals(base)
                    && base.contains(".")) {
                try {
                    resolved = URLDecoder.decode(base, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    resolved = base;
                }
            }
        }
        if (!resolved.contains(".")) {
            String ext = contentTypeToExt(
                    response.headers().firstValue("Content-Type").orElse(""));
            if (!ext.isEmpty()) {
                resolved = resolved + "." + ext;
            }
        }
        return new Downloaded(response.body(), resolved);
    }

    /** 对照 {@code isAllowedIMAPIHost}。 */
    static boolean isAllowedImApiHost(String rawUrl, String extraHost) {
        String host;
        try {
            host = URI.create(rawUrl).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (extraHost != null && !extraHost.isEmpty() && host.equals(extraHost)) {
            return true;
        }
        return ALLOWED_IM_API_HOSTS.contains(host);
    }

    /** 对照 {@code contentTypeToExt}。 */
    static String contentTypeToExt(String contentType) {
        String ct = contentType == null ? "" : contentType;
        int idx = ct.indexOf(';');
        if (idx >= 0) {
            ct = ct.substring(0, idx).trim();
        }
        ct = ct.toLowerCase(Locale.ROOT);
        return switch (ct) {
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "text/plain" -> "txt";
            case "text/markdown" -> "md";
            case "text/csv" -> "csv";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "";
        };
    }

    private static String pathBase(String rawUrl) {
        try {
            String path = URI.create(rawUrl).getPath();
            if (path == null) {
                return null;
            }
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 长连接的逐消息文件解密 ──────────────────────────────────────────────

    /**
     * 对照 {@code decryptAESCBC}（ws_adapter.go L131-181）：AES-CBC，IV = key 前 16 字节，
     * key 为逐消息的 base64（43 字符 + "="；失败时退回无填充解码）。
     * <b>填充畸形时原样返回</b>（照 Go 的容错：有些实现不填充）。
     */
    static byte[] decryptAesCbc(byte[] ciphertext, String aesKeyB64) {
        byte[] aesKey;
        try {
            aesKey = Base64.getDecoder().decode(aesKeyB64 + "=");
        } catch (IllegalArgumentException e) {
            try {
                aesKey = Base64.getDecoder().decode(aesKeyB64);
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException("base64 decode aes key: " + e2.getMessage());
            }
        }
        if (aesKey.length < 16) {
            throw new IllegalArgumentException("aes key too short: " + aesKey.length + " bytes");
        }
        if (ciphertext.length < 16) {
            throw new IllegalArgumentException("ciphertext too short: " + ciphertext.length + " bytes");
        }
        if (ciphertext.length % 16 != 0) {
            throw new IllegalArgumentException(
                    "ciphertext not a multiple of block size: " + ciphertext.length + " bytes");
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            plain = cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("new aes cipher: " + e.getMessage(), e);
        }
        if (plain.length == 0) {
            throw new IllegalStateException("empty plaintext after decryption");
        }
        int padLen = plain[plain.length - 1] & 0xFF;
        if (padLen == 0 || padLen > 16 || padLen > plain.length) {
            return plain; // 无有效 PKCS#7 填充 → 原样返回（照 Go）
        }
        for (int i = 0; i < padLen; i++) {
            if ((plain[plain.length - 1 - i] & 0xFF) != padLen) {
                return plain; // 填充不合法 → 原样返回（照 Go）
            }
        }
        return java.util.Arrays.copyOf(plain, plain.length - padLen);
    }
}
