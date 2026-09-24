package com.ragagent.im.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * 钉钉适配器（W5γ3.7）对照 Go {@code internal/im/dingtalk/adapter.go}：
 * 验签（HMAC + 时间窗）、解析四段链（richText/file/picture/audio/text，含多图提示与
 * 首图提取）、下载（downloadCode → 临时 URL → 白名单）、发送（sessionWebhook 优先 /
 * OpenAPI 群与私聊两条）、流式（AI 卡片建/更新/节流/定稿，无卡退回 webhook 整段）。
 */
class DingtalkAdapterTest {

    private record Captured(String method, String path, String query, String body,
                            Map<String, List<String>> headers) {
    }

    private record StubResponse(int status, String contentType, byte[] body) {
        static StubResponse json(String json) {
            return new StubResponse(200, "application/json",
                    json.getBytes(StandardCharsets.UTF_8));
        }

        static StubResponse raw(byte[] data) {
            return new StubResponse(200, "application/octet-stream", data);
        }
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, StubResponse> responder =
            c -> StubResponse.json("{\"accessToken\":\"T1\",\"expireIn\":7200}");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Captured req = new Captured(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(),
                    new String(raw, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders());
            captured.add(req);
            StubResponse resp = responder.apply(req);
            exchange.getResponseHeaders().add("Content-Type", resp.contentType());
            exchange.sendResponseHeaders(resp.status(), resp.body().length);
            exchange.getResponseBody().write(resp.body());
            exchange.close();
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        DingtalkAdapter.STREAMS.clear();
    }

    private DingtalkAdapter adapter(String clientSecret, String cardTemplateId) {
        return new DingtalkAdapter("ding_client", clientSecret, cardTemplateId, apiBase, null);
    }

    /** token 端点固定应答，其余走各自 responder。 */
    private void stubTokenAnd(Function<Captured, StubResponse> rest) {
        responder = req -> req.path().endsWith("/oauth2/accessToken")
                ? StubResponse.json("{\"accessToken\":\"T1\",\"expireIn\":7200}")
                : rest.apply(req);
    }

    // ── 验签 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("验签：HMAC 基串 <timestamp>\\n<secret>；缺头/过期/错签各自报错；空 secret 跳过")
    void verifiesSignature() {
        String secret = "sec-1";
        DingtalkAdapter a = adapter(secret, "");

        String now = String.valueOf(Instant.now().toEpochMilli());
        String good = ImAdapterVerify.dingtalkExpectedSignature(secret, now);
        assertNull(a.verifyCallback(new HeaderExchange(Map.of("Timestamp", now, "Sign", good))));

        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(new HeaderExchange(Map.of())));
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(new HeaderExchange(Map.of("Timestamp", "abc", "Sign", good))));

