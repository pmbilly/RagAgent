package com.ragagent.im.feishu;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 长连接事件帧 → 统一消息（对照 Go {@code internal/im/feishu/longconn.go} 的
 * {@code convertEvent}/{@code convertTextEvent}/{@code convertFileEvent}/
 * {@code convertImageEvent}/{@code convertPostEvent}，L111-360）。
 *
 * <p>与 webhook 的 {@code ParseCallback} 有三处<b>刻意不同</b>（都是照 Go 的既有分歧）：</p>
 * <ol>
 *   <li><b>不设 threadId</b>——Go 的长连接分支不填 {@code ThreadID}（只有 root_id/message_id
 *       的 webhook 分支填）；</li>
 *   <li><b>post 会提取首张内嵌图</b>：{@code tag == "img"} 取第一个 {@code image_key}，
 *       有图则整条按<b>图片消息</b>返回（content 仍是拼好的文本、fileName = {@code <image_key>.png}）；
 *       webhook 的 post 分支只取文本；</li>
 *   <li>只认 {@code im.message.receive_v1}，其余事件返回 null（照 SDK 只注册该事件）。</li>
 * </ol>
 */
public final class LarkEventConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String EVENT_TYPE_MESSAGE_RECEIVE = "im.message.receive_v1";

    private LarkEventConverter() {
    }

    /** 对照 {@code convertEvent}；无法识别时返回 null（照 Go）。 */
    public static IncomingMessage convert(FeishuRegion region, byte[] eventJson) throws Exception {
        JsonNode root = MAPPER.readTree(eventJson == null ? new byte[0] : eventJson);
        if (!EVENT_TYPE_MESSAGE_RECEIVE.equals(root.path("header").path("event_type").asText(""))) {
            return null;
        }
        JsonNode message = root.path("event").path("message");
        if (message.isMissingNode() || message.isNull()) {
            return null;
        }
        String msgType = message.path("message_type").asText("");
        if (msgType.isEmpty()) {
            return null;
        }

        String openId = root.path("event").path("sender").path("sender_id")
                .path("open_id").asText("");
        boolean isGroup = "group".equals(message.path("chat_type").asText(""));
        String chatId = isGroup ? message.path("chat_id").asText("") : "";
        String messageId = message.path("message_id").asText("");
        String content = message.path("content").asText("");

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = region.platform();
        incoming.userId = openId;
        incoming.chatId = chatId;
        incoming.chatType = isGroup ? ImTypes.CHAT_TYPE_GROUP : ImTypes.CHAT_TYPE_DIRECT;
        incoming.messageId = messageId;

        switch (msgType) {
            case "text" -> {
                JsonNode text = MAPPER.readTree(content.isEmpty() ? "{}" : content);
                String body = text.path("text").asText("");
                if (isGroup) {
                    body = FeishuAdapter.stripBotMention(body);
                }
                incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
                incoming.content = body.trim();
                return incoming;
            }
            case "file" -> {
                JsonNode file = MAPPER.readTree(content.isEmpty() ? "{}" : content);
                String fileKey = file.path("file_key").asText("");
                if (fileKey.isEmpty()) {
                    return null;
                }
                incoming.messageType = ImTypes.MESSAGE_TYPE_FILE;
                incoming.fileKey = fileKey;
                incoming.fileName = file.path("file_name").asText("");
                return incoming;
            }
            case "image" -> {
                JsonNode image = MAPPER.readTree(content.isEmpty() ? "{}" : content);
                String imageKey = image.path("image_key").asText("");
                if (imageKey.isEmpty()) {
                    return null;
                }
                incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
                incoming.fileKey = imageKey;
                incoming.fileName = imageKey + ".png";
                return incoming;
            }
            case "post" -> {
                JsonNode post = MAPPER.readTree(content.isEmpty() ? "{}" : content);
                List<String> parts = new ArrayList<>();
                String imageKey = "";
                String title = post.path("title").asText("");
                if (!title.isEmpty()) {
                    parts.add(title);
                }
                for (JsonNode line : post.path("content")) {
                    StringBuilder lineText = new StringBuilder();
                    for (JsonNode element : line) {
                        String tag = element.path("tag").asText("");
                        switch (tag) {
                            case "text", "a" -> lineText.append(element.path("text").asText(""));
                            case "img" -> {
                                if (imageKey.isEmpty()) {
                                    imageKey = element.path("image_key").asText("");
                                }
                            }
                            default -> {
                                // at 等标签照 Go 跳过
                            }
                        }
                    }
                    String trimmed = lineText.toString().trim();
                    if (!trimmed.isEmpty()) {
                        parts.add(trimmed);
                    }
                }
                String joined = String.join("\n", parts);
                if (isGroup) {
                    joined = stripBotMention(joined);
                }
                joined = joined.trim();
                if (joined.isEmpty() && imageKey.isEmpty()) {
                    return null;
                }
                if (!imageKey.isEmpty()) {
                    incoming.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
                    incoming.fileKey = imageKey;
                    incoming.fileName = imageKey + ".png";
                } else {
                    incoming.messageType = ImTypes.MESSAGE_TYPE_TEXT;
                }
                incoming.content = joined;
                return incoming;
            }
            default -> {
                return null;
            }
        }
    }

    /** 与适配器共用同一剥离逻辑（长连接与 webhook 一致）。 */
    private static String stripBotMention(String content) {
        return FeishuAdapter.stripBotMention(content);
    }
}
