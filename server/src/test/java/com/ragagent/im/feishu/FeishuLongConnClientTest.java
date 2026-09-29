package com.ragagent.im.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 飞书/Lark 长连接（W5γ3.9）：接入点协议与 ClientConfig 覆盖、数据帧分发与
 * <b>同帧回执</b>（code + biz_rt）、card 帧忽略（照 Go 不回执）、分片合包、
 * pong 带配置、事件转换（长连接分支：不设 threadId、post 取首图）。
 */
class FeishuLongConnClientTest {

    private static final class RecordingSink implements FeishuLongConnClient.AckSink {
        final List<byte[]> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(byte[] frame) {
            frames.add(frame);
        }
    }

    private FeishuLongConnClient client(List<IncomingMessage> received) {
        return new FeishuLongConnClient(FeishuRegion.FEISHU, "cli_1", "sec_1",
                "https://open.feishu.cn", null, "ch-1", (msg, cid) -> received.add(msg));
    }

    private static byte[] dataFrame(String type, Map<String, String> extraHeaders, String payload) {
        LarkFrame frame = new LarkFrame();
        frame.seqId = 1;
        frame.logId = 1;
        frame.service = 41;
        frame.method = LarkFrame.METHOD_DATA;
        frame.payloadEncoding = "json";
        frame.payloadType = type;
        frame.addHeader("type", type);
        frame.addHeader("message_id", "om_1");
        frame.addHeader("sum", "1");
        frame.addHeader("seq", "0");
        frame.addHeader("trace_id", "tr_1");
        extraHeaders.forEach(frame::addHeader);
        frame.payload = payload.getBytes(StandardCharsets.UTF_8);
        return frame.encode();
    }