        String old = String.valueOf(Instant.now().minusSeconds(3700).toEpochMilli());
        assertEquals("timestamp expired", assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(new HeaderExchange(
                        Map.of("Timestamp", old, "Sign", good)))).getMessage());

        assertEquals("invalid signature",
                assertInstanceOf(AdapterInterfaces.VerifyException.class,
                        a.verifyCallback(new HeaderExchange(
                                Map.of("Timestamp", now, "Sign", "AAAA")))).getMessage());

        // 空 clientSecret → 跳过验签
        assertNull(adapter("", "").verifyCallback(new HeaderExchange(Map.of())));
        // 钉钉不走 URL 挑战
        assertTrue(!a.handleURLVerification(new HeaderExchange(Map.of())));
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：群聊判 2 / userId 回落 / richText 取首图与多图提示 / file / picture / audio")
    void parsesCallbacks() throws Exception {
        DingtalkAdapter a = adapter("sec", "");

        IncomingMessage group = a.parseCallback(new BodyExchange(
                "{\"conversationId\":\"cid-1\",\"conversationType\":\"2\",\"msgId\":\"m1\","
                        + "\"msgtype\":\"text\",\"text\":{\"content\":\" 你好 \"},"
                        + "\"senderNick\":\"张三\",\"senderId\":\"u_legacy\","
                        + "\"sessionWebhook\":\"https://hook/x\"}"));
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("cid-1", group.chatId);
        assertEquals("u_legacy", group.userId); // senderStaffId 缺省回落 senderId
        assertEquals("张三", group.userName);
        assertEquals("你好", group.content);
        assertEquals("https://hook/x", group.extra.get("session_webhook"));
        assertEquals("text", group.extra.get("raw_msgtype"));

        IncomingMessage rich = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m2\",\"msgtype\":\"richText\","
                        + "\"robotCode\":\"robot-1\",\"senderStaffId\":\"u1\",\"content\":{"
                        + "\"richText\":[{\"text\":\"第一段\",\"type\":\"text\"},"
                        + "{\"type\":\"picture\",\"pictureDownloadCode\":\"pic-1\"},"
                        + "{\"text\":\"第二段\",\"type\":\"text\"},"
                        + "{\"type\":\"picture\",\"downloadCode\":\"pic-2\"}]}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, rich.messageType);
        assertEquals("第一段\n第二段\n（该消息共 2 张图片，当前仅处理第一张）", rich.content);
        assertEquals("pic-1", rich.fileKey); // 首图（原图码优先）
        assertEquals("m2.png", rich.fileName);
        assertEquals("2", rich.extra.get("rich_text_picture_count"));
        assertEquals("robot-1", rich.extra.get("robot_code"));

        IncomingMessage richTextOnly = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m3\",\"msgtype\":\"richText\","
                        + "\"content\":{\"richText\":[{\"text\":\"只有文字\",\"type\":\"text\"}]}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, richTextOnly.messageType);
        assertEquals("只有文字", richTextOnly.content);
        assertTrue(richTextOnly.fileKey.isEmpty());
        assertTrue(richTextOnly.extra.getOrDefault("rich_text_picture_count", "").isEmpty());

        IncomingMessage file = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m4\",\"msgtype\":\"file\","
                        + "\"content\":{\"downloadCode\":\"dc-1\",\"fileName\":\"报告.pdf\"}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("报告.pdf", file.fileName);
        assertEquals("dc-1", file.fileKey);

        IncomingMessage picture = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m5\",\"msgtype\":\"picture\","
                        + "\"content\":{\"downloadCode\":\"dc-2\",\"fileName\":\"忽略.jpg\"}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, picture.messageType);
        assertEquals("m5.png", picture.fileName); // 图片不带原始文件名

        IncomingMessage audio = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m6\",\"msgtype\":\"audio\","
                        + "\"content\":{\"recognition\":\"语音内容\"}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, audio.messageType);
        assertEquals("语音内容", audio.content);

        // 未知类型 → 文本（内容空）
        IncomingMessage unknown = a.parseCallback(new BodyExchange(
                "{\"conversationType\":\"1\",\"msgId\":\"m7\",\"msgtype\":\"video\","
                        + "\"content\":{\"downloadCode\":\"dc-3\"}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, unknown.messageType);
        assertEquals("", unknown.content);
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：sessionWebhook 优先（markdown 直发）；否则 OpenAPI 群/私聊两条")
    void sendReplyPaths() throws Exception {
        stubTokenAnd(req -> StubResponse.json("{}"));
        DingtalkAdapter a = adapter("sec", "");

        IncomingMessage withHook = new IncomingMessage();
        withHook.platform = ImTypes.PLATFORM_DINGTALK;
        withHook.userId = "u1";
        withHook.messageId = "m1";
        withHook.extra.put("session_webhook", apiBase + "/hook");
        a.sendReply(withHook, new ReplyMessage("答案", false, true));

        assertEquals(1, captured.size());
        assertEquals("/hook", captured.get(0).path());
        assertTrue(captured.get(0).body().contains("\"msgtype\":\"markdown\""));
        assertTrue(captured.get(0).body().contains("\"title\":\"Reply\""));
        assertTrue(captured.get(0).body().contains("答案"));

        // 无 webhook → OpenAPI：群聊走 groupMessages/send
        captured.clear();
        IncomingMessage group = new IncomingMessage();
        group.platform = ImTypes.PLATFORM_DINGTALK;
        group.chatType = ImTypes.CHAT_TYPE_GROUP;
        group.chatId = "cid-1";
        group.userId = "u1";
        a.sendReply(group, new ReplyMessage("群答", false, true));

        assertEquals(2, captured.size()); // token + send
        Captured groupSend = captured.get(1);
        assertEquals("/v1.0/robot/groupMessages/send", groupSend.path());
        assertEquals("T1", groupSend.headers().get("x-acs-dingtalk-access-token").get(0));
        assertTrue(groupSend.body().contains("\"msgKey\":\"sampleMarkdown\""));
        assertTrue(groupSend.body().contains("\"openConversationId\":\"cid-1\""));
        assertTrue(groupSend.body().contains("\"robotCode\":\"ding_client\""));

        // 私聊走 oToMessages/batchSend（userIds 数组）
        captured.clear();
        IncomingMessage direct = new IncomingMessage();
        direct.platform = ImTypes.PLATFORM_DINGTALK;
        direct.chatType = ImTypes.CHAT_TYPE_DIRECT;
        direct.userId = "u2";
        a.sendReply(direct, new ReplyMessage("私答", false, true));

        Captured directSend = captured.get(captured.size() - 1);
        assertEquals("/v1.0/robot/oToMessages/batchSend", directSend.path());
        assertTrue(directSend.body().contains("\"userIds\":[\"u2\"]"));
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("下载：downloadCode 换临时 URL → 取字节；缺 code / 缺 downloadUrl 报错")
    void downloadsViaTemporaryUrl() throws Exception {
        stubTokenAnd(req -> switch (req.path()) {
            case "/v1.0/robot/messageFiles/download" ->
                    StubResponse.json("{\"downloadUrl\":\"" + apiBase + "/file.bin\"}");
            case "/file.bin" -> StubResponse.raw("PDFDATA".getBytes(StandardCharsets.UTF_8));
            default -> StubResponse.json("{}");
        });
        DingtalkAdapter a = adapter("sec", "");

        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_DINGTALK;
        msg.messageId = "m1";
        msg.fileKey = "dc-1";
        msg.fileName = "报告.pdf";
        msg.extra.put("robot_code", "robot-9");
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(msg);

        assertEquals("PDFDATA", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("报告.pdf", file.fileName());
        Captured exchange = captured.get(1);
        assertEquals("/v1.0/robot/messageFiles/download", exchange.path());
        assertTrue(exchange.body().contains("\"robotCode\":\"robot-9\""));
        assertTrue(exchange.body().contains("\"downloadCode\":\"dc-1\""));

        IncomingMessage noCode = new IncomingMessage();
        noCode.platform = ImTypes.PLATFORM_DINGTALK;
        assertThrows(IllegalArgumentException.class, () -> a.downloadFile(noCode));

        stubTokenAnd(req -> StubResponse.json("{}")); // 无 downloadUrl
        assertThrows(IllegalStateException.class, () -> a.downloadFile(msg));
    }

    // ── 流式 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("流式（AI 卡片）：建卡 → 更新（isFull/节流）→ 定稿；流 ID 是 dt:<user>:<msg>")
    void streamsWithCard() throws Exception {
        stubTokenAnd(req -> StubResponse.json("{}"));
        DingtalkAdapter a = adapter("sec", "tpl-1");

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_DINGTALK;
        incoming.userId = "u1";
        incoming.messageId = "m1";
        incoming.chatType = ImTypes.CHAT_TYPE_DIRECT;

        String streamId = a.startStream(incoming);
        assertEquals("dt:u1:m1", streamId);

        Captured create = captured.stream()
                .filter(c -> c.path().equals("/v1.0/card/instances/createAndDeliver"))
                .findFirst().orElseThrow();
        assertTrue(create.body().contains("\"cardTemplateId\":\"tpl-1\""));
        assertTrue(create.body().contains("\"callbackType\":\"STREAM\""));
        assertTrue(create.body().contains("\"openSpaceId\":\"dtv1.card//IM_ROBOT.u1\""));
        assertTrue(create.body().contains("\"outTrackId\""));

        a.updateStreamContent(incoming, streamId, "第一段");
        Captured first = captured.stream()
                .filter(c -> c.path().equals("/v1.0/card/streaming"))
                .findFirst().orElseThrow();
        assertEquals("PUT", first.method());
        assertTrue(first.body().contains("\"isFull\":true"));
        assertTrue(first.body().contains("\"isFinalize\":false"));
        assertTrue(first.body().contains("\"content\":\"第一段\""));

        // 500ms 节流：紧接着的更新只累积、不再打接口
        int before = captured.size();
        a.updateStreamContent(incoming, streamId, "第一段+第二段");
        assertEquals(before, captured.size());

        a.finalizeStream(incoming, streamId, "定稿内容");
        long puts = captured.stream().filter(c -> c.path().equals("/v1.0/card/streaming")).count();
        assertEquals(2, puts); // 首次更新 + finalize
        Captured finalize = captured.stream()
                .filter(c -> c.body().contains("定稿内容")).reduce((x, y) -> y).orElseThrow();
        assertTrue(finalize.body().contains("\"isFinalize\":false"));

        a.endStream(incoming, streamId);
        long putsAfterEnd = captured.stream()
                .filter(c -> c.path().equals("/v1.0/card/streaming")).count();
        assertEquals(3, putsAfterEnd);
        Captured last = captured.get(captured.size() - 1);
        assertTrue(last.body().contains("\"isFinalize\":true"));
        assertTrue(last.body().contains("定稿内容"));
        assertNull(DingtalkAdapter.STREAMS.get(streamId));

        // 未知流 ID
        assertThrows(IllegalStateException.class,
                () -> a.updateStreamContent(incoming, "nope", "x"));
        assertThrows(IllegalStateException.class,
                () -> a.finalizeStream(incoming, "nope", "x"));
    }

    @Test
    @DisplayName("流式（无卡片）：更新只累积不发送；结束整段发（webhook 优先，否则 OpenAPI）")
    void streamsWithoutCard() throws Exception {
        stubTokenAnd(req -> StubResponse.json("{}"));
        DingtalkAdapter a = adapter("sec", "");

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_DINGTALK;
        incoming.userId = "u1";
        incoming.messageId = "m1";
        incoming.chatType = ImTypes.CHAT_TYPE_DIRECT;
        incoming.extra.put("session_webhook", apiBase + "/hook");

        String streamId = a.startStream(incoming);
        assertTrue(captured.isEmpty()); // 无卡片模板 → 不发任何接口

        a.updateStreamContent(incoming, streamId, "累积内容");
        assertTrue(captured.isEmpty());

        a.endStream(incoming, streamId);
        assertEquals(1, captured.size());
        assertEquals("/hook", captured.get(0).path());
        assertTrue(captured.get(0).body().contains("累积内容"));

        // 无 webhook → OpenAPI 群发
        captured.clear();
        IncomingMessage group = new IncomingMessage();
        group.platform = ImTypes.PLATFORM_DINGTALK;
        group.userId = "u2";
        group.messageId = "m2";
        group.chatType = ImTypes.CHAT_TYPE_GROUP;
        group.chatId = "cid-2";
        String streamId2 = a.startStream(group);
        a.updateStreamContent(group, streamId2, "群答内容");
        a.endStream(group, streamId2);

        Captured send = captured.stream()
                .filter(c -> c.path().equals("/v1.0/robot/groupMessages/send"))
                .findFirst().orElseThrow();
        assertTrue(send.body().contains("群答内容"));

        // 未知流 ID 结束 → 静默（照 Go）
        a.endStream(incoming, "nope");
    }

    // ── 工厂与工具 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：webhook 建、websocket 明确未落地、未知模式报错；下载白名单按后缀")
    void factoryAndHelpers() {
        ImChannelEntity channel = new ImChannelEntity();
        channel.setId("ch-1");
        channel.setMode("webhook");
        channel.setCredentials("{\"client_id\":\"ding_client\",\"client_secret\":\"sec\","
                + "\"card_template_id\":\"tpl\"}");
        var reg = new DingtalkAdapterFactory(null, apiBase).create(channel, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_DINGTALK, reg.adapter().platform());
        assertNull(reg.stop());

        ImChannelEntity ws = new ImChannelEntity();
        ws.setId("ch-2");
        ws.setMode("websocket");
        ws.setCredentials("{\"client_id\":\"c\",\"client_secret\":\"s\"}");
        assertThrows(UnsupportedOperationException.class,
                () -> new DingtalkAdapterFactory(null, apiBase).create(ws, (m, c) -> { }));

        ImChannelEntity bad = new ImChannelEntity();
        bad.setId("ch-3");
        bad.setMode("pigeon");
        bad.setCredentials("{}");
        assertThrows(IllegalArgumentException.class,
                () -> new DingtalkAdapterFactory(null, apiBase).create(bad, (m, c) -> { }));

        assertTrue(DingtalkAdapter.isAllowedDownloadHost("https://x.aliyuncs.com/a.jpg"));
        assertTrue(DingtalkAdapter.isAllowedDownloadHost("https://cdn.dingtalk.com/x"));
        assertTrue(!DingtalkAdapter.isAllowedDownloadHost("https://evil.com/x"));
        assertTrue(!DingtalkAdapter.isAllowedDownloadHost("not-a-url"));
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    /** 无 body、只带请求头的交换（验签用）。 */
    private static final class HeaderExchange implements CallbackExchange {
        private final Map<String, String> headers;

        HeaderExchange(Map<String, String> headers) {
            this.headers = headers;
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String query(String name) {
            return null;
        }

        @Override
        public String header(String name) {
            return headers.get(name);
        }

        @Override
        public Map<String, String> headers() {
            return headers;
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }

        @Override
        public void json(int status, Object payload) {
        }

        @Override
        public void plain(int status, String contentType, String text) {
        }

        @Override
        public boolean committed() {
            return false;
        }
    }

    /** 只带 body 的交换（解析用）。 */
    private static final class BodyExchange implements CallbackExchange {
        private final byte[] body;

        BodyExchange(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String query(String name) {
            return null;
        }

        @Override
        public String header(String name) {
            return null;
        }

        @Override
        public Map<String, String> headers() {
            return Map.of();
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public void json(int status, Object payload) {
        }

        @Override
        public void plain(int status, String contentType, String text) {
        }

        @Override
        public boolean committed() {
            return false;
        }
    }
}
