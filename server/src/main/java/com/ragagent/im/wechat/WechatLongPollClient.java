package com.ragagent.im.wechat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 微信 iLink 机器人的 HTTP 长轮询收件（对照 Go {@code internal/im/wechat/longpoll.go}
 * L28-376）。
 *
 * <h2>照抄点</h2>
 * <ul>
 *   <li>{@code POST /ilink/bot/getupdates}，体 {@code {get_updates_buf, base_info}}，
 *       认证头同适配器；响应 {@code {ret, errcode, errmsg, msgs, get_updates_buf}}
 *       ——{@code errcode == -14} 即 <b>token 过期</b>（立即停轮询），
 *       {@code ret != 0 && errcode != 0} 算错；成功则<b>推进游标</b>；</li>
 *   <li>退避：1s·2^n 上限 30s（<b>shift 上限 30</b> 防溢出，照 Go 的注释）；
 *       <b>轮询活过 30s 后失败则清零计数</b>（照 Go）；无限重试；</li>
 *   <li>每条消息 <b>detached</b> 交处理器（不阻塞轮询，照 Go 的 goroutine）；</li>
 *   <li>解析只取 <b>item_list[0]</b>：1=文本（trim 后空则丢）、2=图片（CDN URL +
 *       aeskey/media.aes_key，文件名 {@code <message_id>.png}）、3=语音（取转写文本）、
 *       4=文件（CDN URL + media.aes_key，文件名回落 {@code file_<id>}，len 解析成字节数）；
 *       <b>message_type==2（BOT 自己发）一律丢</b>；消息 extra 带 {@code context_token}
 *       （回复要用）。</li>
 * </ul>
 */
public class WechatLongPollClient {

