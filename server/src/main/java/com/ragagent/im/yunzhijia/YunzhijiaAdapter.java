package com.ragagent.im.yunzhijia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

/**
 * 云之家（Yunzhijia）适配器——对照 Go {@code internal/im/yunzhijia/adapter.go} L27-610。
 *
 * <h2>照抄点</h2>
 * <ul>
 *   <li>验签：{@code sign} 头（三形态大小写都试）+ {@code Base64(HMAC-SHA1(secret, 七字段逗号串))}，
 *       定长比较；未配 secret 跳过；</li>
 *   <li>解析：只认 {@code type == 2}（文本）；{@code msgParam} 解析失败只告警；
 *       空内容且无内嵌图丢弃；<b>必须 @机器人</b>（先剥内容里的 {@code @名字}，再回落看
 *       {@code notifyTo}/{@code at} 描述）；剥完为空又无图再丢；</li>
 *   <li>ID 回落链：userId（operatorOpenid→operatorOid→openId→senderId→operatorId→operatorUserId）、
 *       userName（operatorName→senderName）、chatId（groupId→robotId）；chatType 恒 group；</li>
 *   <li>threadID = {@code replyRootMsgId} 回落 {@code msgId}；extra 带
 *       robot_id/robot_name/group_id/group_type/operator_name/time（图片另有宽高）；</li>
 *   <li>图片：{@code desc} 里首个 {@code type=image} 且 data 非空 → 图片消息（fileKey=data、
 *       文件名 {@code <msgId>.png}，msgId 空则 {@code yunzhijia-image.png}）；</li>
 *   <li>发送：{@code POST send_msg_url}，体 {@code {msgtype:2, content, notifyParams, paramType,
 *       param{formatType:"markdown", replyMsgId, isReference, replySummary, replyPersonName}}}
 *       ——有 {@code incoming.MessageID} 时带引用（paramType=3）并把 content 当引用摘要；
 *       <b>无引用且 formatType 被显式置空时整体不出 param</b>（照 Go 的 opt-out 语义）；
 *       {@code group_type == "3"} 不设 notifyParams；</li>
 *   <li>下载：fileId 校验（≤256、不含 {@code /\?#&}、无空白/控制字符）→ app access token
 *       （缓存留 60s 余量、{@code expireIn} 缺省 3600）→ {@code downloadfileOpen?fileId=}
 *       → <b>手动跟随 ≤1 次重定向</b>（每次重新校验宿主、token 只带首发）→ 32MiB 上限
 *       （读超限报错而非静默截断）→ 文件名：Content-Disposition 优先，无扩展名时按
 *       Content-Type 补 jpg/png/gif；</li>
 *   <li>出站端点校验：send_msg_url / 下载地址都要求 https + 落在允许后缀内（IP 字面量与
 *       localhost 一律拒），另有公网 IP 判定（{@link YunzhijiaUrl#resolvePublicAddress}）。</li>
 * </ul>
 *
 * <h2>与 Go 的差异（备案）</h2>
 * <ul>
 *   <li>Go 用自定义拨号器（{@code safeDialContext}）在<b>建连时</b>校验解析出的 IP；
 *       Java 的 HttpClient 无拨号口，改为请求前解析校验（{@link #allowPrivateHosts} 为
 *       测试口——Go 侧靠包级 var 覆写达到同样效果）；</li>
 *   <li>认证/下载基址在 Java 侧是构造器参数（默认同 Go 的包级 var）。</li>
 * </ul>
 */
