package com.ragagent.im.qqbot;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.ragagent.im.runtime.ThinkDisplay;

/**
 * QQ 机器人适配器（对照 Go {@code internal/im/qqbot/adapter.go} L16-127）。
 *
 * <p>只有 {@code Adapter} 一面（Go 的编译期断言也只有它）：<b>不验签</b>
 * （{@code VerifyCallback} 恒 nil）、没有 URL 挑战、也没有流式与文件下载。
 * 发送按 {@code chat_kind} 分 C2C / 群两条路径，文本取
 * {@code FormatIMDisplayContent(..., Final)} 并 trim，空则不发（照 Go）。</p>
 */
public class QqBotAdapter implements AdapterInterfaces.Adapter {

    static final int OP_DISPATCH = 0;
    static final String EVENT_C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE";
    static final String EVENT_GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE";
    static final String EXTRA_KEY_MESSAGE_ID = "message_id";
    static final String EXTRA_KEY_CHAT_KIND = "chat_kind";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QqBotClient client;

    public QqBotAdapter(QqBotClient client) {
        this.client = client;
    }

    @Override
    public String platform() {
        return ImTypes.PLATFORM_QQBOT;
    }

    /** 对照 Go：QQBot 没有 URL verification 挑战。 */
    @Override
    public boolean handleURLVerification(CallbackExchange exchange) {
        return false;
    }

    /** 对照 Go：不做验签（恒通过）。 */
    @Override
    public Exception verifyCallback(CallbackExchange exchange) {
        return null;
    }

    @Override
    public IncomingMessage parseCallback(CallbackExchange exchange) throws Exception {
        byte[] body = exchange.body();
        JsonNode payload = body == null || body.length == 0
                ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        return parseGatewayPayload(payload);
    }

    @Override
    public void sendReply(IncomingMessage incoming, ReplyMessage reply) throws Exception {
        String content = ThinkDisplay.formatIMDisplayContent(
                reply.content, ThinkDisplay.STREAM_DISPLAY_FINAL).trim();
        if (content.isEmpty()) {
            return;
        }
        String msgId = incoming.extra == null
                ? "" : incoming.extra.getOrDefault(EXTRA_KEY_MESSAGE_ID, "");
        if (ImTypes.CHAT_TYPE_GROUP.equals(incoming.chatType)) {
            client.sendGroupMessage(incoming.chatId, content, msgId);
            return;
        }
        client.sendC2CMessage(incoming.userId, content, msgId);
    }

    // ── 解析（也供网关 WS 复用） ───────────────────────────────────────────

    /** 对照 {@code parseGatewayPayload}：非 dispatch（op≠0）或未知事件类型 → null。 */
    static IncomingMessage parseGatewayPayload(JsonNode payload) throws Exception {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        if (payload.path("op").asInt(0) != OP_DISPATCH) {
            return null;
        }
        JsonNode event = payload.path("d");
        String type = payload.path("t").asText("");
        if (EVENT_C2C_MESSAGE_CREATE.equals(type)) {
            return parseC2CMessage(event);
        }
        if (EVENT_GROUP_AT_MESSAGE_CREATE.equals(type)) {
            return parseGroupMessage(event);
        }
        return null;
    }

    /** 对照 {@code parseC2CMessage}：user_id 取 user_openid 优先。 */
    static IncomingMessage parseC2CMessage(JsonNode event) {
        JsonNode author = event.path("author");
        String userId = firstNonEmpty(author.path("user_openid").asText(""),
                author.path("id").asText(""));
        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_QQBOT;
        msg.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        msg.userId = userId;
        msg.userName = author.path("username").asText("");
        msg.chatId = "";
        msg.chatType = ImTypes.CHAT_TYPE_DIRECT;
        msg.content = event.path("content").asText("").trim();
        msg.messageId = event.path("id").asText("");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(EXTRA_KEY_MESSAGE_ID, msg.messageId);
        extra.put(EXTRA_KEY_CHAT_KIND, "c2c");
        msg.extra = extra;
        return msg;
    }

    /** 对照 {@code parseGroupMessage}：user_id 取 member_openid → user_openid → id。 */
    static IncomingMessage parseGroupMessage(JsonNode event) {
        JsonNode author = event.path("author");
        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_QQBOT;
        msg.messageType = ImTypes.MESSAGE_TYPE_TEXT;
        msg.userId = firstNonEmpty(author.path("member_openid").asText(""),
                author.path("user_openid").asText(""), author.path("id").asText(""));
        msg.userName = author.path("username").asText("");
        msg.chatId = event.path("group_openid").asText("");
        msg.chatType = ImTypes.CHAT_TYPE_GROUP;
        msg.content = event.path("content").asText("").trim();
        msg.messageId = event.path("id").asText("");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(EXTRA_KEY_MESSAGE_ID, msg.messageId);
        extra.put(EXTRA_KEY_CHAT_KIND, "group");
        msg.extra = extra;
        return msg;
    }

    /** 对照 {@code firstNonEmpty}：取第一个 trim 后非空的值。 */
    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
