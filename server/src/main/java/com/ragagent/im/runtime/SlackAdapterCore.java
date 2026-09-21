package com.ragagent.im.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slack 适配器的确定性核心（对照 Go internal/im/slack/adapter.go，波 5 W5γ3 翻译）：
 * URL verification 挑战回显、事件解析（AppMention/Message 分支、bot/subType 过滤、
 * thread_ts 语义、&lt;@U…&gt; 提及剥离）。出站发送（chat.postMessage 等 HTTP 调用）
 * 属 provider-XDEP。
 */
public final class SlackAdapterCore {

    private SlackAdapterCore() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 对照 HandleURLVerification（adapter.go L?-?）：type=url_verification →
     * 写 {"challenge": <challenge>} 返回 true。非挑战请求不动响应。
     *
     * @return true 表示该请求是 URL 验证且已处理
     */
    public static boolean handleURLVerification(CallbackExchange exchange) {
        try {
            JsonNode body = JSON.readTree(exchange.body());
            if (body == null || !body.path("type").asText("").equals("url_verification")) {
                return false;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("challenge", body.path("challenge").asText(""));
            exchange.json(200, resp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对照 ParseCallback 的 CallbackEvent 分支：AppMention（群内提及）与
     * Message（私聊/频道消息；bot 或带 subType 的系统事件 → null）。非消息
     * 事件（如 app_home_opened）→ null。
     */
    public static IncomingMessage parseCallback(byte[] bodyBytes) throws Exception {
        JsonNode root = JSON.readTree(bodyBytes);
        String eventType = root.path("type").asText("");
        if (!eventType.equals("event_callback")) {
            return null;
        }
        JsonNode event = root.path("event");
        String innerType = event.path("type").asText("");

        switch (innerType) {
            case "app_mention": {
                String threadTs = event.path("thread_ts").asText("");
                if (threadTs.isEmpty()) {
                    threadTs = event.path("ts").asText("");
                }
                return parseIncomingMessage(event.path("user").asText(""),
                        event.path("channel").asText(""), event.path("text").asText(""),
                        threadTs, ImTypes.CHAT_TYPE_GROUP);
            }
            case "message": {
                // bot 消息与系统 subType 不回环处理（file_share 除外）
                String botId = event.path("bot_id").asText("");
                String subType = event.path("subtype").asText("");
                if (!botId.isEmpty() || (!subType.isEmpty() && !subType.equals("file_share"))) {
                    return null;
                }
                String channelType = event.path("channel_type").asText("");
                String chatType = channelType.equals("channel") || channelType.equals("group")
                        ? ImTypes.CHAT_TYPE_GROUP
                        : ImTypes.CHAT_TYPE_DIRECT;
                String threadTs = event.path("thread_ts").asText("");
                if (threadTs.isEmpty()) {
                    threadTs = event.path("ts").asText("");
                }
                // Go 把 threadTs 传给 parseIncomingMessage 的 ts 参数——
                // MessageID 与 ThreadID 都是它（adapter.go L162-166）。
                return parseIncomingMessage(event.path("user").asText(""),
                        event.path("channel").asText(""), event.path("text").asText(""),
                        threadTs, chatType);
            }
            default:
                return null;
        }
    }

    /** Go L52-75 逐行：群聊剥离前导 &lt;@U…&gt; 提及；ts 即 MessageID 与 ThreadID。 */
    static IncomingMessage parseIncomingMessage(String user, String channel, String text,
            String ts, String chatType) {
        String content = text == null ? "" : text;
        if (chatType.equals(ImTypes.CHAT_TYPE_GROUP)) {
            while (content.startsWith("<@")) {
                int idx = content.indexOf('>');
                if (idx >= 0) {
                    content = content.substring(idx + 1).strip();
                } else {
                    break;
                }
            }
        }
        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_SLACK;
        msg.userId = user;
        msg.chatId = channel;
        msg.chatType = chatType;
        msg.content = content.strip();
        msg.messageId = ts;
        msg.threadId = ts;
        return msg;
    }

    /** 对照 outgoing payload 解析（mattermost/adapter.go）：form/JSON 双态取 token/text。 */
    public static Map<String, String> parseOutgoingBody(String contentType, byte[] bodyBytes) {
        Map<String, String> out = new LinkedHashMap<>();
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.startsWith("application/x-www-form-urlencoded")) {
            for (String pair : new java.lang.String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
                }
            }
            return out;
        }
        try {
            JsonNode node = JSON.readTree(bodyBytes);
            if (node != null && node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var e = fields.next();
                    JsonNode v = e.getValue();
                    out.put(e.getKey(), v.isValueNode() ? v.asText("") : v.toString());
                }
            }
        } catch (Exception ignored) {
            // 坏 JSON：空 map（Go 的 Unmarshal error 上抛 → parse failed；调用方处理）
        }
        return out;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 供 CallbackExchange 测试侧使用的事件列表。 */
    public static List<String> supportedEventTypes() {
        return List.of("app_mention", "message");
    }
}