    private static final Logger log = LoggerFactory.getLogger(WechatLongPollClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final long LONG_POLL_TIMEOUT_MS = 35_000L;
    static final long LONG_POLL_HTTP_TIMEOUT_MS = 40_000L;
    static final long RECONNECT_BASE_DELAY_MS = 1_000L;
    static final long RECONNECT_MAX_DELAY_MS = 30_000L;
    static final int ERR_CODE_TOKEN_EXPIRED = -14;

    /** 对照 {@code ErrTokenExpired}：token 过期，需要重新扫码登录。 */
    public static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String message) {
            super(message);
        }
    }

    private final String botToken;
    private final String ilinkBotId;
    private final String baseUrl;
    private final SsrfGuard ssrfGuard;
    private final BiConsumer<IncomingMessage, String> msgHandler;
    private final String channelId;
    private final HttpClient http;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "im-wechat-handler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String cursor = "";
    private volatile Thread loopThread;

    public WechatLongPollClient(String botToken, String ilinkBotId, String baseUrl,
                                SsrfGuard ssrfGuard, String channelId,
                                BiConsumer<IncomingMessage, String> msgHandler) {
        this.botToken = botToken == null ? "" : botToken;
        this.ilinkBotId = ilinkBotId == null ? "" : ilinkBotId;
        String base = baseUrl == null || baseUrl.isBlank()
                ? WechatAdapter.ILINK_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.ssrfGuard = ssrfGuard;
        this.channelId = channelId == null ? "" : channelId;
        this.msgHandler = msgHandler;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 对照 {@code Start}：无限重连 + 退避 + token 过期即停。 */
    public void start() {
        loopThread = Thread.currentThread();
        log.info("[WeChat] long-poll starting (bot_id={}) channel={}", ilinkBotId, channelId);
        int attempts = 0;
        while (!stopped.get()) {
            long pollStart = System.currentTimeMillis();
            try {
                poll();
                attempts = 0;
                continue;
            } catch (TokenExpiredException e) {
                log.warn("[WeChat] Bot token expired, stopping long-poll");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (stopped.get()) {
                    return;
                }
                // 跑了一阵子才失败 → 重置退避（照 Go）
                if (System.currentTimeMillis() - pollStart > RECONNECT_MAX_DELAY_MS) {
                    attempts = 0;
                }
                attempts++;
                long delay = pollReconnectDelayMs(attempts);
                log.warn("[WeChat] Poll error ({}), retrying in {}ms (attempt {})",
                        e.toString(), delay, attempts);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public void stop() {
        stopped.set(true);
        Thread loop = loopThread;
        if (loop != null) {
            loop.interrupt();
        }
        workers.shutdownNow();
    }

    /** 对照 {@code poll}：一次长轮询 + 游标推进 + 逐条派发。 */
    void poll() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("get_updates_buf", cursor);
        payload.set("base_info", WechatAdapter.baseInfo());

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/ilink/bot/getupdates"))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", botToken.isEmpty() ? "" : "Bearer " + botToken)
                .header("X-WECHAT-UIN", WechatAdapter.generateWeChatUin())
                .timeout(Duration.ofMillis(LONG_POLL_HTTP_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] respBody = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("getupdates returned status " + response.statusCode()
                    + ": " + new String(respBody, StandardCharsets.UTF_8));
        }

        JsonNode result = MAPPER.readTree(respBody);
        int errCode = result.path("errcode").asInt(0);
        int ret = result.path("ret").asInt(0);
        if (errCode == ERR_CODE_TOKEN_EXPIRED) {
            throw new TokenExpiredException("wechat bot token expired");
        }
        if (ret != 0 && errCode != 0) {
            throw new IllegalStateException("getupdates error: ret=" + ret + " errcode=" + errCode
                    + " msg=" + result.path("errmsg").asText(""));
        }
        String newCursor = result.path("get_updates_buf").asText("");
        if (!newCursor.isEmpty()) {
            cursor = newCursor;
        }

        for (JsonNode msg : result.path("msgs")) {
            IncomingMessage incoming = parseMessage(msg);
            if (incoming == null) {
                continue;
            }
            // detached：不阻塞轮询（照 Go 的 go func）
            workers.submit(() -> {
                try {
                    if (msgHandler != null) {
                        msgHandler.accept(incoming, channelId);
                    }
                } catch (Exception e) {
                    log.error("[WeChat] Handle message error: {}", e.toString());
                }
            });
        }
    }

    /** 对照 {@code parseMessage}：只取首 item；BOT 自己的消息丢弃。 */
    IncomingMessage parseMessage(JsonNode msg) {
        if (msg.path("message_type").asInt(0) == 2) {
            return null;
        }
        JsonNode items = msg.path("item_list");
        if (!items.isArray() || items.isEmpty()) {
            return null;
        }
        JsonNode item = items.get(0);
        int type = item.path("type").asInt(0);
        String contextToken = msg.path("context_token").asText("");
        String fromUserId = msg.path("from_user_id").asText("");
        String messageId = msg.path("message_id").asText("");

        switch (type) {
            case 1: {   // TEXT
                String content = item.path("text_item").path("text").asText("").trim();
                if (content.isEmpty()) {
                    return null;
                }
                IncomingMessage incoming = base(fromUserId, messageId);
                incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
                incoming.content = content;
                incoming.extra.put("context_token", contextToken);
                return incoming;
            }
            case 2: {   // IMAGE
                JsonNode media = item.path("image_item").path("media");
                String encryptParam = media.path("encrypt_query_param").asText("");
                if (media.isMissingNode() || encryptParam.isEmpty()) {
                    return null;
                }
                // 图片优先用 image_item.aeskey（hex），否则 media.aes_key（base64）
                String aesKey = item.path("image_item").path("aeskey").asText("");
                if (aesKey.isEmpty()) {
                    aesKey = media.path("aes_key").asText("");
                }
                IncomingMessage incoming = base(fromUserId, messageId);
                incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
                incoming.fileKey = WechatAdapter.buildCdnDownloadUrl(encryptParam);
                incoming.fileName = messageId + ".png";
                incoming.extra.put("context_token", contextToken);
                incoming.extra.put("aes_key", aesKey);
                return incoming;
            }
            case 3: {   // VOICE（语音转写）
                String text = item.path("voice_item").path("text").asText("");
                if (text.isEmpty()) {
                    return null;
                }
                IncomingMessage incoming = base(fromUserId, messageId);
                incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
                incoming.content = text.trim();
                incoming.extra.put("context_token", contextToken);
                return incoming;
            }
            case 4: {   // FILE
                JsonNode media = item.path("file_item").path("media");
                String encryptParam = media.path("encrypt_query_param").asText("");
                if (media.isMissingNode() || encryptParam.isEmpty()) {
                    return null;
                }
                String fileName = item.path("file_item").path("file_name").asText("");
                if (fileName.isEmpty()) {
                    fileName = "file_" + messageId;
                }
                long fileSize = parseLongSafe(item.path("file_item").path("len").asText(""));
                IncomingMessage incoming = base(fromUserId, messageId);
                incoming.messageType = ImTypes.MESSAGE_TYPE_FILE;
                incoming.fileKey = WechatAdapter.buildCdnDownloadUrl(encryptParam);
                incoming.fileName = fileName;
                incoming.fileSize = fileSize;
                incoming.extra.put("context_token", contextToken);
                incoming.extra.put("aes_key", media.path("aes_key").asText(""));
                return incoming;
            }
            default:
                return null;
        }
    }

    private static IncomingMessage base(String fromUserId, String messageId) {
        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_WECHAT;
        incoming.userId = fromUserId;
        incoming.chatType = ImTypes.CHAT_TYPE_DIRECT;
        incoming.messageId = messageId;
        return incoming;
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** 对照 {@code pollReconnectDelay}：1s·2^(n-1)，上限 30s（shift 上限 30 防溢出）。 */
    static long pollReconnectDelayMs(int attempt) {
        if (attempt < 1) {
            return RECONNECT_BASE_DELAY_MS;
        }
        int shift = attempt - 1;
        if (shift > 30) {
            return RECONNECT_MAX_DELAY_MS;
        }
        long delay = RECONNECT_BASE_DELAY_MS * (1L << shift);
        return Math.min(delay, RECONNECT_MAX_DELAY_MS);
    }

    /** 供测试观察游标推进。 */
    String cursor() {
        return cursor;
    }

}