public class YunzhijiaAdapter implements AdapterInterfaces.Adapter,
        AdapterInterfaces.FileDownloader {

    private static final Logger log = LoggerFactory.getLogger(YunzhijiaAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 {@code textMessageType}。 */
    public static final int TEXT_MESSAGE_TYPE = 2;
    /** 对照 {@code markdownFormatType}。 */
    public static final String MARKDOWN_FORMAT_TYPE = "markdown";
    public static final String DEFAULT_AUTH_URL =
            "https://yunzhijia.com/api/oauth2_v12/auth/getAppAccessToken";
    public static final String DEFAULT_DOWNLOAD_BASE_URL =
            "https://yunzhijia.com/gateway/docrest/doc/file/downloadfileOpen";
    public static final String DOWNLOAD_ALLOWED_SUFFIX = "yunzhijia.com";
    public static final long MAX_DOWNLOAD_FILE_SIZE = 32L << 20;
    static final int MAX_DOWNLOAD_REDIRECTS = 1;

    private final String sendMsgUrl;
    private final String secret;
    private final String appId;
    private final String appSecret;
    private final String allowedWebhookHostSuffix;
    private final String authUrl;
    private final String downloadBaseUrl;
    /** 测试口：允许私网/回环（Go 侧是覆写包级 var 达到同样效果）。 */
    private final boolean allowPrivateHosts;
    private final SsrfGuard ssrfGuard;
    private final HttpClient http;

    private final Object tokenLock = new Object();
    private String accessToken = "";
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public YunzhijiaAdapter(String sendMsgUrl, String secret, String appId, String appSecret,
                            int timeoutSeconds, String allowedHostSuffix, SsrfGuard ssrfGuard) {
        this(sendMsgUrl, secret, appId, appSecret, timeoutSeconds, allowedHostSuffix, ssrfGuard,
                DEFAULT_AUTH_URL, DEFAULT_DOWNLOAD_BASE_URL, false);
    }

    public YunzhijiaAdapter(String sendMsgUrl, String secret, String appId, String appSecret,
                            int timeoutSeconds, String allowedHostSuffix, SsrfGuard ssrfGuard,
                            String authUrl, String downloadBaseUrl, boolean allowPrivateHosts) {
        int timeout = timeoutSeconds <= 0 ? 10 : timeoutSeconds;
        this.sendMsgUrl = trim(sendMsgUrl);
        this.secret = trim(secret);
        this.appId = trim(appId);
        this.appSecret = trim(appSecret);
        this.allowedWebhookHostSuffix = trim(allowedHostSuffix);
        this.authUrl = authUrl == null || authUrl.isBlank() ? DEFAULT_AUTH_URL : authUrl;
        this.downloadBaseUrl = downloadBaseUrl == null || downloadBaseUrl.isBlank()
                ? DEFAULT_DOWNLOAD_BASE_URL : downloadBaseUrl;
        this.allowPrivateHosts = allowPrivateHosts;
        this.ssrfGuard = ssrfGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(timeout, 1)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_YUNZHIJIA;
    }

    /** 对照 Go：不走 URL 挑战。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    /** 对照 {@code VerifyCallback}：sign 头 + HMAC-SHA1（未配 secret 跳过）。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        if (secret.isEmpty()) {
            return null;
        }
        YunzhijiaTypes.CallbackMessage msg;
        try {
            msg = MAPPER.readValue(exchange.body() == null ? new byte[0] : exchange.body(),
                    YunzhijiaTypes.CallbackMessage.class);
        } catch (Exception e) {
            return new AdapterInterfaces.VerifyException("parse callback for verification: "
                    + e.getMessage());
        }
        String sign = firstHeader(exchange, "sign", "Sign", "SIGN");
        if (sign.isEmpty()) {
            return new AdapterInterfaces.VerifyException("missing sign header");
        }
        String expected = YunzhijiaSign.computeSignature(secret, msg);
        if (!MessageDigest.isEqual(sign.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return new AdapterInterfaces.VerifyException("invalid signature");
        }
        return null;
    }

    private static String firstHeader(CallbackExchange exchange, String... names) {
        for (String name : names) {
            String value = exchange.header(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        YunzhijiaTypes.CallbackMessage msg;
        try {
            msg = MAPPER.readValue(exchange.body() == null ? new byte[0] : exchange.body(),
                    YunzhijiaTypes.CallbackMessage.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("parse callback: " + e.getMessage(), e);
        }
        return toIncomingMessage(msg);
    }

    /** 对照 {@code toIncomingMessage}。 */
    static IncomingMessage toIncomingMessage(YunzhijiaTypes.CallbackMessage msg) {
        if (msg.type != TEXT_MESSAGE_TYPE) {
            log.info("[Yunzhijia] Skip non-text message: type={} msgId={}", msg.type, msg.msgId);
            return null;
        }

        YunzhijiaTypes.MessageParam param = null;
        try {
            param = parseMessageParam(msg.msgParam);
        } catch (Exception e) {
            log.warn("[Yunzhijia] Failed to parse msgParam: msgId={} err={}", msg.msgId,
                    e.toString());
        }
        if (param != null) {
            log.info("[Yunzhijia] Thread callback: msg_id={} reply_msg_id={} reply_root_msg_id={}",
                    msg.msgId, param.replyMsgId, param.replyRootMsgId);
        }
        YunzhijiaTypes.MessageParamDesc image = param == null ? null : param.firstImage();

        String content = trim(msg.content);
        if (content.isEmpty() && image == null) {
            log.info("[Yunzhijia] Skip empty content: msgId={}", msg.msgId);
            return null;
        }

        // 会话机器人只应收到显式 @ 它的消息
        MentionResult mention = cleanAtMention(content, msg.robotName);
        boolean mentioned = mention.mentioned();
        if (!mentioned) {
            mentioned = messageParamMentionsRobot(param, msg.robotId);
        }
        if (!mentioned) {
            log.info("[Yunzhijia] Skip message without robot mention: msgId={}", msg.msgId);
            return null;
        }
        content = mention.content();
        if (content.isEmpty() && image == null) {
            log.info("[Yunzhijia] Skip after cleaning @mention: msgId={}", msg.msgId);
            return null;
        }

        String userId = firstNonEmpty(msg.operatorOpenid, msg.operatorOid, msg.openId, msg.senderId,
                msg.operatorId, msg.operatorUserId);
        String userName = firstNonEmpty(msg.operatorName, msg.senderName);
        String chatId = firstNonEmpty(msg.groupId, msg.robotId);

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_YUNZHIJIA;
        incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        incoming.userId = userId;
        incoming.userName = userName;
        incoming.chatId = chatId;
        incoming.chatType = ImTypes.CHAT_TYPE_GROUP;
        incoming.content = content;
        incoming.messageId = msg.msgId;
        incoming.threadId = threadIdForMessage(msg.msgId, param);
        incoming.extra.put("robot_id", msg.robotId);
        incoming.extra.put("robot_name", msg.robotName);
        incoming.extra.put("group_id", msg.groupId);
        incoming.extra.put("group_type", String.valueOf(msg.groupType));
        incoming.extra.put("operator_name", userName);
        incoming.extra.put("time", String.valueOf(msg.time));

        if (image != null) {
            incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
            incoming.fileKey = image.data;
            incoming.fileName = defaultImageFileName(msg.msgId);
            incoming.extra.put("yunzhijia_image_width", String.valueOf(image.w));
            incoming.extra.put("yunzhijia_image_height", String.valueOf(image.h));
        }
        return incoming;
    }

    /** 供测试的便捷口（勿在运行时使用）。 */
    static IncomingMessage toIncomingMessage(String rawJson) throws Exception {
        return toIncomingMessage(MAPPER.readValue(rawJson,
                YunzhijiaTypes.CallbackMessage.class));
    }

    /** 对照 {@code parseMessageParam}：空串 → null；坏 JSON 上抛。 */
    static YunzhijiaTypes.MessageParam parseMessageParam(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return MAPPER.readValue(raw, YunzhijiaTypes.MessageParam.class);
    }

    /** 对照 {@code threadIDForMessage}。 */
    static String threadIdForMessage(String messageId, YunzhijiaTypes.MessageParam param) {
        if (param != null && param.replyRootMsgId != null && !param.replyRootMsgId.isEmpty()) {
            return param.replyRootMsgId;
        }
        return messageId;
    }

    /** 对照 {@code firstNonEmpty}：逐个 trim 取首个非空。 */
    static String firstNonEmpty(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    /** 对照 {@code messageParamMentionsRobot}：notifyTo 命中或 desc 里有 at=robotId。 */
    static boolean messageParamMentionsRobot(YunzhijiaTypes.MessageParam param, String robotId) {
        if (param == null || robotId == null || robotId.isEmpty()) {
            return false;
        }
        if (param.notifyTo != null) {
            for (String notifyTo : param.notifyTo) {
                if (robotId.equals(notifyTo)) {
                    return true;
                }
            }
        }
        if (param.desc != null) {
            for (YunzhijiaTypes.MessageParamDesc desc : param.desc) {
                if ("at".equals(desc.type) && robotId.equals(desc.data)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 对照 {@code defaultImageFileName}。 */
    static String defaultImageFileName(String msgId) {
        if (msgId == null || msgId.isEmpty()) {
            return "yunzhijia-image.png";
        }
        return msgId + ".png";
    }

    /** 清理结果（对照 Go 的双返回值）。 */
    record MentionResult(String content, boolean mentioned) {
    }

    /** 对照 {@code cleanAtMention}：剥掉开头的 @机器人名（其后必须是空白或中英标点）。 */
    static MentionResult cleanAtMention(String content, String robotName) {
        String value = content == null ? "" : content;
        if (robotName == null || robotName.isEmpty()) {
            return new MentionResult(value, false);
        }
        String prefix = "@" + robotName;
        String trimmed = value.replaceAll("^[ \\t]+", "");
        if (!trimmed.startsWith(prefix)) {
            return new MentionResult(value, false);
        }
        String rest = trimmed.substring(prefix.length());
        if (rest.isEmpty()) {
            return new MentionResult("", true);
        }
        int separator = rest.codePointAt(0);
        if (!Character.isWhitespace(separator) && ":：,，".indexOf(separator) < 0) {
            return new MentionResult(value, false);
        }
        int idx = 0;
        while (idx < rest.length()) {
            int cp = rest.codePointAt(idx);
            if (!Character.isWhitespace(cp) && ":：,，".indexOf(cp) < 0) {
                break;
            }
            idx += Character.charCount(cp);
        }
        return new MentionResult(rest.substring(idx), true);
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        if (sendMsgUrl.isEmpty()) {
            throw new IllegalStateException("yunzhijia send_msg_url is not configured");
        }
        validateSendUrl();

        YunzhijiaTypes.SendMessagePayload payload = new YunzhijiaTypes.SendMessagePayload();
        payload.msgtype = TEXT_MESSAGE_TYPE;
        payload.content = reply.content;
        payload.param = new YunzhijiaTypes.SendMessageParam();
        payload.param.formatType = MARKDOWN_FORMAT_TYPE;
        if (reply.extra != null && reply.extra.containsKey("yunzhijia_format_type")) {
            payload.param.formatType = reply.extra.get("yunzhijia_format_type");
        }
        if (incoming.messageId != null && !incoming.messageId.isEmpty()) {
            payload.paramType = 3;
            payload.param.replyMsgId = incoming.messageId;
            payload.param.isReference = true;
            payload.param.replySummary = incoming.content;
            payload.param.replyPersonName = incoming.userName;
        } else if (payload.param.formatType == null || payload.param.formatType.isEmpty()) {
            // 无引用且被显式关掉 markdown → 整体不出 param（照 Go 的 opt-out 语义）
            payload.param = null;
        }

        String groupType = incoming.extra == null ? "" : incoming.extra.getOrDefault("group_type", "");
        if (!"3".equals(groupType) && incoming.userId != null && !incoming.userId.isEmpty()) {
            payload.notifyParams = List.of(new YunzhijiaTypes.NotifyParam("openIds",
                    List.of(incoming.userId)));
        }

        validateOutboundHost(sendMsgUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(sendMsgUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            byte[] body = response.body() == null ? new byte[0] : response.body();
            throw new IllegalStateException("yunzhijia sendMsgUrl returned "
                    + response.statusCode() + ": " + new String(body, StandardCharsets.UTF_8));
        }
    }

    /** 对照 {@code validateSendURL}：https + 允许后缀。 */
    void validateSendUrl() {
        try {
            YunzhijiaUrl.validateEndpointUrl(sendMsgUrl, "https", allowedWebhookHostSuffix);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid send_msg_url: " + e.getMessage(), e);
        }
    }

    /** 对照 Go 的拨号期公网校验（Java 改在请求前，见类注释）。 */
    private void validateOutboundHost(String rawUrl) {
        if (allowPrivateHosts) {
            return;
        }
        try {
            if (ssrfGuard != null) {
                ssrfGuard.validateURLForSSRF(rawUrl);
            }
            URI uri = URI.create(rawUrl);
            if (uri.getHost() != null) {
                YunzhijiaUrl.resolvePublicAddress(uri.getHost());
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("outbound host rejected: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("outbound host rejected: " + e.getMessage(), e);
        }
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    @Override
    public DownloadedFile downloadFile(IncomingMessage msg) throws Exception {
        if (msg.fileKey == null || msg.fileKey.isEmpty()) {
            throw new IllegalArgumentException("yunzhijia file id is required");
        }
        String fileId = msg.fileKey.trim();
        try {
            validateFileId(fileId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid yunzhijia file id: " + e.getMessage(), e);
        }

        String token = getAppAccessToken();
        String downloadUrl = buildDownloadUrl(fileId);
        try {
            validateDownloadFileUrl(downloadUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("download url rejected: " + e.getMessage(), e);
        }

        Fetched fetched = fetchDownload(downloadUrl, token);
        if (fetched == null || fetched.status != 200) {
            int status = fetched == null ? 0 : fetched.status;
            String body = fetched == null ? "" : new String(fetched.body, StandardCharsets.UTF_8);
            throw new IllegalStateException("download file returned " + status + ": " + body);
        }

        String fileName = msg.fileName == null || msg.fileName.isEmpty() ? fileId : msg.fileName;
        fileName = resolveDownloadFileName(fileName, fetched.contentDisposition, fetched.contentType);
        long limit = MAX_DOWNLOAD_FILE_SIZE;
        if (fetched.body.length > limit) {
            throw new IllegalStateException("yunzhijia file exceeds max download size of "
                    + limit + " bytes");
        }
        return new DownloadedFile(fetched.body, fileName);
    }

    /** 对照 {@code fetchDownload}：手动 ≤1 次重定向（token 只带首发，逐次重校验宿主）。 */
    Fetched fetchDownload(String downloadUrl, String token) throws Exception {
        String currentUrl = downloadUrl;
        for (int redirects = 0; ; redirects++) {
            validateOutboundHost(currentUrl);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(currentUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET();
            if (redirects == 0) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return new Fetched(status, response.body() == null ? new byte[0] : response.body(),
                        response.headers().firstValue("Content-Disposition").orElse(""),
                        response.headers().firstValue("Content-Type").orElse(""));
            }
            String location = response.headers().firstValue("Location").orElse("").trim();
            if (redirects >= MAX_DOWNLOAD_REDIRECTS) {
                throw new IllegalStateException("download file: too many redirects");
            }
            if (location.isEmpty()) {
                throw new IllegalStateException("download file: redirect without Location");
            }
            String resolved;
            try {
                resolved = URI.create(currentUrl).resolve(location).toString();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "download file: invalid redirect location: " + e.getMessage(), e);
            }
            try {
                validateDownloadFileUrl(resolved);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("download redirect rejected: " + e.getMessage(), e);
            }
            currentUrl = resolved;
        }
    }

    /** 一次下载往返的结果。 */
    record Fetched(int status, byte[] body, String contentDisposition, String contentType) {
    }

    /** 对照 {@code validateDownloadFileURL}：https + {@code yunzhijia.com} 后缀。 */
    void validateDownloadFileUrl(String rawUrl) {
        YunzhijiaUrl.validateEndpointUrl(rawUrl, "https", DOWNLOAD_ALLOWED_SUFFIX);
    }

    /** 对照 {@code buildDownloadFileURL}。 */
    static String buildDownloadFileUrl(String fileId) {
        return DEFAULT_DOWNLOAD_BASE_URL + "?fileId="
                + java.net.URLEncoder.encode(fileId, StandardCharsets.UTF_8);
    }

    /** 实例面：基址可注入（测试用本地 stub），语义与上面的常量版一致。 */
    String buildDownloadUrl(String fileId) {
        return downloadBaseUrl + "?fileId="
                + java.net.URLEncoder.encode(fileId, StandardCharsets.UTF_8);
    }

    /** 对照 {@code validateFileID}。 */
    static void validateFileId(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        if (fileId.length() > 256) {
            throw new IllegalArgumentException("too long");
        }
        if (fileId.matches(".*[/\\\\?#&].*")) {
            throw new IllegalArgumentException("contains path or query separators");
        }
        for (int i = 0; i < fileId.length(); i++) {
            char c = fileId.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("contains whitespace or control character");
            }
        }
    }

    /** 对照 {@code resolveDownloadFileName}：CD 文件名优先；无扩展名时按 CT 补。 */
    static String resolveDownloadFileName(String fallback, String contentDisposition,
                                          String contentType) {
        String fileName = fallback == null ? "" : fallback;
        String cd = contentDisposition == null ? "" : contentDisposition;
        if (!cd.isEmpty()) {
            for (String part : cd.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("filename=")) {
                    String name = trimmed.substring("filename=".length()).trim()
                            .replaceAll("^\"|\"$", "").trim();
                    if (!name.isEmpty()) {
                        fileName = name;
                    }
                }
            }
        }
        if (!hasExtension(fileName)) {
            String ct = (contentType == null ? "" : contentType).trim();
            int semi = ct.indexOf(';');
            if (semi >= 0) {
                ct = ct.substring(0, semi);
            }
            switch (ct.trim().toLowerCase(Locale.ROOT)) {
                case "image/jpeg" -> fileName += ".jpg";
                case "image/png" -> fileName += ".png";
                case "image/gif" -> fileName += ".gif";
                default -> {
                    // 其它类型不加扩展名（照 Go 只认这三种）
                }
            }
        }
        return fileName;
    }

    private static boolean hasExtension(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        return dot > slash && dot >= 0 && dot < fileName.length() - 1;
    }

    /** 对照 {@code getAppAccessToken}：缓存留 60s 余量（expireIn 缺省 3600）。 */
    String getAppAccessToken() throws Exception {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw new IllegalStateException(
                    "yunzhijia app_id and app_secret are required to download files");
        }
        synchronized (tokenLock) {
            if (!accessToken.isEmpty() && Instant.now().isBefore(accessTokenExpiresAt)) {
                return accessToken;
            }
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("appId", appId);
        payload.put("secret", appSecret);
        payload.put("timestamp", System.currentTimeMillis());
        validateOutboundHost(authUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("yunzhijia token endpoint returned "
                    + response.statusCode() + ": "
                    + new String(respBody, StandardCharsets.UTF_8));
        }
        YunzhijiaTypes.AppAccessTokenResponse tokenResp = MAPPER.readValue(respBody,
                YunzhijiaTypes.AppAccessTokenResponse.class);
        if (!tokenResp.success || tokenResp.errorCode != 0
                || tokenResp.data.accessToken == null || tokenResp.data.accessToken.isEmpty()) {
            throw new IllegalStateException("yunzhijia token response failed: errorCode="
                    + tokenResp.errorCode + " error=" + (tokenResp.error == null
                    ? "null" : tokenResp.error.toString()));
        }
        long expiresIn = tokenResp.data.expireIn <= 0 ? 3600 : tokenResp.data.expireIn;
        Instant expiresAt = Instant.now().plusSeconds(expiresIn);
        if (expiresIn > 120) {
            expiresAt = expiresAt.minusSeconds(60);
        }
        synchronized (tokenLock) {
            accessToken = tokenResp.data.accessToken;
            accessTokenExpiresAt = expiresAt;
        }
        return accessToken;
    }

    /** 供测试观察（auth 基址）。 */
    String authUrl() {
        return authUrl;
    }
}