    // ── 接入点 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("接入点：code=0 取 URL 并用 ClientConfig 覆盖重连/心跳参数；非 0 或缺 URL 报错")
    void parsesEndpoint() throws Exception {
        FeishuLongConnClient c = client(new ArrayList<>());
        String url = c.parseEndpointResponse(("{\"code\":0,\"data\":{\"URL\":"
                + "\"wss://gw.example/ws?device_id=d1&service_id=41\","
                + "\"ClientConfig\":{\"ReconnectCount\":5,\"ReconnectInterval\":10,"
                + "\"ReconnectNonce\":3,\"PingInterval\":30}}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("wss://gw.example/ws?device_id=d1&service_id=41", url);
        Map<String, Integer> params = c.connectionParams();
        assertEquals(5, params.get("reconnectCount"));
        assertEquals(10, params.get("reconnectIntervalSeconds"));
        assertEquals(3, params.get("reconnectNonce"));
        assertEquals(30, params.get("pingIntervalSeconds"));

        // 默认值（照 SDK NewClient）：-1 无限 / 120s / 30 / 120s
        Map<String, Integer> defaults = client(new ArrayList<>()).connectionParams();
        assertEquals(-1, defaults.get("reconnectCount"));
        assertEquals(120, defaults.get("reconnectIntervalSeconds"));
        assertEquals(30, defaults.get("reconnectNonce"));
        assertEquals(120, defaults.get("pingIntervalSeconds"));

        assertThrows(IllegalStateException.class, () -> c.parseEndpointResponse(
                "{\"code\":1,\"msg\":\"system busy\"}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> c.parseEndpointResponse(
                "{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("41", FeishuLongConnClient.queryParam(
                URI.create("wss://gw/ws?device_id=d1&service_id=41"), "service_id"));
        assertEquals("", FeishuLongConnClient.queryParam(URI.create("wss://gw/ws"), "x"));
    }

    // ── 数据帧 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("事件帧：转统一消息并回同帧 ack（code=200 + biz_rt + message_id 保留）")
    void dispatchesEventAndAcks() {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        FeishuLongConnClient c = client(received);
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        c.handleFrame(dataFrame("event", Map.of(),
                "{\"schema\":\"2.0\",\"header\":{\"eventType\":\"im.message.receive_v1\"},"
                        + "\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"ou_1\"}},"
                        + "\"message\":{\"message_id\":\"m1\",\"message_type\":\"text\","
                        + "\"chat_type\":\"p2p\",\"content\":\"{\\\"text\\\":\\\"你好\\\"}\"}}}"));

        assertEquals(1, received.size());
        IncomingMessage msg = received.get(0);
        assertEquals(ImTypes.PLATFORM_FEISHU, msg.platform);
        assertEquals("你好", msg.content);
        assertEquals("", msg.threadId);   // 长连接分支不设 threadId（照 Go）

        assertEquals(1, sink.frames.size());
        LarkFrame ack = LarkFrame.decode(sink.frames.get(0));
        assertEquals("om_1", ack.header("message_id"));
        assertTrue(ack.header("biz_rt").matches("\\d+"));
        assertEquals("{\"code\":200}", new String(ack.payload, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("card 帧照 Go 忽略（不回执）；处理器异常回 code=500；未知事件类型仍回 200")
    void handlesCardAndErrors() {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        FeishuLongConnClient c = client(received);
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        c.handleFrame(dataFrame("card", Map.of(), "{\"x\":1}"));
        assertTrue(received.isEmpty());
        assertTrue(sink.frames.isEmpty());   // card 直接 return，不回执

        // 未知 event_type → 转换返回 null，但仍回 200（照 Go 的分发器语义）
        c.handleFrame(dataFrame("event", Map.of(),
                "{\"header\":{\"eventType\":\"im.chat.updated_v1\"},\"event\":{}}"));
        assertEquals(200, codeOf(sink.frames.get(0)));
        assertTrue(received.isEmpty());

        FeishuLongConnClient failing = new FeishuLongConnClient(FeishuRegion.LARK, "a", "s",
                "https://open.larksuite.com", null, "ch", (m, cid) -> {
                    throw new IllegalStateException("boom");
                });
        RecordingSink failSink = new RecordingSink();
        failing.setAckSink(failSink);
        failing.handleFrame(dataFrame("event", Map.of(),
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":"
                        + "{\"message\":{\"message_id\":\"m2\",\"message_type\":\"text\","
                        + "\"content\":\"{\\\"text\\\":\\\"x\\\"}\"}}}"));
        assertEquals(500, codeOf(failSink.frames.get(0)));
    }

    @Test
    @DisplayName("分片：sum=2 时先攒片不回执，集齐后拼接派发（按 message_id 归并）")
    void combinesChunks() {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        FeishuLongConnClient c = client(received);
        RecordingSink sink = new RecordingSink();
        c.setAckSink(sink);

        String head = "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":"
                + "{\"message\":{\"message_id\":\"m9\",\"message_type\":\"text\",";
        String tail = "\"content\":\"{\\\"text\\\":\\\"拼片\\\"}\"}}}";

        LarkFrame first = new LarkFrame();
        first.method = LarkFrame.METHOD_DATA;
        first.addHeader("type", "event");
        first.addHeader("message_id", "om_c");
        first.addHeader("sum", "2");
        first.addHeader("seq", "0");
        first.payload = head.getBytes(StandardCharsets.UTF_8);
        c.handleFrame(first.encode());
        assertTrue(received.isEmpty());
        assertTrue(sink.frames.isEmpty());

        LarkFrame second = new LarkFrame();
        second.method = LarkFrame.METHOD_DATA;
        second.addHeader("type", "event");
        second.addHeader("message_id", "om_c");
        second.addHeader("sum", "2");
        second.addHeader("seq", "1");
        second.payload = tail.getBytes(StandardCharsets.UTF_8);
        c.handleFrame(second.encode());

        assertEquals(1, received.size());
        assertEquals("拼片", received.get(0).content);
        assertEquals(1, sink.frames.size());   // 只有集齐那次回执
    }

    @Test
    @DisplayName("pong 帧：payload 带 ClientConfig 时覆盖参数；空 payload 不动")
    void handlesPongConfig() {
        FeishuLongConnClient c = client(new ArrayList<>());
        LarkFrame pong = new LarkFrame();
        pong.method = LarkFrame.METHOD_CONTROL;
        pong.addHeader("type", "pong");
        pong.payload = "{\"PingInterval\":15}".getBytes(StandardCharsets.UTF_8);
        c.handleFrame(pong.encode());
        assertEquals(15, c.connectionParams().get("pingIntervalSeconds"));

        LarkFrame empty = new LarkFrame();
        empty.method = LarkFrame.METHOD_CONTROL;
        empty.addHeader("type", "pong");
        c.handleFrame(empty.encode());
        assertEquals(15, c.connectionParams().get("pingIntervalSeconds"));
    }

    // ── 事件转换（长连接语义） ──────────────────────────────────────────────

    @Test
    @DisplayName("转换：群聊剥 @_user_；file/image；post 取首图按图片消息；未知类型 null；不设 threadId")
    void convertsEvents() throws Exception {
        IncomingMessage group = LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_9\"}},"
                        + "\"message\":{\"message_id\":\"m1\",\"root_id\":\"r1\","
                        + "\"message_type\":\"text\",\"chat_type\":\"group\",\"chat_id\":\"oc_1\","
                        + "\"content\":\"{\\\"text\\\":\\\"@_user_1 问题\\\"}\"}}}"));
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("oc_1", group.chatId);
        assertEquals("问题", group.content);
        assertEquals("", group.threadId);   // root_id 存在也不填（照 Go 的长连接分支）

        IncomingMessage file = LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{\"message\":"
                        + "{\"message_id\":\"m2\",\"message_type\":\"file\",\"content\":"
                        + "\"{\\\"file_key\\\":\\\"fk1\\\",\\\"file_name\\\":\\\"a.pdf\\\"}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("fk1", file.fileKey);
        assertEquals("a.pdf", file.fileName);

        IncomingMessage image = LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{\"message\":"
                        + "{\"message_id\":\"m3\",\"message_type\":\"image\",\"content\":"
                        + "\"{\\\"image_key\\\":\\\"ik1\\\"}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertEquals("ik1.png", image.fileName);

        // post：文本 + 首图 → 图片消息（webhook 分支只取文本，长连接取图——照 Go 的分歧）
        IncomingMessage post = LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{\"message\":"
                        + "{\"message_id\":\"m4\",\"message_type\":\"post\",\"content\":"
                        + "\"{\\\"title\\\":\\\"标题\\\",\\\"content\\\":[[{\\\"tag\\\":\\\"text\\\","
                        + "\\\"text\\\":\\\"正文\\\"},{\\\"tag\\\":\\\"img\\\",\\\"image_key\\\":"
                        + "\\\"ik9\\\"}],[{\\\"tag\\\":\\\"img\\\",\\\"image_key\\\":\\\"ik_second\\\"}]]}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, post.messageType);
        assertEquals("ik9", post.fileKey);
        assertEquals("ik9.png", post.fileName);
        assertEquals("标题\n正文", post.content);

        // post 仅文本 → 文本消息；全空 → null
        IncomingMessage textOnly = LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{\"message\":"
                        + "{\"message_id\":\"m5\",\"message_type\":\"post\",\"content\":"
                        + "\"{\\\"content\\\":[[{\\\"tag\\\":\\\"at\\\",\\\"user_id\\\":\\\"u\\\"}]]}\"}}}"));
        assertEquals(null, textOnly);

        assertEquals(null, LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.chat.updated_v1\"},\"event\":{}}")));
        assertEquals(null, LarkEventConverter.convert(FeishuRegion.FEISHU, utf8(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{\"message\":"
                        + "{\"message_id\":\"m6\",\"message_type\":\"audio\",\"content\":\"{}\"}}}")));

        assertFalse(FeishuRegion.FEISHU.platform().isEmpty());
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static int codeOf(byte[] ackFrame) {
        LarkFrame ack = LarkFrame.decode(ackFrame);
        String payload = new String(ack.payload, StandardCharsets.UTF_8);
        return Integer.parseInt(payload.replaceAll("[^0-9]", ""));
    }
}
