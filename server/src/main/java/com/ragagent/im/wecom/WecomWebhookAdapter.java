package com.ragagent.im.wecom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.FeishuWecomCrypt;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * 企业微信自建应用 webhook 适配器。
 *
 * <p>行为要点：<b>验签</b>走 {@link FeishuWecomCrypt#wecomVerifySignature}（SHA1 排序串接 + 常时比较）；
 * <b>解密</b>自持（AES-CBC + PKCS#7 + 信封 {@code random(16)+len(4)+msg+corpId}，且校 corp_id——
 * 共享的 {@code wecomDecryptMessage} 只解信封不校 corp_id）；URL 验证（GET + echostr 解密回显）；
 * 解析（群聊剥 {@code @} 提及、text/image 两型、其它忽略）；发送（群先试
 * {@code appchat/send} 失败回落 {@code message/send} 直发用户，markdown + agentid）；
 * 取 token（7200s，缓存留 5 分钟余量）；文件下载（http(s) 直链或 {@code media/get}，
 * 文件名依次取 Content-Disposition → URL 路径 → Content-Type 推断）；
 * <b>IM 平台主机白名单</b>（qyapi/api/open.work/novac2c/ilinkai）绕过 SSRF 校验，其余仍校验。</p>
 *
 * <p>文本与 markdown 内容<b>原样</b>发送（不做 {@code FormatIMDisplayContent}）。</p>
 */
public class WecomWebhookAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(WecomWebhookAdapter.class);

    public static final String DEFAULT_API_BASE_URL = "https://qyapi.weixin.qq.com";
    private static final int PKCS7_BLOCK_SIZE = 32;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String corpId;
    private final String token;
    private final String encodingAesKey;
    private final String agentSecret;
    private final int corpAgentId;
    private final String apiBaseUrl;
    private final String extraAllowedHost;
    private final byte[] aesKey;
    private final HttpClient http;
    private final SsrfGuard ssrfGuard;

    private final Object tokenLock = new Object();
    private String tokenCache = "";
    private Instant tokenExpiresAt = Instant.EPOCH;

    public WecomWebhookAdapter(String corpId, String agentSecret, String token,
                               String encodingAesKey, int corpAgentId, String apiBaseUrl,
                               SsrfGuard ssrfGuard) {
        this(corpId, agentSecret, token, encodingAesKey, corpAgentId, apiBaseUrl, ssrfGuard, true);
    }

    /**
     * 测试用：{@code validateEndpoint=false} 跳过端点校验（生产构造强制
     * https + SSRF——本地 stub 是 http，过不了该校验）。
     */
    WecomWebhookAdapter(String corpId, String agentSecret, String token,
                        String encodingAesKey, int corpAgentId, String apiBaseUrl,
                        SsrfGuard ssrfGuard, boolean validateEndpoint) {
        this.corpId = corpId == null ? "" : corpId;
        this.agentSecret = agentSecret == null ? "" : agentSecret;
        this.token = token == null ? "" : token;
        this.encodingAesKey = encodingAesKey == null ? "" : encodingAesKey;
        this.corpAgentId = corpAgentId;
        this.ssrfGuard = ssrfGuard;
        try {
            // 43 字符的 EncodingAESKey + "=" 后解出 32 字节
            this.aesKey = Base64.getDecoder().decode(this.encodingAesKey + "=");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("decode encoding_aes_key: " + e.getMessage());
        }
        String base = apiBaseUrl == null || apiBaseUrl.isEmpty()
                ? DEFAULT_API_BASE_URL : apiBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (validateEndpoint) {
            validateEndpointUrl(base, ssrfGuard);
        }
        this.apiBaseUrl = base;
        this.extraAllowedHost = extraHostFromEndpoint(base);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 默认端点放行；自定义必须 https + SSRF。 */
    static void validateEndpointUrl(String endpoint, SsrfGuard ssrfGuard) {
        WecomSupport.validateEndpointUrl(endpoint, DEFAULT_API_BASE_URL, "https", ssrfGuard);
    }

    /** 自定义端点的主机名（公共件实现）。 */
    static String extraHostFromEndpoint(String endpoint) {
        return WecomSupport.extraHostFromEndpoint(endpoint, DEFAULT_API_BASE_URL);
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    @Override
    public String platform() {
        return ImTypes.PLATFORM_WECOM;
    }

    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        String timestamp = exchange.query("timestamp");
        String nonce = exchange.query("nonce");
        String msgSignature = exchange.query("msg_signature");

        String encrypt;
        if ("GET".equalsIgnoreCase(exchange.method())) {
            encrypt = exchange.query("echostr");
        } else {
            try {
                Document doc = WecomXml.parse(exchange.body());
                encrypt = WecomXml.text(doc, "Encrypt");
            } catch (Exception e) {
                return new AdapterInterfaces.VerifyException("unmarshal xml body: " + e.getMessage());
            }
        }
        boolean ok = FeishuWecomCrypt.wecomVerifySignature(
                token, timestamp, nonce, encrypt, msgSignature);
        return ok ? null : new AdapterInterfaces.VerifyException("invalid signature");
    }

    /** GET + echostr → 解密回显（失败 400）。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.method())) {
            return false;
        }
        String echoStr = exchange.query("echostr");
        if (echoStr == null || echoStr.isEmpty()) {
            return false;
        }
        try {
            exchange.plain(200, "text/plain; charset=utf-8", decryptToString(echoStr));
        } catch (RuntimeException e) {
            log.error("[WeCom] Failed to decrypt echostr: {}", e.toString());
            exchange.plain(400, "text/plain; charset=utf-8", "decrypt failed");
        }
        return true;
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        Document body = WecomXml.parse(exchange.body());
        String encrypt = WecomXml.text(body, "Encrypt");
        byte[] decrypted = decrypt(encrypt);
        Document msg = WecomXml.parse(decrypted);

        String msgType = WecomXml.text(msg, "MsgType");
        String fromUser = WecomXml.text(msg, "FromUserName");
        String msgId = WecomXml.text(msg, "MsgId");
        String chatIdRaw = WecomXml.text(msg, "ChatId");
        boolean isGroup = chatIdRaw != null && !chatIdRaw.isEmpty();

        String chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        String chatId = isGroup ? chatIdRaw : "";

        if ("text".equals(msgType)) {
            String content = WecomXml.text(msg, "Content");
            if (isGroup) {
                content = stripAtMentionBasic(content);
            }
            IncomingMessage incoming = new IncomingMessage();
            incoming.platform = ImTypes.PLATFORM_WECOM;
            incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
            incoming.userId = fromUser;
            incoming.userName = fromUser;
            incoming.chatId = chatId;
            incoming.chatType = chatType;
            incoming.content = content == null ? "" : content.trim();
            incoming.messageId = msgId;
            return incoming;
        }
        if ("image".equals(msgType)) {
            String picUrl = WecomXml.text(msg, "PicUrl");
            String mediaId = WecomXml.text(msg, "MediaId");
            if ((picUrl == null || picUrl.isEmpty()) && (mediaId == null || mediaId.isEmpty())) {
                return null;
            }
            IncomingMessage incoming = new IncomingMessage();
            incoming.platform = ImTypes.PLATFORM_WECOM;
            incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
            incoming.userId = fromUser;
            incoming.userName = fromUser;
            incoming.chatId = chatId;
            incoming.chatType = chatType;
            incoming.messageId = msgId;
            incoming.fileKey = picUrl == null || picUrl.isEmpty() ? mediaId : picUrl;
            incoming.fileName = (msgId == null ? "" : msgId) + ".png";
            return incoming;
        }
        log.info("[WeCom] Ignoring unsupported message type: {}", msgType);
        return null;
    }

    /** 剥群聊 @提及。 */
    static String stripAtMentionBasic(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("@")) {
            return value;
        }
        // 有的客户端在 @提及与正文之间插两个空格
        int doubleSpace = value.indexOf("  ");
        if (doubleSpace > 0) {
            return value.substring(doubleSpace + 2).trim();
        }
        // 启发式：机器人名是 ASCII 词，正文以 "/" 或非 ASCII（CJK）开头
        for (int i = 1; i + 1 < value.length(); i++) {
            if (value.charAt(i) == ' ') {
                char next = value.charAt(i + 1);
                if (next == '/' || next >= 0x80) {
                    return value.substring(i + 1).trim();
                }
            }
        }
        // 兜底：剥掉第一个 @词
        int idx = value.indexOf(' ');
        if (idx > 0) {
            return value.substring(idx + 1).trim();
        }
        return value;
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String accessToken = getAccessToken();

        // 群：先试 appchat/send（经 /cgi-bin/appchat/create 建的群），失败回落直发用户
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)
                && incoming.chatId != null && !incoming.chatId.isEmpty()) {
            try {
                sendToAppChat(accessToken, incoming.chatId, reply);
                return;
            } catch (RuntimeException e) {
                log.debug("[WeCom] appchat/send failed for chat={}, falling back to touser: {}",
                        incoming.chatId, e.toString());
            }
        }
        sendToUser(accessToken, incoming.userId, reply);
    }

    /** {@code /cgi-bin/appchat/send}，markdown。 */
    private void sendToAppChat(String accessToken, String chatId, ReplyMessage reply)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chatid", chatId);
        body.put("msgtype", "markdown");
        body.put("markdown", Map.of("content", reply.content));
        JsonNode result = postApi("/cgi-bin/appchat/send?access_token=" + accessToken, body);
        int errCode = result.path("errcode").asInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("appchat api error: code=" + errCode + " msg="
                    + result.path("errmsg").asText(""));
        }
    }

    /** {@code /cgi-bin/message/send}，markdown + agentid。 */
    private void sendToUser(String accessToken, String userId, ReplyMessage reply)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", userId);
        body.put("msgtype", "markdown");
        body.put("agentid", corpAgentId);
        body.put("markdown", Map.of("content", reply.content));
        JsonNode result = postApi("/cgi-bin/message/send?access_token=" + accessToken, body);
        int errCode = result.path("errcode").asInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("wecom api error: code=" + errCode + " msg="
                    + result.path("errmsg").asText(""));
        }
    }

    /** 取 access_token：7200s，缓存留 5 分钟余量。 */
    String getAccessToken() throws Exception {
        synchronized (tokenLock) {
            if (!tokenCache.isEmpty() && Instant.now().isBefore(tokenExpiresAt)) {
                return tokenCache;
            }
        }
        String url = apiBaseUrl + "/cgi-bin/gettoken?corpid=" + corpId
                + "&corpsecret=" + agentSecret;
        JsonNode result = getJson(url);
        int errCode = result.path("errcode").asInt(0);
        if (errCode != 0) {
            throw new IllegalStateException("get token error: code=" + errCode + " msg="
                    + result.path("errmsg").asText(""));
        }
        String accessToken = result.path("access_token").asText("");
        long expiresIn = result.path("expires_in").asLong(0);
        synchronized (tokenLock) {
            tokenCache = accessToken;
            long ttlSeconds = expiresIn;
            if (ttlSeconds > 300) {
                ttlSeconds -= 300;
            }
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(ttlSeconds, 0));
        }
        return accessToken;
    }

    // ── FileDownloader ──────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("no file key (URL or media_id) in message");
        }
        String fileName = msg.fileName == null || msg.fileName.isEmpty() ? msg.fileKey : msg.fileName;
        if (msg.fileKey.startsWith("http://") || msg.fileKey.startsWith("https://")) {
            return downloadFromUrl(msg.fileKey, fileName);
        }
        String accessToken = getAccessToken();
        String apiUrl = apiBaseUrl + "/cgi-bin/media/get?access_token=" + accessToken
                + "&media_id=" + msg.fileKey;
        return downloadFromUrl(apiUrl, fileName);
    }

    /** 白名单绕过 SSRF；文件名三级推断。 */
    DownloadedFile downloadFromUrl(String rawUrl, String fileName) throws Exception {
        WecomSupport.Downloaded downloaded = WecomSupport.downloadFromUrl(
                http, rawUrl, fileName, extraAllowedHost, ssrfGuard);
        return new DownloadedFile(downloaded.content(), downloaded.fileName());
    }

    /** IM 平台下载域白名单（公共件实现）。 */
    static boolean isAllowedImApiHost(String rawUrl, String extraHost) {
        return WecomSupport.isAllowedImApiHost(rawUrl, extraHost);
    }

    /** Content-Type → 扩展名推断（公共件实现）。 */
    static String contentTypeToExt(String contentType) {
        return WecomSupport.contentTypeToExt(contentType);
    }

    // ── 解密（自持：共享件不校 corp_id） ────────────────────────────────────

    /** AES-CBC + PKCS#7 + 信封拆解 + corp_id 校验。 */
    byte[] decrypt(String encrypted) {
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encrypted);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("base64 decode: " + e.getMessage(), e);
        }
        if (ciphertext.length < 16) {
            throw new IllegalStateException("ciphertext too short");
        }
        if (ciphertext.length % 16 != 0) {
            throw new IllegalStateException("ciphertext length is not a multiple of AES block size");
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            plain = cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed: " + e.getMessage(), e);
        }

        int padLen = plain[plain.length - 1] & 0xFF;
        if (padLen == 0 || padLen > PKCS7_BLOCK_SIZE || padLen > plain.length) {
            throw new IllegalStateException("invalid padding");
        }
        for (int i = 0; i < padLen; i++) {
            if ((plain[plain.length - 1 - i] & 0xFF) != padLen) {
                throw new IllegalStateException("invalid padding");
            }
        }
        byte[] content = java.util.Arrays.copyOf(plain, plain.length - padLen);
        if (content.length < 20) {
            throw new IllegalStateException("plaintext too short");
        }
        long msgLen = ((long) (content[16] & 0xFF) << 24) | ((content[17] & 0xFF) << 16)
                | ((content[18] & 0xFF) << 8) | (content[19] & 0xFF);
        if (content.length < 20 + msgLen) {
            throw new IllegalStateException("message length mismatch");
        }
        String receivedCorpId = new String(content, (int) (20 + msgLen), content.length - (int) (20 + msgLen),
                StandardCharsets.UTF_8);
        if (!receivedCorpId.equals(corpId)) {
            throw new IllegalStateException("corp_id mismatch: expected " + corpId
                    + ", got " + receivedCorpId);
        }
        return java.util.Arrays.copyOfRange(content, 20, (int) (20 + msgLen));
    }

    private String decryptToString(String encrypted) {
        return new String(decrypt(encrypted), StandardCharsets.UTF_8);
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private JsonNode postApi(String pathAndQuery, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + pathAndQuery))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        return raw == null || raw.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        return raw == null || raw.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
    }

    /** 极简 XML 读取（无第三方 XML 依赖，走 JDK DOM）。 */
    static final class WecomXml {

        private WecomXml() {
        }

        static Document parse(byte[] body) throws Exception {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(body == null ? new byte[0] : body));
        }

        /** 取第一个同名元素的文本（缺失 → ""）。 */
        static String text(Document doc, String tag) {
            NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() == 0 || nodes.item(0) == null) {
                return "";
            }
            String value = nodes.item(0).getTextContent();
            return value == null ? "" : value;
        }
    }
}
