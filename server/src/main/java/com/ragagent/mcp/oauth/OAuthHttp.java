package com.ragagent.mcp.oauth;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.ragagent.mcp.protocol.McpHttp;

/**
 * OAuth 流程的出站 HTTP（对照 Go {@code secutils.NewSSRFSafeHTTPClient(httpCfg)} 在
 * {@code newHandler} 里的用法：{@code httpCfg.Timeout = 30s}）。
 *
 * <p><b>所有</b> OAuth 出站请求（元数据发现、动态客户端注册、code 交换、刷新）都必须经过
 * 这里——底层复用 {@link McpHttp#send}，即"发送前校验 + 每一跳重定向再校验 + 跨域剥凭据头"。
 * 这正是任务要求的"所有出站请求走 SSRF 校验"。</p>
 *
 * <p>响应体上限 1 MiB（对照 mcp-go {@code maxMetadataBodyBytes}），防止恶意授权服务器
 * 用超大响应把内存打满。</p>
 */
final class OAuthHttp {

    static final int MAX_BODY_BYTES = 1 << 20; // 1 MiB

    static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
    static final String PROTOCOL_VERSION = "2025-03-26";

    private OAuthHttp() {
    }

    /** 一次出站响应（已把 body 读成字符串，流已关闭）。 */
    record Response(int status, String body) {
    }

    /** GET {@code url}；{@code accept: application/json} 与协议版本头按 Go 设置。 */
    static Response get(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION)
                .GET();
        return send(builder.build(), url);
    }

    /** POST {@code body} 到 {@code url}。 */
    static Response post(String url, String contentType, String accept, byte[] body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", contentType)
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(builder.build(), url);
    }

    private static Response send(HttpRequest request, String url) {
        HttpResponse<InputStream> response;
        try {
            response = McpHttp.send(request);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw OAuthProtocolException.of(
                    "failed to send request to " + url + ": " + e.getMessage(), e);
        }
        try (InputStream in = response.body()) {
            byte[] bytes = readLimited(in);
            return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw OAuthProtocolException.of(
                    "failed to read response body from " + url + ": " + e.getMessage(), e);
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                out.write(buffer, 0, read - (total - MAX_BODY_BYTES));
                break;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
