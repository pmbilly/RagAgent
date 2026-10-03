package com.ragagent.im.wechat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;

/**
 * 微信扫码登录（iLink 接入）。
 *
 * <h2>协议</h2>
 * <ul>
 *   <li>{@code GET /ilink/bot/get_bot_qrcode?bot_type=3}
 *       → {@code {qrcode, qrcode_img_content}}（{@code qrcode} 空即失败）；</li>
 *   <li>{@code GET /ilink/bot/get_qrcode_status?qrcode=<escaped>}，头
 *       {@code iLink-App-ClientVersion: 1}——<b>长轮询</b>（服务端最长挂 35s）；
 *       状态 {@code wait/scaned/confirmed/expired}；{@code confirmed} 时带
 *       {@code bot_token/ilink_bot_id/ilink_user_id/baseurl}；</li>
 *   <li><b>客户端超时算"还在等"</b>（返回 {@code status="wait"}，不报错）——
 *       用**脱离调用方**的上下文 + 38s 超时，避免 HTTP 请求上下文先超时。</li>
 * </ul>
 */
@Component
public class WechatQRCodeService {

    /** 客户端侧长轮询超时。 */
    public static final long POLL_TIMEOUT_MS = 38_000L;
    /** 请求端点到 iLink 的固定前缀。 */
    static final String QRCODE_PATH = "/ilink/bot/get_bot_qrcode";
    static final String QRCODE_STATUS_PATH = "/ilink/bot/get_qrcode_status";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 扫码结果。 */
    public record QRCodeResult(String qrcodeUrl, String qrcode) {
    }

    /** 登录状态结果。 */
    public record LoginResult(String status, String botToken, String ilinkBotId,
                              String ilinkUserId, String baseUrl) {
        static LoginResult waiting() {
            return new LoginResult("wait", "", "", "", "");
        }
    }

    private final String baseUrl;
    private final long pollTimeoutMs;
    private final HttpClient http;

    /** Spring 装配面：SSRF 守卫可选（iLink 基址是常量，非用户输入）。 */
    @Autowired
    public WechatQRCodeService(ObjectProvider<SsrfGuard> ssrfGuard) {
        this(WechatAdapter.ILINK_BASE_URL, ssrfGuard.getIfAvailable());
    }

    public WechatQRCodeService(String baseUrl, SsrfGuard ssrfGuard) {
        this(baseUrl, ssrfGuard, POLL_TIMEOUT_MS);
    }

    /** {@code pollTimeoutMs} 供测试缩短长轮询超时（生产用 {@link #POLL_TIMEOUT_MS}）。 */
    public WechatQRCodeService(String baseUrl, SsrfGuard ssrfGuard, long pollTimeoutMs) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? WechatAdapter.ILINK_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.pollTimeoutMs = pollTimeoutMs <= 0 ? POLL_TIMEOUT_MS : pollTimeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 拉取登录二维码。 */
    public QRCodeResult getLoginQRCode() throws Exception {
        String url = baseUrl + QRCODE_PATH + "?bot_type="
                + URLEncoder.encode(WechatAdapter.DEFAULT_BOT_TYPE, StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = send(url, Duration.ofSeconds(15), null);
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("qrcode API returned status " + response.statusCode()
                    + ": " + new String(body, StandardCharsets.UTF_8));
        }
        JsonNode result = MAPPER.readTree(body);
        String qrcode = result.path("qrcode").asText("");
        if (qrcode.isEmpty()) {
            throw new IllegalStateException("empty qrcode in response: "
                    + new String(body, StandardCharsets.UTF_8));
        }
        return new QRCodeResult(result.path("qrcode_img_content").asText(""), qrcode);
    }

    /** 轮询扫码状态；超时 → wait（不报错）。 */
    public LoginResult pollQRCodeStatus(String qrcode) throws Exception {
        String url = baseUrl + QRCODE_STATUS_PATH + "?qrcode="
                + URLEncoder.encode(qrcode == null ? "" : qrcode, StandardCharsets.UTF_8);
        HttpResponse<byte[]> response;
        try {
            response = send(url, Duration.ofMillis(pollTimeoutMs), "1");
        } catch (java.net.http.HttpTimeoutException e) {
            // 客户端超时是长轮询的正常形态：当作"还在等"
            return LoginResult.waiting();
        } catch (java.io.IOException e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException
                    || e.getMessage() != null && e.getMessage().contains("timed out")) {
                return LoginResult.waiting();
            }
            throw new IllegalStateException("request qrcode status: " + e.getMessage(), e);
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("qrcode status API returned status "
                    + response.statusCode() + ": " + new String(body, StandardCharsets.UTF_8));
        }
        JsonNode result = MAPPER.readTree(body);
        return new LoginResult(
                result.path("status").asText(""),
                result.path("bot_token").asText(""),
                result.path("ilink_bot_id").asText(""),
                result.path("ilink_user_id").asText(""),
                result.path("baseurl").asText(""));
    }

    private HttpResponse<byte[]> send(String url, Duration timeout, String clientVersion)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET();
        if (clientVersion != null) {
            builder.header("iLink-App-ClientVersion", clientVersion);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 供装配/测试观察。 */
    String baseUrl() {
        return baseUrl;
    }
}
