package com.ragagent.im.dingtalk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;

/**
 * 钉钉适配器。
 *
 * <p>三面：{@code Adapter}（验签/解析/发送）+ {@code StreamSender}（AI 卡片流式，可选）
 * + {@code FileDownloader}（downloadCode → 临时 URL → 取字节）。</p>
 *
 * <h2>行为要点</h2>
 * <ul>
 *   <li>验签：{@code Timestamp}/{@code Sign} 头，时间窗 ±3600 秒，
 *       期望签名 = Base64(HMAC-SHA256({@code <timestamp>\n<clientSecret>}, clientSecret))
 *       （走共享 {@link ImAdapterVerify#dingtalkExpectedSignature}），定长比较；
 *       clientSecret 为空跳过；</li>
 *   <li>解析：群聊判据 {@code conversationType == "2"}；userId 取 senderStaffId 回落 senderId；
 *       四段链 <b>richText → file/picture → audio → text</b>（按此顺序）；
 *       richText 取文本片段（trim + 换行连接）+ 首图 downloadCode（原图优先回落预览码），
 *       多图时追加"仅处理第一张"提示；图片无 fileName → {@code <msgId>.png}；
 *       audio 取 recognition 作为文本；</li>
 *   <li>下载：downloadCode 换 {@code /v1.0/robot/messageFiles/download} 的临时 downloadUrl
 *       → 宿主机白名单（{@code *.aliyuncs.com} / {@code *.dingtalk.com}）放行、其余走 SSRF 校验
 *       → GET 字节；（robotCode 取回调里的、回落 client_id）；</li>
 *   <li>发送：优先回调里的 {@code sessionWebhook}（markdown 直发，先过 SSRF 校验），
 *       否则 OpenAPI（群 {@code groupMessages/send} + openConversationId / 私聊
 *       {@code oToMessages/batchSend} + userIds；{@code msgKey=sampleMarkdown}，
 *       xml/hmac 头 {@code x-acs-dingtalk-access-token}）；</li>
 *   <li>流式：配了 {@code card_template_id} 才建 **AI 卡片**（
 *       {@code /v1.0/card/instances/createAndDeliver}：outTrackId=UUID、callbackType=STREAM、
 *       {@code dtv1.card//IM_GROUP.<cid>} 或 {@code dtv1.card//IM_ROBOT.<uid>}），
 *       逐次 {@code PUT /v1.0/card/streaming}（isFull=true、isFinalize、key=content）
 *       ——**500ms 节流**；EndStream 时 isFinalize=true；没有卡片就退回 sessionWebhook
 *       整段发出（再没有就 OpenAPI）；</li>
 *   <li>流 ID 是确定性的 {@code dt:<userId>:<messageId>}；</li>
 *   <li>token：{@code /v1.0/oauth2/accessToken}（appKey/appSecret）→ accessToken/expireIn，
 *       缓存留 5 分钟余量。</li>
 * </ul>
 *
 * <h2>实现差异（备案）</h2>
 * <ul>
 *   <li>孤儿流回收是惰性的（清理 5 分钟前的条目），在每次
 *       {@link #startStream} 时顺带清理；</li>
 *   <li>OpenAPI 回复统一走可注入的 {@code apiBaseUrl}（生产为
 *       {@code https://api.dingtalk.com}，测试可指向本地 stub）。</li>
 * </ul>
 */
