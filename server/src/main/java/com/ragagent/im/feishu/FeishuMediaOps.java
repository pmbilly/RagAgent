package com.ragagent.im.feishu;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书媒体协作者（对照 Go adapter.go 的图片/文件段，自 {@link FeishuAdapter} 机械搬出）：
 * 卡片 markdown 外链图下载→上传换 image_key（失败降级纯链接）、GetMessageResource
 * 文件下载。image_key 缓存（IMAGE_KEY_CACHE）与 MD 正则留门面（测试直摸/跨簇共用）。
 * 持门面回引取 appId/region/ssrfGuard/http/token。
 */
final class FeishuMediaOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuMediaOps.class);

    private final FeishuAdapter service;

    FeishuMediaOps(FeishuAdapter service) {
        this.service = service;
    }

    /** 上传飞书前对图片的下载上限（飞书限制 10MB，留余量）。 */
    static final int MAX_IMAGE_BYTES = 10 << 20;
    // ── 卡片 markdown 图片 → image_key ──────────────────────────────────────

    /** 对照 {@code resolveMarkdownImages}：失败降级为纯链接（不让整次更新失败）。 */
    String resolveMarkdownImages(String accessToken, String content) {
        if (content == null || !content.contains("![")) {
            return content;
        }
        Matcher matcher = FeishuAdapter.MD_IMAGE_RE.matcher(content);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String rawUrl = matcher.group(2);
            String imageKey = null;
            try {
                imageKey = imageKeyForUrl(accessToken, rawUrl);
            } catch (Exception e) {
                log.warn("[{}] image upload failed, degrading to link: url={} err={}",
                        service.region.label(), rawUrl, e.toString());
            }
            String replacement;
            if (imageKey == null || imageKey.isEmpty()) {
                String label = alt == null || alt.isEmpty() ? service.region.imageFallbackLabel() : alt;
                replacement = "[" + label + "](" + rawUrl + ")";
            } else {
                replacement = "![" + alt + "](" + imageKey + ")";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 对照 {@code imageKeyForURL}：缓存按 app 作用域 + URL 去 query。 */
    String imageKeyForUrl(String accessToken, String rawUrl) throws Exception {
        String key = service.appId + "\u0000" + imageCacheKey(rawUrl);
        String cached = FeishuAdapter.IMAGE_KEY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String imageKey = uploadImageFromUrl(accessToken, rawUrl);
        FeishuAdapter.IMAGE_KEY_CACHE.put(key, imageKey);
        return imageKey;
    }

    /** 对照 {@code imageCacheKey}：去掉 query（签名 URL 的签名每次都变）。 */
    static String imageCacheKey(String rawUrl) {
        int idx = rawUrl.indexOf('?');
        return idx >= 0 ? rawUrl.substring(0, idx) : rawUrl;
    }

    /** 对照 {@code uploadImageFromURL}：下载（限 10MB）→ multipart 上传 → image_key。 */
    String uploadImageFromUrl(String accessToken, String rawUrl) throws Exception {
        if (service.ssrfGuard != null) {
            service.ssrfGuard.validateURLForSSRF(rawUrl);
        }
        HttpRequest downloadRequest = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> downloadResponse = service.http.send(downloadRequest,
                HttpResponse.BodyHandlers.ofByteArray());
        if (downloadResponse.statusCode() != 200) {
            throw new IllegalStateException("download image: status="
                    + downloadResponse.statusCode());
        }
        byte[] data = downloadResponse.body() == null ? new byte[0] : downloadResponse.body();
        if (data.length == 0) {
            throw new IllegalStateException("empty image body");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("image exceeds " + MAX_IMAGE_BYTES + " bytes");
        }

        String boundary = "----WeKnoraBoundary" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image_type\"\r\n\r\n"
                + "message\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        body.write(prefix.getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(service.api("/open-apis/im/v1/images")))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<byte[]> response = service.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = FeishuAdapter.readTree(response.body());
        int code = result.path("code").asInt(0);
        if (code != 0) {
            throw new IllegalStateException("upload image error: code=" + code + " msg="
                    + result.path("msg").asText(""));
        }
        String imageKey = result.path("data").path("image_key").asText("");
        if (imageKey.isEmpty()) {
            throw new IllegalStateException("upload image: empty image_key");
        }
        return imageKey;
    }
    // ── FileDownloader ──────────────────────────────────────────────────────

    public AdapterInterfaces.FileDownloader.DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()
                || msg.messageId == null || msg.messageId.isEmpty()) {
            throw new IllegalArgumentException("file_key and message_id are required");
        }
        if (!FeishuAdapter.safePathParam(msg.messageId) || !FeishuAdapter.safePathParam(msg.fileKey)) {
            throw new IllegalArgumentException("invalid message_id or file_key format");
        }
        String accessToken = service.getTenantAccessToken();
        String resourceType = ImTypes.MESSAGE_TYPE_IMAGE.equals(msg.messageType)
                ? "image" : "file";

        String url = service.api("/open-apis/im/v1/messages/" + msg.messageId + "/resources/"
                + msg.fileKey + "?type=" + resourceType);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = service.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download file failed: status="
                    + response.statusCode());
        }

        String fileName = msg.fileName == null ? "" : msg.fileName;
        if (fileName.isEmpty()) {
            String disposition = response.headers().firstValue("Content-Disposition").orElse("");
            int idx = disposition.indexOf("filename=");
            if (idx >= 0) {
                fileName = disposition.substring(idx + "filename=".length()).trim()
                        .replaceAll("^\"|\"$", "").trim();
            }
        }
        if (fileName.isEmpty()) {
            fileName = msg.fileKey;
        }
        return new AdapterInterfaces.FileDownloader.DownloadedFile(response.body(), fileName);
    }
}
