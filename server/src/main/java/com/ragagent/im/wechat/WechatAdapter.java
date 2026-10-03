package com.ragagent.im.wechat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * 微信（个人号）iLink 机器人适配器。
 *
 * <p><b>只有长轮询，没有回调</b>：{@code VerifyCallback}/{@code ParseCallback} 明确报
 * "does not support webhook callbacks"、{@code HandleURLVerification} 恒 false。</p>
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>基址 {@code https://ilinkai.weixin.qq.com}、CDN 基址
 *       {@code https://novac2c.cdn.weixin.qq.com/c2c}；</li>
 *   <li>每个请求体都带 {@code base_info:{channel_version:"weknora-1.0.0"}}；</li>
 *   <li>认证头：{@code AuthorizationType: ilink_bot_token} + {@code Authorization: Bearer <token>}
 *       + 随机 {@code X-WECHAT-UIN}（随机 uint32 → 十进制串 → base64）；</li>
 *   <li>发消息 {@code /ilink/bot/sendmessage}：{@code msg{from_user_id:"", to_user_id,
 *       client_id:"weknora_<纳秒>", message_type:2(BOT), message_state:2(FINISH),
 *       item_list:[{type:1(TEXT), text_item:{text}}], context_token}}（context_token 来自消息 extra，
 *       文本<b>原样</b>不做展示格式化）；</li>
 *   <li>输入中 {@code /ilink/bot/sendtyping}：{@code {ilink_user_id, status:1}}；</li>
 *   <li>下载：{@code fileKey} 就是 CDN URL，先过 SSRF 校验；extra 里带 {@code aes_key} 才
 *       AES-128-ECB 解密（否则原样返回）；文件名缺省回落 fileKey。</li>
 * </ul>
 */
public class WechatAdapter implements AdapterInterfaces.Adapter, AdapterInterfaces.FileDownloader {

    public static final String ILINK_BASE_URL = "https://ilinkai.weixin.qq.com";
    public static final String CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";
    public static final String DEFAULT_BOT_TYPE = "3";
    public static final String CHANNEL_VERSION = "weknora-1.0.0";
    static final String UNSUPPORTED_CALLBACK = "WeChat adapter does not support webhook callbacks";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String botToken;
    private final String ilinkBotId;
    private final String baseUrl;
    private final SsrfGuard ssrfGuard;
    private final HttpClient http;

    public WechatAdapter(String botToken, String ilinkBotId, SsrfGuard ssrfGuard) {
        this(botToken, ilinkBotId, ILINK_BASE_URL, ssrfGuard);
    }

    /** {@code baseUrl} 供测试指向本地 stub。 */
    public WechatAdapter(String botToken, String ilinkBotId, String baseUrl,
                         SsrfGuard ssrfGuard) {
        this.botToken = botToken == null ? "" : botToken;
        this.ilinkBotId = ilinkBotId == null ? "" : ilinkBotId;
        String base = baseUrl == null || baseUrl.isBlank() ? ILINK_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_WECHAT;
    }

    /** iLink 走长轮询，回调三面明确不支持。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        return new AdapterInterfaces.VerifyException(UNSUPPORTED_CALLBACK);
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) {
        throw new IllegalArgumentException(UNSUPPORTED_CALLBACK);
    }

    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String contextToken = incoming.extra == null ? ""
                : incoming.extra.getOrDefault("context_token", "");

        ObjectNode textItem = MAPPER.createObjectNode();
        textItem.put("type", 1);
        textItem.set("text_item", MAPPER.createObjectNode()
                .put("text", reply.content == null ? "" : reply.content));
        ArrayNode itemList = MAPPER.createArrayNode();
        itemList.add(textItem);

        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("from_user_id", "");
        msg.put("to_user_id", incoming.userId == null ? "" : incoming.userId);
        msg.put("client_id", "weknora_" + System.nanoTime());
        msg.put("message_type", 2);   // BOT
        msg.put("message_state", 2);  // FINISH
        msg.set("item_list", itemList);
        msg.put("context_token", contextToken);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("msg", msg);
        payload.set("base_info", baseInfo());
        ilinkPost("/ilink/bot/sendmessage", payload);
    }

    /** 输入中状态：{@code {ilink_user_id, status:1}}（当前无调用点）。 */
    public void sendTyping(IncomingMessage incoming) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ilink_user_id", incoming.userId == null ? "" : incoming.userId);
        payload.put("status", 1);   // TYPING
        payload.set("base_info", baseInfo());
        ilinkPost("/ilink/bot/sendtyping", payload);
    }

    /** 拼 CDN 下载地址。 */
    public static String buildCdnDownloadUrl(String encryptQueryParam) {
        return CDN_BASE_URL + "/download?encrypted_query_param="
                + URLEncoder.encode(encryptQueryParam == null ? "" : encryptQueryParam,
                StandardCharsets.UTF_8);
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    /** 下载文件：SSRF 先校验；有 aes_key 才解密。 */
    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("no file URL in message");
        }
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(msg.fileKey);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "file URL rejected by SSRF policy: " + e.getMessage(), e);
            }
        }
        String fileName = msg.fileName == null || msg.fileName.isEmpty()
                ? msg.fileKey : msg.fileName;

        HttpRequest request = HttpRequest.newBuilder(URI.create(msg.fileKey))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download failed: status=" + response.statusCode());
        }
        byte[] data = response.body() == null ? new byte[0] : response.body();

        String aesKeyRaw = msg.extra == null ? "" : msg.extra.getOrDefault("aes_key", "");
        if (aesKeyRaw.isEmpty()) {
            return new DownloadedFile(data, fileName);
        }
        byte[] key = WechatCrypto.parseAesKey(aesKeyRaw);
        return new DownloadedFile(WechatCrypto.decryptAes128Ecb(data, key), fileName);
    }

    // ── iLink 调用 ──────────────────────────────────────────────────────────

    /** 统一认证头 + 非 200 抛。 */
    byte[] ilinkPost(String path, Object payload) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", botToken.isEmpty() ? "" : "Bearer " + botToken)
                .header("X-WECHAT-UIN", generateWeChatUin())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ilink api " + path + " returned status "
                    + response.statusCode() + ": "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        return respBody;
    }

    /** 组 base_info（channel_version 等）。 */
    static ObjectNode baseInfo() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("channel_version", CHANNEL_VERSION);
        return node;
    }

    /** 随机 uint32 → 十进制串 → base64。 */
    static String generateWeChatUin() {
        long value = RANDOM.nextInt() & 0xFFFF_FFFFL;
        return java.util.Base64.getEncoder()
                .encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    /** iLink 机器人标识（供装配/测试观察）。 */
    String ilinkBotId() {
        return ilinkBotId;
    }

}
