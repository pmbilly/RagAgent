package com.ragagent.im.feishu;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书发送协作者：reply API 优先、可回落错误码时改走
 * send-message（带 receive_id_type）；message_id 不安全字符直接拒。
 * 持门面回引取 region/http/token；{@code MAPPER}/{@code readTree}/{@code FALLBACK_ELIGIBLE}
 * 经门面类名访问。
 */
final class FeishuSendOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuSendOps.class);

    private final FeishuAdapter service;

    FeishuSendOps(FeishuAdapter service) {
        this.service = service;
    }

    // ── 发送（reply 优先，可回落 send-message） ───────────────────────────────

    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String accessToken = service.getTenantAccessToken();
        String content = FeishuAdapter.MAPPER.writeValueAsString(Map.of("text",
                reply.content == null ? "" : reply.content));

        Map<String, Object> replyPayload = new LinkedHashMap<>();
        replyPayload.put("msg_type", "text");
        replyPayload.put("content", content);

        String[] receive = resolveReceiveId(incoming);
        Map<String, Object> fallbackPayload = new LinkedHashMap<>();
        fallbackPayload.put("receive_id", receive[1]);
        fallbackPayload.put("msg_type", "text");
        fallbackPayload.put("content", content);

        sendWithFallback(accessToken, incoming, replyPayload, fallbackPayload, receive[0]);
    }

    /** 群聊 chat_id，私聊 open_id。 */
    static String[] resolveReceiveId(IncomingMessage incoming) {
        String receiveIdType = "open_id";
        String receiveId = incoming.userId == null ? "" : incoming.userId;
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)
                && incoming.chatId != null && !incoming.chatId.isEmpty()) {
            receiveIdType = "chat_id";
            receiveId = incoming.chatId;
        }
        return new String[] {receiveIdType, receiveId};
    }

    /** reply API 优先，可回落码 → send-message。 */
    void sendWithFallback(String accessToken, IncomingMessage incoming,
                                  Map<String, Object> replyPayload,
                                  Map<String, Object> fallbackPayload, String receiveIdType)
            throws Exception {
        String messageId = incoming.messageId == null ? "" : incoming.messageId;
        if (!messageId.isEmpty() && safePathParam(messageId)) {
            String replyUrl = service.api("/open-apis/im/v1/messages/" + messageId + "/reply");
            ApiResult result = postFeishuMessage(accessToken, replyUrl, replyPayload);
            if (result.transportError() != null) {
                log.warn("[{}] reply API transport error (will try fallback): {}",
                        service.region.label(), result.transportError());
            } else if (result.code() == 0) {
                return;
            } else if (!FeishuAdapter.FALLBACK_ELIGIBLE.contains(result.code())) {
                throw new IllegalStateException(service.region.label() + " reply api error: code="
                        + result.code() + " msg=" + result.msg());
            } else {
                log.warn("[{}] reply API returned code={} msg={}, falling back to send-message API",
                        service.region.label(), result.code(), result.msg());
            }
        } else if (!messageId.isEmpty()) {
            // message_id 含不安全字符 → 拒绝而不是放进 URL 路径（疑似篡改）
            throw new IllegalArgumentException("invalid message_id for reply API: " + messageId);
        } else {
            log.warn("[{}] incoming message has no message_id; replying via send-message API"
                    + " (will not attach to thread)", service.region.label());
        }

        String fallbackUrl = service.api("/open-apis/im/v1/messages?receive_id_type=" + receiveIdType);
        ApiResult result = postFeishuMessage(accessToken, fallbackUrl, fallbackPayload);
        if (result.transportError() != null) {
            throw new IllegalStateException("send message (fallback): " + result.transportError(),
                    result.transportError());
        }
        if (result.code() != 0) {
            throw new IllegalStateException(service.region.label() + " send api error: code="
                    + result.code() + " msg=" + result.msg());
        }
    }

    /** POST JSON，解 (code, msg)（传输错误单列）。 */
    private ApiResult postFeishuMessage(String accessToken, String url, Map<String, Object> payload)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(FeishuAdapter.MAPPER.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<byte[]> response = service.http.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            JsonNode node = FeishuAdapter.readTree(response.body());
            return new ApiResult(node.path("code").asInt(0), node.path("msg").asText(""), null);
        } catch (java.io.IOException e) {
            return new ApiResult(0, "", e);
        }
    }

    private record ApiResult(int code, String msg, Throwable transportError) {
    }

    /** 只允许字母数字与 {@code -_}，且非空。 */
    static boolean safePathParam(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
