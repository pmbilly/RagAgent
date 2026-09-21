package com.ragagent.im.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Slack 适配器确定性核心（对照 slack/adapter.go 的 URL verification、事件分支、
 * 提及剥离；mattermost outgoing payload 双态解析）。
 */
class SlackAdapterCoreTest {

    @Test
    void urlVerificationChallengeEcho() {
        var exchange = new FakeExchange(
                "{\"type\":\"url_verification\",\"challenge\":\"ch-123\"}".getBytes());
        assertTrue(SlackAdapterCore.handleURLVerification(exchange));
        assertEquals("{\"challenge\":\"ch-123\"}", new String(exchange.writtenBody));
        assertEquals(200, exchange.writtenStatus);
    }

    @Test
    void urlVerificationNonChallengePassesThrough() {
        var exchange = new FakeExchange("{\"type\":\"event_callback\"}".getBytes());
        assertFalse(SlackAdapterCore.handleURLVerification(exchange));
        assertEquals(0, exchange.writtenStatus);
    }

    @Test
    void appMentionEventStripsLeadingMention() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"app_mention\","
                + "\"user\":\"U1\",\"channel\":\"C1\",\"text\":\"<@U999> 你好 机器人\","
                + "\"ts\":\"1700.0001\"}}";
        IncomingMessage m = SlackAdapterCore.parseCallback(body.getBytes(StandardCharsets.UTF_8));
        assertEquals(ImTypes.PLATFORM_SLACK, m.platform);
        assertEquals("U1", m.userId);
        assertEquals("C1", m.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, m.chatType);
        assertEquals("你好 机器人", m.content);
        assertEquals("1700.0001", m.messageId);
        assertEquals("1700.0001", m.threadId);
    }

    @Test
    void messageEventFiltersBotAndSubType() throws Exception {
        String bot = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"bot_id\":\"B1\",\"text\":\"hi\",\"channel_type\":\"channel\"}}";
        assertNull(SlackAdapterCore.parseCallback(bot.getBytes(StandardCharsets.UTF_8)));

        String system = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"subtype\":\"message_changed\",\"text\":\"hi\"}}";
        assertNull(SlackAdapterCore.parseCallback(system.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void messageEventDirectAndThreadTs() throws Exception {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\","
                + "\"user\":\"U2\",\"channel\":\"D1\",\"text\":\"hello\","
                + "\"channel_type\":\"im\",\"thread_ts\":\"1700.9\",\"ts\":\"1700.2\"}}";
        IncomingMessage m = SlackAdapterCore.parseCallback(body.getBytes(StandardCharsets.UTF_8));
        assertEquals(ImTypes.CHAT_TYPE_DIRECT, m.chatType);
        assertEquals("1700.9", m.threadId);
        assertEquals("1700.9", m.messageId);
    }

    @Test
    void mattermostOutgoingBodyParsing() {
        Map<String, String> form = SlackAdapterCore.parseOutgoingBody(
                "application/x-www-form-urlencoded", "token=abc&text=hello+world".getBytes());
        assertEquals("abc", form.get("token"));
        assertEquals("hello world", form.get("text"));

        Map<String, String> json = SlackAdapterCore.parseOutgoingBody("application/json",
                "{\"token\":\"t2\",\"text\":\"你好\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("t2", json.get("token"));
        assertEquals("你好", json.get("text"));
    }

    /** 极简 exchange fake。 */
    static class FakeExchange implements CallbackExchange {
        final byte[] body;
        int writtenStatus;
        String writtenBody = "";

        FakeExchange(byte[] body) {
            this.body = body;
        }

        @Override public String method() { return "POST"; }
        @Override public String query(String name) { return ""; }
        @Override public String header(String name) { return ""; }
        @Override public Map<String, String> headers() { return Map.of(); }
        @Override public byte[] body() { return body; }
        @Override public void json(int status, Object body) {
            writtenStatus = status;
            writtenBody = CallbackExchange.ImJson.encode(body);
        }
        @Override public void plain(int status, String contentType, String text) {
            writtenStatus = status;
            writtenBody = text;
        }
        @Override public boolean committed() { return writtenStatus != 0; }
    }
}