public class DingtalkAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.StreamSender, AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(DingtalkAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String DEFAULT_API_BASE_URL = "https://api.dingtalk.com";
    /** 群聊判据。 */
    static final String CONV_TYPE_GROUP = "2";
    /** 连续卡片更新的最小间隔。 */
    static final long MIN_CARD_UPDATE_INTERVAL_MS = 500;
    static final long STREAM_ORPHAN_TTL_MS = 5 * 60 * 1000L;
    /** 临时下载链接的放行后缀。 */
    static final List<String> ALLOWED_DOWNLOAD_HOST_SUFFIXES =
            List.of(".aliyuncs.com", ".dingtalk.com");

    /** 全局流表，key = stream ID。 */
    static final Map<String, StreamState> STREAMS = new ConcurrentHashMap<>();

    /** 一条流的状态。 */
    static final class StreamState {
        final long createdAt = System.currentTimeMillis();
        final Object lock = new Object();
        final StringBuilder content = new StringBuilder();
        String sessionWebhook = "";
        String outTrackId = "";
        long lastUpdate;
    }

    private final String clientId;
    private final String clientSecret;
    private final String cardTemplateId;
    private final String apiBaseUrl;
    private final SsrfGuard ssrfGuard;
    private final HttpClient http;

    private final Object tokenLock = new Object();
    private String token = "";
    private Instant tokenExpiresAt = Instant.EPOCH;

    public DingtalkAdapter(String clientId, String clientSecret, String cardTemplateId,
                           String apiBaseUrl, SsrfGuard ssrfGuard) {
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.cardTemplateId = cardTemplateId == null ? "" : cardTemplateId;
        String base = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? DEFAULT_API_BASE_URL : apiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.apiBaseUrl = base;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_DINGTALK;
    }

    /** 钉钉不走 URL 挑战。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    /** Sign/Timestamp 头 + ±1 小时时间窗 + 定长比较。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        if (clientSecret.isEmpty()) {
            return null;
        }
        String timestamp = exchange.header("Timestamp");
        String sign = exchange.header("Sign");
        if (timestamp == null || timestamp.isEmpty() || sign == null || sign.isEmpty()) {
            return new AdapterInterfaces.VerifyException("missing timestamp or sign header");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return new AdapterInterfaces.VerifyException("invalid timestamp: " + e.getMessage());
        }
        long diff = Instant.now().toEpochMilli() - ts;
        if (diff > 3600L * 1000 || diff < -3600L * 1000) {
            return new AdapterInterfaces.VerifyException("timestamp expired");
        }
        String expected = ImAdapterVerify.dingtalkExpectedSignature(clientSecret, timestamp);
        if (!MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return new AdapterInterfaces.VerifyException("invalid signature");
        }
        return null;
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        JsonNode msg;
        try {
            msg = MAPPER.readTree(exchange.body() == null ? new byte[0] : exchange.body());
        } catch (Exception e) {
            throw new IllegalArgumentException("parse callback: " + e.getMessage(), e);
        }
        if (msg == null || msg.isMissingNode() || msg.isNull()) {
            return null;
        }
        return parseCallbackMessage(msg);
    }

    /** 四段链：richText → file/picture → audio → text。 */
    static IncomingMessage parseCallbackMessage(JsonNode msg) {
        String conversationType = msg.path("conversationType").asText("");
        boolean isGroup = CONV_TYPE_GROUP.equals(conversationType);
        String chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        String chatId = isGroup ? msg.path("conversationId").asText("") : "";

        String userId = msg.path("senderStaffId").asText("");
        if (userId.isEmpty()) {
            userId = msg.path("senderId").asText("");
        }
        String msgType = msg.path("msgtype").asText("");
        String msgId = msg.path("msgId").asText("");
        String robotCode = msg.path("robotCode").asText("");
        JsonNode content = msg.path("content");

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_DINGTALK;
        incoming.userId = userId;
        incoming.userName = msg.path("senderNick").asText("");
        incoming.chatId = chatId;
        incoming.chatType = chatType;
        incoming.messageId = msgId;
        incoming.extra.put("session_webhook", msg.path("sessionWebhook").asText(""));
        incoming.extra.put("raw_msgtype", msgType);

        RichText rich = parseRichTextContent(msgType, content);
        if (rich != null) {
            applyRichText(incoming, rich, msgId, robotCode);
            return incoming;
        }
        FileContent file = parseFileContent(msgType, content);
        if (file != null) {
            incoming.messageType = file.messageType();
            incoming.fileName = defaultFileName(file.messageType(), file.fileName(), msgId);
            incoming.fileKey = file.downloadCode();
            incoming.extra.put("robot_code", robotCode);
            return incoming;
        }
        String audioText = parseAudioContent(msgType, content);
        if (audioText != null) {
            incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
            incoming.content = audioText;
            return incoming;
        }
        incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        incoming.content = msg.path("text").path("content").asText("").trim();
        return incoming;
    }

    private static final class RichText {
        String text = "";
        String downloadCode = "";
        int pictureCount;
    }

    /** msgtype=richText 才认；否则 null。 */
    static RichText parseRichTextContent(String msgType, JsonNode content) {
        if (msgType == null || !msgType.trim().equalsIgnoreCase("richText")) {
            return null;
        }
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        JsonNode richText = content.path("richText");
        if (!richText.isArray()) {
            return null;
        }
        List<String> textParts = new ArrayList<>();
        RichText result = new RichText();
        for (JsonNode item : richText) {
            String text = item.path("text").asText("").trim();
            if (!text.isEmpty()) {
                textParts.add(text);
            }
            String code = pictureDownloadCode(item);
            if (code.isEmpty()) {
                continue;
            }
            result.pictureCount++;
            if (result.downloadCode.isEmpty()) {
                result.downloadCode = code;
            }
        }
        result.text = String.join("\n", textParts);
        return result;
    }

    /** type=picture 才认；原图码优先回落预览码。 */
    static String pictureDownloadCode(JsonNode item) {
        if (!item.path("type").asText("").trim().equalsIgnoreCase("picture")) {
            return "";
        }
        String code = item.path("downloadCode").asText("");
        return code.isEmpty() ? item.path("pictureDownloadCode").asText("") : code;
    }

    /** msgtype=audio 且 recognition 非空。 */
    static String parseAudioContent(String msgType, JsonNode content) {
        if (msgType == null || !msgType.trim().equalsIgnoreCase("audio")) {
            return null;
        }
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        String text = content.path("recognition").asText("").trim();
        return text.isEmpty() ? null : text;
    }

    /** 文件/图片的解析结果。 */
    record FileContent(String messageType, String fileName, String downloadCode) {
    }

    /** 只认 file/picture；图片不带 fileName（服务端补扩展名）。 */
    static FileContent parseFileContent(String msgType, JsonNode content) {
        String messageType;
        switch (msgType == null ? "" : msgType) {
            case "file" -> messageType = ImTypes.MESSAGE_TYPE_FILE;
            case "picture" -> messageType = ImTypes.MESSAGE_TYPE_IMAGE;
            default -> {
                return null;
            }
        }
        String downloadCode = content == null ? "" : content.path("downloadCode").asText("");
        if (downloadCode.isEmpty()) {
            downloadCode = content == null ? "" : content.path("pictureDownloadCode").asText("");
        }
        if (downloadCode.isEmpty()) {
            return null;
        }
        String fileName = content == null ? "" : content.path("fileName").asText("");
        if (ImTypes.MESSAGE_TYPE_IMAGE.equals(messageType)) {
            fileName = "";
        }
        return new FileContent(messageType, fileName, downloadCode);
    }

    /** 有图 → 图片消息（取首图），否则文本；多图附提示。 */
    static void applyRichText(IncomingMessage incoming, RichText rich, String msgId,
                              String robotCode) {
        incoming.content = rich.text;
        incoming.messageType = rich.downloadCode.isEmpty()
                ? ImTypes.MESSAGE_TYPE_TEXT : ImTypes.MESSAGE_TYPE_IMAGE;

        if (rich.pictureCount == 0) {
            return;
        }
        incoming.extra.put("rich_text_picture_count", String.valueOf(rich.pictureCount));
        if (rich.downloadCode.isEmpty()) {
            return;
        }
        incoming.fileKey = rich.downloadCode;
        incoming.fileName = defaultFileName(ImTypes.MESSAGE_TYPE_IMAGE, "", msgId);
        incoming.extra.put("robot_code", robotCode);
        if (rich.pictureCount > 1) {
            incoming.content = appendDroppedPictureHint(incoming.content, rich.pictureCount);
        }
    }

    static String appendDroppedPictureHint(String text, int pictureCount) {
        String hint = String.format("（该消息共 %d 张图片，当前仅处理第一张）", pictureCount);
        if (text == null || text.trim().isEmpty()) {
            return hint;
        }
        return text + "\n" + hint;
    }

    /** 图片用 {@code <msgId>.png}，其余回落 msgId。 */
    static String defaultFileName(String messageType, String fileName, String msgId) {
        if (fileName != null && !fileName.isEmpty()) {
            return fileName;
        }
        if (ImTypes.MESSAGE_TYPE_IMAGE.equals(messageType)) {
            return msgId + ".png";
        }
        return msgId;
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    /** downloadCode → 临时 URL → 白名单/SSRF 校验 → 字节。 */
    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        String downloadCode = msg.fileKey == null ? "" : msg.fileKey;
        if (downloadCode.isEmpty()) {
            throw new IllegalArgumentException("no downloadCode in message");
        }
        String robotCode = msg.extra == null ? "" : msg.extra.getOrDefault("robot_code", "");
        if (robotCode.isEmpty()) {
            robotCode = clientId;
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("robotCode", robotCode);
        body.put("downloadCode", downloadCode);
        byte[] respBody = dingtalkApi("POST", "/v1.0/robot/messageFiles/download", body);

        JsonNode parsed = MAPPER.readTree(respBody);
        String downloadUrl = parsed.path("downloadUrl").asText("");
        if (downloadUrl.isEmpty()) {
            throw new IllegalStateException("download response has no downloadUrl: "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        validateFileDownloadUrl(downloadUrl);

        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("download file returned " + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        return new DownloadedFile(response.body(),
                msg.fileName == null ? "" : msg.fileName);
    }

    /** 宿主机白名单放行，其余走 SSRF 校验。 */
    void validateFileDownloadUrl(String rawUrl) {
        if (isAllowedDownloadHost(rawUrl)) {
            return;
        }
        if (ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(rawUrl);
        }
    }

    static boolean isAllowedDownloadHost(String rawUrl) {
        String host;
        try {
            host = URI.create(rawUrl).getHost();
        } catch (RuntimeException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : ALLOWED_DOWNLOAD_HOST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    /** sessionWebhook 优先，否则 OpenAPI。 */
    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String content = ThinkDisplay.formatIMDisplayContent(
                reply.content == null ? "" : reply.content, ThinkDisplay.STREAM_DISPLAY_FINAL);
        String sessionWebhook = incoming.extra == null ? ""
                : incoming.extra.getOrDefault("session_webhook", "");
        if (!sessionWebhook.isEmpty()) {
            replyViaSessionWebhook(sessionWebhook, content);
            return;
        }
        replyViaOpenApi(incoming, content);
    }

    /** markdown 直发（先过 SSRF 校验）。 */
    void replyViaSessionWebhook(String webhookUrl, String content) throws Exception {
        if (ssrfGuard != null) {
            try {
                ssrfGuard.validateURLForSSRF(webhookUrl);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "dingtalk sessionWebhook rejected by SSRF policy: " + e.getMessage(), e);
            }
        }
        ObjectNode markdown = MAPPER.createObjectNode();
        markdown.put("title", "Reply");
        markdown.put("text", content);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("msgtype", "markdown");
        body.set("markdown", markdown);

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk sessionWebhook returned "
                    + response.statusCode() + ": "
                    + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
    }

    /** 群 groupMessages/send，私聊 oToMessages/batchSend。 */
    void replyViaOpenApi(IncomingMessage incoming, String content) throws Exception {
        String accessToken = getAccessToken();
        ObjectNode msgParam = MAPPER.createObjectNode();
        msgParam.put("title", "Reply");
        msgParam.put("text", content);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("robotCode", clientId);
        body.put("msgKey", "sampleMarkdown");
        body.put("msgParam", MAPPER.writeValueAsString(msgParam));

        String path;
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)) {
            path = "/v1.0/robot/groupMessages/send";
            body.put("openConversationId", incoming.chatId == null ? "" : incoming.chatId);
        } else {
            path = "/v1.0/robot/oToMessages/batchSend";
            ArrayNode userIds = MAPPER.createArrayNode();
            userIds.add(incoming.userId == null ? "" : incoming.userId);
            body.set("userIds", userIds);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .header("x-acs-dingtalk-access-token", accessToken)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk OpenAPI returned " + response.statusCode()
                    + ": " + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
    }

    /** 缓存留 5 分钟余量。 */
    String getAccessToken() throws Exception {
        synchronized (tokenLock) {
            if (!token.isEmpty() && Instant.now().isBefore(tokenExpiresAt)) {
                return token;
            }
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("appKey", clientId);
        body.put("appSecret", clientSecret);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiBaseUrl + "/v1.0/oauth2/accessToken"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk accessToken returned " + response.statusCode()
                    + ": " + new String(response.body() == null ? new byte[0] : response.body(),
                    StandardCharsets.UTF_8));
        }
        JsonNode result = MAPPER.readTree(response.body());
        String accessToken = result.path("accessToken").asText("");
        if (accessToken.isEmpty()) {
            throw new IllegalStateException("empty access token from dingtalk");
        }
        long expireIn = result.path("expireIn").asLong(0);
        synchronized (tokenLock) {
            token = accessToken;
            tokenExpiresAt = Instant.now().plusSeconds(expireIn - 300);
        }
        return accessToken;
    }

    // ── AI 卡片与流式 ───────────────────────────────────────────────────────

    /** 带 token 的通用调用（非 200 抛）。 */
    byte[] dingtalkApi(String method, String path, Object body) throws Exception {
        String accessToken = getAccessToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .header("Content-Type", "application/json")
                .header("x-acs-dingtalk-access-token", accessToken)
                .timeout(Duration.ofSeconds(15))
                .method(method, HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("dingtalk API " + path + " returned "
                    + response.statusCode() + ": "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        return respBody;
    }

    /** outTrackId 即流 ID 之外的卡片句柄。 */
    String createAndDeliverCard(IncomingMessage incoming) throws Exception {
        String outTrackId = UUID.randomUUID().toString();

        ObjectNode cardParamMap = MAPPER.createObjectNode();
        cardParamMap.put("content", "");
        ObjectNode cardData = MAPPER.createObjectNode();
        cardData.set("cardParamMap", cardParamMap);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("cardTemplateId", cardTemplateId);
        body.put("outTrackId", outTrackId);
        body.put("callbackType", "STREAM");
        body.set("cardData", cardData);
        body.put("userIdType", 1);

        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)) {
            body.put("openSpaceId", "dtv1.card//IM_GROUP."
                    + (incoming.chatId == null ? "" : incoming.chatId));
            ObjectNode spaceModel = MAPPER.createObjectNode();
            spaceModel.put("supportForward", true);
            body.set("imGroupOpenSpaceModel", spaceModel);
            ObjectNode deliverModel = MAPPER.createObjectNode();
            deliverModel.put("robotCode", clientId);
            deliverModel.set("extension", MAPPER.createObjectNode());
            body.set("imGroupOpenDeliverModel", deliverModel);
        } else {
            body.put("openSpaceId", "dtv1.card//IM_ROBOT."
                    + (incoming.userId == null ? "" : incoming.userId));
            ObjectNode spaceModel = MAPPER.createObjectNode();
            spaceModel.put("supportForward", true);
            body.set("imRobotOpenSpaceModel", spaceModel);
            ObjectNode deliverModel = MAPPER.createObjectNode();
            deliverModel.put("robotCode", clientId);
            deliverModel.put("spaceType", "IM_ROBOT");
            deliverModel.set("extension", MAPPER.createObjectNode());
            body.set("imRobotOpenDeliverModel", deliverModel);
        }

        dingtalkApi("POST", "/v1.0/card/instances/createAndDeliver", body);
        return outTrackId;
    }

    /** PUT /v1.0/card/streaming（isFull 恒 true）。 */
    void streamingUpdateCard(String outTrackId, String content, boolean isFinalize)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("outTrackId", outTrackId);
        body.put("guid", UUID.randomUUID().toString());
        body.put("key", "content");
        body.put("content", content);
        body.put("isFull", true);
        body.put("isFinalize", isFinalize);
        body.put("isError", false);
        dingtalkApi("PUT", "/v1.0/card/streaming", body);
    }

    /** 惰性回收：清掉超过 TTL 的孤儿流。 */
    static void purgeOrphans() {
        long cutoff = System.currentTimeMillis() - STREAM_ORPHAN_TTL_MS;
        STREAMS.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    @Override
    public String startStream(IncomingMessage incoming) throws Exception {
        purgeOrphans();
        String sessionWebhook = incoming.extra == null ? ""
                : incoming.extra.getOrDefault("session_webhook", "");
        String streamId = "dt:" + (incoming.userId == null ? "" : incoming.userId) + ":"
                + (incoming.messageId == null ? "" : incoming.messageId);

        StreamState state = new StreamState();
        state.sessionWebhook = sessionWebhook;

        // 配了卡片模板才建 AI 卡片；建卡失败退回 sessionWebhook 整段回复（只警告）
        if (!cardTemplateId.isEmpty()) {
            try {
                state.outTrackId = createAndDeliverCard(incoming);
            } catch (Exception e) {
                log.warn("[DingTalk] Failed to create AI card, falling back to sessionWebhook: {}",
                        e.toString());
            }
        }
        STREAMS.put(streamId, state);
        log.info("[DingTalk] Streaming started: stream_id={}, card={}",
                streamId, !state.outTrackId.isEmpty());
        return streamId;
    }

    @Override
    public void updateStreamContent(IncomingMessage incoming, String streamId, String fullContent)
            throws Exception {
        if (fullContent == null || fullContent.isEmpty()) {
            return;
        }
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        synchronized (state.lock) {
            state.content.setLength(0);
            state.content.append(fullContent);
            if (state.outTrackId.isEmpty()) {
                return;
            }
            // 节流：距上次卡片更新不足 500ms 就只更新本地累积
            if (System.currentTimeMillis() - state.lastUpdate < MIN_CARD_UPDATE_INTERVAL_MS) {
                return;
            }
            state.lastUpdate = System.currentTimeMillis();
        }
        try {
            streamingUpdateCard(state.outTrackId, fullContent, false);
        } catch (Exception e) {
            log.warn("[DingTalk] Failed to update card stream: {}", e.toString());
        }
    }

    @Override
    public void finalizeStream(IncomingMessage incoming, String streamId, String finalContent)
            throws Exception {
        StreamState state = STREAMS.get(streamId);
        if (state == null) {
            throw new IllegalStateException("unknown stream ID: " + streamId);
        }
        String outTrackId;
        synchronized (state.lock) {
            state.content.setLength(0);
            state.content.append(finalContent == null ? "" : finalContent);
            outTrackId = state.outTrackId;
        }
        if (!outTrackId.isEmpty()) {
            try {
                streamingUpdateCard(outTrackId, finalContent == null ? "" : finalContent, false);
            } catch (Exception e) {
                log.warn("[DingTalk] Failed to finalize card stream: {}", e.toString());
            }
        }
    }

    @Override
    public void endStream(IncomingMessage incoming, String streamId) throws Exception {
        StreamState state = STREAMS.remove(streamId);
        if (state == null) {
            return;
        }
        String fullContent;
        String outTrackId;
        String sessionWebhook;
        synchronized (state.lock) {
            fullContent = state.content.toString();
            outTrackId = state.outTrackId;
            sessionWebhook = state.sessionWebhook;
        }

        // 有卡片 → 定稿卡片；否则退回 sessionWebhook 整段发；再没有就走 OpenAPI（都只警告）
        try {
            if (!outTrackId.isEmpty()) {
                streamingUpdateCard(outTrackId, fullContent, true);
            } else if (!sessionWebhook.isEmpty()) {
                replyViaSessionWebhook(sessionWebhook, fullContent);
            } else {
                replyViaOpenApi(incoming, fullContent);
            }
        } catch (Exception e) {
            log.warn("[DingTalk] Failed to end stream: {}", e.toString());
        }
        log.info("[DingTalk] Streaming ended: stream_id={}", streamId);
    }

    /** 供测试观察。 */
    String apiBaseUrl() {
        return apiBaseUrl;
    }
}
