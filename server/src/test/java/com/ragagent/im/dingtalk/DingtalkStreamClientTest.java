package com.ragagent.im.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 钉钉 Stream 模式（W5γ3.8）协议级测试：接入点请求/响应校验、三类订阅、帧分流
 * （SYSTEM ping/disconnect、CALLBACK 机器人消息、未知 topic 404）、回执形状（含 messageId 回填）、
 * 处理器异常折 500、流帧 → 统一消息（robot_code 回落 clientId）。
 *
 * <p>不起真 WS（照 wecom/qqbot 长连接的测试粒度）：WS 收包用 {@code offerFrame} 直接投递，
 * ack 用注入的记录器观察。</p>
 */
class DingtalkStreamClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class RecordingSink implements DingtalkStreamClient.AckSink {
        final List<String> acks = new CopyOnWriteArrayList<>();

        @Override
        public void send(String json) {
            acks.add(json);
        }
    }

    private DingtalkStreamClient client(List<IncomingMessage> received) {
        DingtalkStreamClient c = new DingtalkStreamClient("cli_1", "sec_1",
                "https://api.dingtalk.com", null, "ch-1",
                (msg, cid) -> received.add(msg));
        c.asyncCallbacks = false; // 保测试确定性
        return c;
    }

    @Test
    @DisplayName("接入点请求：clientId/clientSecret/三类订阅/ua/localIp；响应缺字段或非 wss 报错")
    void endpointRequestAndValidation() throws Exception {
        DingtalkStreamClient c = client(new ArrayList<>());
        String body = c.buildEndpointRequest();
        JsonNode node = MAPPER.readTree(body);
        assertEquals("cli_1", node.path("clientId").asText());
        assertEquals("sec_1", node.path("clientSecret").asText());
        assertEquals("weknora-java", node.path("ua").asText());
        assertNotNull(node.get("localIp"));
        assertNotNull(node.get("extras"));
        JsonNode subs = node.path("subscriptions");
        assertEquals(3, subs.size());
        assertEquals("SYSTEM", subs.get(0).path("type").asText());
        assertEquals("ping", subs.get(0).path("topic").asText());
        assertEquals("SYSTEM", subs.get(1).path("type").asText());
        assertEquals("disconnect", subs.get(1).path("topic").asText());
        assertEquals("CALLBACK", subs.get(2).path("type").asText());
        assertEquals("/v1.0/im/bot/messages/get", subs.get(2).path("topic").asText());

        var endpoint = c.parseEndpointResponse(
                "{\"endpoint\":\"wss://gw.example/ws\",\"ticket\":\"tk-1\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("wss://gw.example/ws", endpoint.endpoint());
        assertEquals("tk-1", endpoint.ticket());

        assertThrows(IllegalStateException.class,
                () -> c.parseEndpointResponse("{\"endpoint\":\"wss://x\"}".getBytes()));
        // 非 wss 拒绝（本仓长连接一贯校验）
        assertThrows(IllegalStateException.class,
                () -> c.parseEndpointResponse(
                        "{\"endpoint\":\"ws://x\",\"ticket\":\"t\"}".getBytes()));
    }

    @Test
    @DisplayName("帧分流：ping 回 {200,ok,data 原样}；未知 topic 404；messageId 始终回填")
    void dispatchesSystemFrames() throws Exception {
        DingtalkStreamClient c = client(new ArrayList<>());
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        c.dispatch("{\"specVersion\":\"1.0\",\"type\":\"SYSTEM\",\"headers\":"
                + "{\"topic\":\"ping\",\"messageId\":\"mid-1\",\"contentType\":\"text\"},"
                + "\"data\":\"PINGDATA\"}");
        assertEquals(1, sink.acks.size());
        JsonNode pong = MAPPER.readTree(sink.acks.get(0));
        assertEquals(200, pong.path("code").asInt());
        assertEquals("ok", pong.path("message").asText());
        assertEquals("PINGDATA", pong.path("data").asText());
        assertEquals("mid-1", pong.path("headers").path("messageId").asText());
        assertEquals("application/json", pong.path("headers").path("contentType").asText());

        c.dispatch("{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"something\","
                + "\"messageId\":\"mid-2\"},\"data\":\"\"}");
        assertEquals(404, MAPPER.readTree(sink.acks.get(1)).path("code").asInt());
    }

    @Test
    @DisplayName("disconnect：先回执 200 再关连接（哨兵入队）")
    void handlesDisconnect() throws Exception {
        DingtalkStreamClient c = client(new ArrayList<>());
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        c.dispatch("{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"disconnect\","
                + "\"messageId\":\"mid-9\"},\"data\":\"\"}");
        assertEquals(200, MAPPER.readTree(sink.acks.get(0)).path("code").asInt());
        // 关连接 = 往消费队列投哨兵 → consumeLoop 会立刻返回
        c.offerFrame("{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"ping\"},\"data\":\"x\"}");
        c.consumeLoop(); // 哨兵先于 ping → 直接退出（不阻塞）
    }

    @Test
    @DisplayName("机器人回调：解析为统一消息并回 200；处理器抛错折 500；未知 topic 回 404")
    void dispatchesBotCallbacks() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        DingtalkStreamClient c = client(received);
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        c.dispatch("{\"type\":\"CALLBACK\",\"headers\":{\"topic\":\"/v1.0/im/bot/messages/get\","
                + "\"messageId\":\"mid-3\",\"contentType\":\"application/json\"},"
                + "\"data\":\"{\\\"conversationType\\\":\\\"1\\\",\\\"msgId\\\":\\\"m1\\\","
                + "\\\"msgtype\\\":\\\"text\\\",\\\"senderStaffId\\\":\\\"u1\\\","
                + "\\\"sessionWebhook\\\":\\\"https://hook/x\\\","
                + "\\\"text\\\":{\\\"content\\\":\\\"你好\\\"}}\"}");
        assertEquals(1, received.size());
        IncomingMessage msg = received.get(0);
        assertEquals(ImTypes.PLATFORM_DINGTALK, msg.platform);
        assertEquals("你好", msg.content);
        assertEquals("https://hook/x", msg.extra.get("session_webhook"));
        JsonNode ack = MAPPER.readTree(sink.acks.get(0));
        assertEquals(200, ack.path("code").asInt());
        assertEquals("mid-3", ack.path("headers").path("messageId").asText());

        // 处理器抛错 → 500 + message 带错误
        DingtalkStreamClient failing = new DingtalkStreamClient("cli", "sec", null, null, "ch",
                (m, cid) -> {
                    throw new IllegalStateException("boom");
                });
        failing.asyncCallbacks = false;
        RecordingSink failSink = new RecordingSink();
        failing.setAckSink(failSink);
        failing.dispatch("{\"type\":\"CALLBACK\",\"headers\":"
                + "{\"topic\":\"/v1.0/im/bot/messages/get\",\"messageId\":\"mid-4\"},"
                + "\"data\":\"{\\\"msgtype\\\":\\\"text\\\",\\\"text\\\":{\\\"content\\\":\\\"x\\\"}}\"}");
        JsonNode failAck = MAPPER.readTree(failSink.acks.get(0));
        assertEquals(500, failAck.path("code").asInt());
        assertTrue(failAck.path("message").asText().contains("boom"));

        // CALLBACK 但 topic 不认识 → 404
        c.dispatch("{\"type\":\"CALLBACK\",\"headers\":{\"topic\":\"/v1.0/other\","
                + "\"messageId\":\"mid-5\"},\"data\":\"{}\"}");
        assertEquals(404, MAPPER.readTree(sink.acks.get(1)).path("code").asInt());
    }

    @Test
    @DisplayName("流帧 → 统一消息：与 webhook 同一解析；文件/图片的 robot_code 回落 clientId")
    void convertsStreamFrames() throws Exception {
        JsonNode fileFrame = MAPPER.readTree(
                "{\"type\":\"CALLBACK\",\"headers\":{\"topic\":\"/v1.0/im/bot/messages/get\"},"
                        + "\"data\":\"{\\\"conversationId\\\":\\\"cid\\\","
                        + "\\\"conversationType\\\":\\\"2\\\",\\\"msgId\\\":\\\"m9\\\","
                        + "\\\"msgtype\\\":\\\"file\\\","
                        + "\\\"content\\\":{\\\"downloadCode\\\":\\\"dc\\\","
                        + "\\\"fileName\\\":\\\"a.pdf\\\"}}\"}");
        IncomingMessage file = DingtalkStreamClient.streamToIncoming(fileFrame, "cli_1");
        assertEquals(ImTypes.CHAT_TYPE_GROUP, file.chatType);
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("dc", file.fileKey);
        assertEquals("cli_1", file.extra.get("robot_code")); // Stream 载荷无 robotCode

        JsonNode textFrame = MAPPER.readTree(
                "{\"type\":\"CALLBACK\",\"headers\":{},\"data\":"
                        + "\"{\\\"msgType\\\":\\\"text\\\",\\\"msgtype\\\":\\\"text\\\","
                        + "\\\"text\\\":{\\\"content\\\":\\\"hi\\\"}}\"}");
        IncomingMessage text = DingtalkStreamClient.streamToIncoming(textFrame, "cli_1");
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, text.messageType);
        assertEquals("hi", text.content);
        assertTrue(text.extra.getOrDefault("robot_code", "").isEmpty());
    }

    @Test
    @DisplayName("回执形状：messageId 回填、contentType 恒 json、data 缺省空串")
    void buildsAckShape() throws Exception {
        JsonNode frame = MAPPER.readTree(
                "{\"type\":\"SYSTEM\",\"headers\":{\"messageId\":\"m-7\"},\"data\":\"\"}");
        JsonNode ack = MAPPER.readTree(DingtalkStreamClient.buildAck(frame, 404, null, null));
        assertEquals(404, ack.path("code").asInt());
        assertEquals("", ack.path("message").asText());
        assertEquals("", ack.path("data").asText());
        assertEquals("m-7", ack.path("headers").path("messageId").asText());
        assertEquals("application/json", ack.path("headers").path("contentType").asText());
    }

    @Test
    @DisplayName("工厂：websocket（Go 默认）建长连接 + 给 stop；webhook 无 stop；未知模式报错")
    void factoryWiresStreamMode() throws Exception {
        com.ragagent.im.domain.ImChannelEntity ws = new com.ragagent.im.domain.ImChannelEntity();
        ws.setId("ch-ws");
        ws.setMode("websocket");
        ws.setCredentials("{\"client_id\":\"cli\",\"client_secret\":\"sec\"}");
        // 接入点指向不可达端口：守护线程连不上会退避重试，stop 后退出（不阻塞测试）
        var reg = new DingtalkAdapterFactory(null, "http://127.0.0.1:1")
                .create(ws, (m, cid) -> { });
        assertNotNull(reg.adapter());
        assertNotNull(reg.stop());
        reg.stop();

        com.ragagent.im.domain.ImChannelEntity hook = new com.ragagent.im.domain.ImChannelEntity();
        hook.setId("ch-hook");
        hook.setMode("webhook");
        hook.setCredentials("{\"client_id\":\"cli\",\"client_secret\":\"sec\"}");
        var hookReg = new DingtalkAdapterFactory(null, "http://127.0.0.1:1")
                .create(hook, (m, cid) -> { });
        assertNotNull(hookReg.adapter());
        org.junit.jupiter.api.Assertions.assertNull(hookReg.stop());

        com.ragagent.im.domain.ImChannelEntity bad = new com.ragagent.im.domain.ImChannelEntity();
        bad.setId("ch-bad");
        bad.setMode("pigeon");
        bad.setCredentials("{}");
        assertThrows(IllegalArgumentException.class,
                () -> new DingtalkAdapterFactory(null, "http://127.0.0.1:1")
                        .create(bad, (m, cid) -> { }));
    }
}
