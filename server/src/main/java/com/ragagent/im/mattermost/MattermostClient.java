package com.ragagent.im.mattermost;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;

/**
 * Mattermost REST 客户端。
 *
 * <p>基址 {@code <site_url>/api/v4}，认证 {@code Authorization: Bearer <bot_token>}；
 * 构造期校验：site_url 必填 + 必须 http(s) + 过 SSRF、
 * bot_token 必填。</p>
 */
public class MattermostClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int ERR_BODY_LIMIT = 512;

    /** 平台文件元信息。 */
    public record FileInfo(String id, String name, long size) {
    }

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public MattermostClient(String siteUrl, String botToken, SsrfGuard ssrfGuard) {
        String site = siteUrl == null ? "" : siteUrl.trim();
        while (site.endsWith("/")) {
            site = site.substring(0, site.length() - 1);
        }
        if (site.isEmpty()) {
            throw new IllegalArgumentException("site_url is required");
        }
        URI parsed;
        try {
            parsed = URI.create(site);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "invalid site_url: must be a valid http(s) URL", e);
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw new IllegalArgumentException("invalid site_url: must be a valid http(s) URL");
        }
        if (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme())) {
            throw new IllegalArgumentException("invalid site_url: must use http or https");
        }
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(site);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid site_url: " + e.getMessage()
                        + " (for private Mattermost deployments, add the hostname to"
                        + " SSRF_WHITELIST)", e);
            }
        }
        if (botToken == null || botToken.trim().isEmpty()) {
            throw new IllegalArgumentException("bot_token is required");
        }
        this.baseUrl = site + "/api/v4";
        this.token = botToken.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 403 时给"把机器人加进频道"的提示。 */
    public String createPost(String channelId, String rootId, String message) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("channel_id", channelId == null ? "" : channelId);
        body.put("message", message == null ? "" : message);
        if (rootId != null && !rootId.isEmpty()) {
            body.put("root_id", rootId);
        }
        HttpResponse<byte[]> response = send("POST", baseUrl + "/posts",
                MAPPER.writeValueAsBytes(body), true);
        byte[] respBody = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() == 403) {
                throw new IllegalStateException("mattermost create post: 403 forbidden — add the"
                        + " bot user to this Mattermost channel (Channel menu → Members → Add);"
                        + " body=" + truncateForErr(respBody));
            }
            throw new IllegalStateException("mattermost create post: status="
                    + response.statusCode() + " body=" + truncateForErr(respBody));
        }
        JsonNode created = MAPPER.readTree(respBody);
        String id = created.path("id").asText("");
        if (id.isEmpty()) {
            throw new IllegalStateException("mattermost create post: empty id");
        }
        return id;
    }

    /** 返回 root_id（顶层帖为空串）。 */
    public String getPostRootId(String postId) throws Exception {
        HttpResponse<byte[]> response = send("GET", baseUrl + "/posts/" + postId, null, false);
        byte[] respBody = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("mattermost get post: status=" + response.statusCode()
                    + " body=" + truncateForErr(respBody));
        }
        return MAPPER.readTree(respBody).path("root_id").asText("");
    }

    /** PUT /posts/{id}/patch。 */
    public void patchPostMessage(String postId, String message) throws Exception {
        HttpResponse<byte[]> response = send("PUT", baseUrl + "/posts/" + postId + "/patch",
                MAPPER.writeValueAsBytes(Map.of("message", message == null ? "" : message)), true);
        byte[] respBody = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("mattermost patch post: status=" + response.statusCode()
                    + " body=" + truncateForErr(respBody));
        }
    }

    /** 取文件元信息。 */
    public FileInfo getFileInfo(String fileId) throws Exception {
        HttpResponse<byte[]> response = send("GET", baseUrl + "/files/" + fileId + "/info",
                null, false);
        byte[] respBody = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("mattermost file info: status=" + response.statusCode()
                    + " body=" + truncateForErr(respBody));
        }
        JsonNode info = MAPPER.readTree(respBody);
        return new FileInfo(info.path("id").asText(""), info.path("name").asText(""),
                info.path("size").asLong(0));
    }

    /** 下载文件内容（向上层返回字节）。 */
    public byte[] getFileBytes(String fileId) throws Exception {
        HttpResponse<byte[]> response = send("GET", baseUrl + "/files/" + fileId, null, false);
        byte[] respBody = body(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("mattermost get file: status=" + response.statusCode()
                    + " body=" + truncateForErr(respBody));
        }
        return respBody;
    }

    private HttpResponse<byte[]> send(String method, String url, byte[] payload, boolean json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60));
        if (json) {
            builder.header("Content-Type", "application/json");
        }
        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] body(HttpResponse<byte[]> response) {
        return response.body() == null ? new byte[0] : response.body();
    }

    /** 512 字节上限 + "..."。 */
    static String truncateForErr(byte[] raw) {
        String text = new String(raw == null ? new byte[0] : raw, StandardCharsets.UTF_8);
        return text.length() > ERR_BODY_LIMIT ? text.substring(0, ERR_BODY_LIMIT) + "..." : text;
    }

    /** 供装配/测试观察。 */
    String baseUrl() {
        return baseUrl;
    }
}
