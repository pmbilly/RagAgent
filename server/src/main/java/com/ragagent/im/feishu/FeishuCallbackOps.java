package com.ragagent.im.feishu;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.FeishuWecomCrypt;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书回调验签/解析协作者（对照 Go adapter.go 的 VerifyCallback/HandleURLVerification/
 * ParseCallback 段，自 {@link FeishuAdapter} 机械搬出）：加密体先解密、只认
 * im.message.receive_v1、text/file/image/post 四型。持门面回引取
 * verificationToken/region/encryptKey，{@code MAPPER}/{@code readTree} 经门面类名访问。
 */
final class FeishuCallbackOps {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackOps.class);

    private final FeishuAdapter service;

    FeishuCallbackOps(FeishuAdapter service) {
        this.service = service;
    }

    /** 对照 {@code VerifyCallback}：未配 token 跳过；加密体先解密，再比 header.token。 */
    public Exception verifyCallback(CallbackExchange exchange) {
        if (service.verificationToken.isEmpty()) {
            return null;
        }
        byte[] body = exchange.body();
        byte[] raw;
        try {
            JsonNode encrypted = FeishuAdapter.readTree(body);
            String encrypt = encrypted.path("encrypt").asText("");
            if (!encrypt.isEmpty()) {
                raw = decrypt(encrypt);
            } else {
                raw = body == null ? new byte[0] : body;
            }
        } catch (Exception e) {
            return new AdapterInterfaces.VerifyException("decrypt event for verification: "
                    + e.getMessage());
        }
        try {
            JsonNode event = FeishuAdapter.readTree(raw);
            JsonNode header = event.path("header");
            if (header.isMissingNode() || header.isNull()
                    || !service.verificationToken.equals(header.path("token").asText(""))) {
                return new AdapterInterfaces.VerifyException("invalid verification token");
            }
        } catch (Exception e) {
            return new AdapterInterfaces.VerifyException("unmarshal event header: "
                    + e.getMessage());
        }
        return null;
    }

    /** 对照 {@code HandleURLVerification}：含 {@code challenge} 即 200 回显（加密体先解密）。 */
    public boolean handleURLVerification(CallbackExchange exchange) {
        byte[] body = exchange.body();
        JsonNode parsed;
        try {
            JsonNode maybeEncrypted = FeishuAdapter.readTree(body);
            String encrypt = maybeEncrypted.path("encrypt").asText("");
            if (!encrypt.isEmpty()) {
                parsed = FeishuAdapter.readTree(decrypt(encrypt));
            } else {
                parsed = maybeEncrypted;
            }
        } catch (Exception e) {
            if (exchange.body() != null && !new String(
                    exchange.body(), StandardCharsets.UTF_8).contains("\"encrypt\"")) {
                return false;
            }
            log.error("[{}] Failed to decrypt: {}", service.region.label(), e.toString());
            return false;
        }
        if (parsed.has("challenge") && parsed.get("challenge").isTextual()) {
            exchange.json(200, Map.of("challenge", parsed.get("challenge").asText()));
            return true;
        }
        return false;
    }

    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        byte[] body = exchange.body();
        JsonNode maybeEncrypted = FeishuAdapter.readTree(body);
        String encrypt = maybeEncrypted.path("encrypt").asText("");
        JsonNode eventBody = encrypt.isEmpty() ? maybeEncrypted
                : FeishuAdapter.readTree(decrypt(encrypt));

        JsonNode header = eventBody.path("header");
        String eventType = header.path("event_type").asText("");
        if (!"im.message.receive_v1".equals(eventType)) {
            if (!header.isMissingNode()) {
                log.info("[{}] Ignoring event type: {}", service.region.label(), eventType);
            }
            return null;
        }
        JsonNode message = eventBody.path("event").path("message");
        if (message.isMissingNode() || message.isNull()) {
            return null;
        }

        String messageId = message.path("message_id").asText("");
        String threadId = message.path("root_id").asText("");
        if (threadId.isEmpty()) {
            threadId = messageId;
        }
        boolean isGroup = "group".equals(message.path("chat_type").asText(""));
        String chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        String chatId = isGroup ? message.path("chat_id").asText("") : "";
        String openId = eventBody.path("event").path("sender").path("sender_id")
                .path("open_id").asText("");

        String messageType = message.path("message_type").asText("");
        String rawContent = message.path("content").asText("");
        return switch (messageType) {
            case "text" -> {
                JsonNode text = FeishuAdapter.MAPPER.readTree(rawContent.isEmpty() ? "{}" : rawContent);
                String content = text.path("text").asText("");
                if (isGroup) {
                    content = stripBotMention(content);
                }
                yield textMessage(openId, chatId, chatType, messageId, threadId,
                        content.trim());
            }
            case "file" -> {
                JsonNode file = FeishuAdapter.MAPPER.readTree(rawContent.isEmpty() ? "{}" : rawContent);
                String fileKey = file.path("file_key").asText("");
                if (fileKey.isEmpty()) {
                    yield null;
                }
                IncomingMessage msg = baseMessage(openId, chatId, chatType, messageId, threadId);
                msg.messageType = ImTypes.MESSAGE_TYPE_FILE;
                msg.fileKey = fileKey;
                msg.fileName = file.path("file_name").asText("");
                yield msg;
            }
            case "image" -> {
                JsonNode image = FeishuAdapter.MAPPER.readTree(rawContent.isEmpty() ? "{}" : rawContent);
                String imageKey = image.path("image_key").asText("");
                if (imageKey.isEmpty()) {
                    yield null;
                }
                IncomingMessage msg = baseMessage(openId, chatId, chatType, messageId, threadId);
                msg.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
                msg.fileKey = imageKey;
                msg.fileName = imageKey + ".png";
                yield msg;
            }
            case "post" -> {
                JsonNode post = FeishuAdapter.MAPPER.readTree(rawContent.isEmpty() ? "{}" : rawContent);
                List<String> parts = new ArrayList<>();
                String title = post.path("title").asText("");
                if (!title.isEmpty()) {
                    parts.add(title);
                }
                for (JsonNode line : post.path("content")) {
                    StringBuilder lineText = new StringBuilder();
                    for (JsonNode element : line) {
                        String tag = element.path("tag").asText("");
                        if ("text".equals(tag) || "a".equals(tag)) {
                            lineText.append(element.path("text").asText(""));
                        }
                    }
                    String trimmed = lineText.toString().trim();
                    if (!trimmed.isEmpty()) {
                        parts.add(trimmed);
                    }
                }
                String content = String.join("\n", parts);
                if (isGroup) {
                    content = stripBotMention(content);
                }
                content = content.trim();
                yield content.isEmpty() ? null
                        : textMessage(openId, chatId, chatType, messageId, threadId, content);
            }
            default -> {
                log.info("[{}] Ignoring unsupported message type: {}", service.region.label(), messageType);
                yield null;
            }
        };
    }

    /** 对照 Go：群聊剥离 {@code @_user_xxx } 前缀（可连续多个）。 */
    static String stripBotMention(String content) {
        String value = content == null ? "" : content;
        while (value.startsWith("@_user_")) {
            int idx = value.indexOf(' ');
            if (idx < 0) {
                break;
            }
            value = value.substring(idx + 1);
        }
        return value;
    }

    private IncomingMessage baseMessage(String openId, String chatId, String chatType,
                                        String messageId, String threadId) {
        IncomingMessage msg = new IncomingMessage();
        msg.platform = service.region.platform();
        msg.userId = openId;
        msg.userName = "";
        msg.chatId = chatId;
        msg.chatType = chatType;
        msg.messageId = messageId;
        msg.threadId = threadId;
        return msg;
    }

    private IncomingMessage textMessage(String openId, String chatId, String chatType,
                                        String messageId, String threadId, String content) {
        IncomingMessage msg = baseMessage(openId, chatId, chatType, messageId, threadId);
        msg.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        msg.content = content == null ? "" : content;
        return msg;
    }
    /** 对照 {@code decrypt}：未配 encrypt_key 直接报错；否则走共享的 feishu 解密（AES-256-CBC）。 */
    byte[] decrypt(String encrypted) throws Exception {
        if (service.encryptKey.isEmpty()) {
            throw new IllegalStateException("encrypt_key not configured");
        }
        return FeishuWecomCrypt.feishuDecrypt(service.encryptKey, encrypted);
    }

}
