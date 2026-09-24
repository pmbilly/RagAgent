package com.ragagent.im.mattermost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * Mattermost（W5γ3.11）对照 Go {@code internal/im/mattermost/{adapter,client,factory}.go}：
 * 入站体三支（JSON / 表单 / 兜底）、token 验签、自环与空消息丢弃、线程根三级回落、
 * file_ids 两形态、发送（channel 回落 + root_id）、流式（建帖/改帖/定稿）、下载名字三级回落、
 * 工厂（默认 webhook + 模式报错 + 凭据校验）。
 */
class MattermostAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String method, String path, String body,
                            Map<String, List<String>> headers) {
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, String> responder = c -> "{}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Captured req = new Captured(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(raw, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders());
            captured.add(req);
            byte[] resp = responder.apply(req).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
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
        MattermostAdapter.STREAMS.clear();
    }

    private MattermostAdapter adapter(String outgoingToken, String botUserId,
                                      boolean postToMain) {
        return new MattermostAdapter(new MattermostClient(apiBase, "tk-1", null),
                outgoingToken, botUserId, postToMain);
    }

    private static CallbackExchange exchange(String contentType, String body) {
        return new CallbackExchange() {
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
                return "Content-Type".equals(name) ? contentType : null;
            }

            @Override
            public Map<String, String> headers() {
                return contentType == null ? Map.of() : Map.of("Content-Type", contentType);
            }

            @Override
            public byte[] body() {
                return body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
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
        };
    }

    // ── 入站体与验签 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("入站体三支：application/json（含 +json）/ 空 CT 走表单 / 未知 CT 先试 JSON")
    void parsesOutgoingBodies() {
        MattermostAdapter.OutgoingPayload json = MattermostAdapter.parseOutgoingBody(
                "application/json; charset=utf-8",
                "{\"token\":\"t1\",\"user_id\":\"u1\",\"channel_id\":\"c1\",\"text\":\"hi\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("t1", json.token());
        assertEquals("c1", json.channelId());
        assertEquals("hi", json.text());

        MattermostAdapter.OutgoingPayload plusJson = MattermostAdapter.parseOutgoingBody(
                "application/vnd.api+json",
                "{\"token\":\"t2\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("t2", plusJson.token());

        MattermostAdapter.OutgoingPayload form = MattermostAdapter.parseOutgoingBody("",
                "token=t3&channel_id=c3&text=a+b&file_ids=f1%2Cf2"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("t3", form.token());
        assertEquals("a b", form.text());
        assertEquals(List.of("f1", "f2"), MattermostAdapter.parseFileIds(form.fileIdsRaw()));

        // 未知 CT：JSON 里有 token/channel_id 就认
        MattermostAdapter.OutgoingPayload fallback = MattermostAdapter.parseOutgoingBody(
                "text/plain", "{\"channel_id\":\"c9\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("c9", fallback.channelId());
        assertThrows(IllegalArgumentException.class, () -> MattermostAdapter.parseOutgoingBody(
                "text/plain", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("验签：outgoing token 精确相等；未配则跳过；解析失败折错")
    void verifiesToken() {
        MattermostAdapter a = adapter("tok-1", "", false);
        assertNull(a.verifyCallback(exchange("application/json",
                "{\"token\":\"tok-1\"}")));
        AdapterInterfaces.VerifyException bad = assertInstanceOf(
                AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange("application/json", "{\"token\":\"nope\"}")));
        assertEquals("invalid outgoing webhook token", bad.getMessage());
        assertNull(adapter("", "", false).verifyCallback(exchange("application/json", "{}")));

        Exception parseFail = a.verifyCallback(exchange("text/plain", "{}"));
        assertInstanceOf(AdapterInterfaces.VerifyException.class, parseFail);
        assertTrue(parseFail.getMessage().startsWith("parse outgoing payload: "));
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：自环/空消息丢弃；线程根三级回落；file_ids 两形态")
    void parsesCallback() {
        MattermostAdapter a = adapter("tok", "bot-1", false);

        assertNull(a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"bot-1\",\"text\":\"hi\"}")));
        assertNull(a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"u1\",\"text\":\"   \"}")));

        IncomingMessage withRoot = a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"u1\",\"user_name\":\"张三\","
                        + "\"channel_id\":\"c1\",\"post_id\":\"p1\",\"root_id\":\"r1\","
                        + "\"text\":\" 问题 \"}"));
        assertEquals(ImTypes.PLATFORM_MATTERMOST, withRoot.platform);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, withRoot.chatType);
        assertEquals("张三", withRoot.userName);
        assertEquals("问题", withRoot.content);
        assertEquals("p1", withRoot.messageId);
        assertEquals("r1", withRoot.threadId);
        assertEquals("r1", withRoot.extra.get("thread_root_id"));
        assertEquals("c1", withRoot.extra.get("channel_id"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, withRoot.messageType);

        // 无 root_id：查 GetPost 拿真根
        responder = c -> "{\"id\":\"p2\",\"root_id\":\"r9\"}";
        IncomingMessage resolved = a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"u1\",\"channel_id\":\"c1\","
                        + "\"post_id\":\"p2\",\"text\":\"x\"}"));
        assertEquals("r9", resolved.threadId);
        assertEquals("/api/v4/posts/p2", captured.get(captured.size() - 1).path());

        // GetPost 失败 → 用自身 post_id 当根
        responder = c -> "{}";
        captured.clear();
        IncomingMessage self = a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"u1\",\"channel_id\":\"c1\","
                        + "\"post_id\":\"p3\",\"text\":\"x\"}"));
        assertEquals("p3", self.threadId);

        // post_to_main=true → 线程根恒空（且不查 GetPost）
        captured.clear();
        IncomingMessage main = adapter("tok", "", true).parseCallback(exchange(
                "application/json", "{\"token\":\"tok\",\"user_id\":\"u1\","
                        + "\"channel_id\":\"c1\",\"post_id\":\"p4\",\"root_id\":\"r4\","
                        + "\"text\":\"x\"}"));
        assertEquals("", main.threadId);
        assertTrue(captured.isEmpty());

        // file_ids（JSON 数组，多枚 → extra 逗号连接）
        IncomingMessage files = a.parseCallback(exchange("application/json",
                "{\"token\":\"tok\",\"user_id\":\"u1\",\"channel_id\":\"c1\","
                        + "\"post_id\":\"p5\",\"file_ids\":[\"f1\",\"f2\"],\"text\":\"\"}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, files.messageType);
        assertEquals("f1", files.fileKey);
        assertEquals("f1,f2", files.extra.get("file_ids"));
    }

    // ── 发送与流式 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：channel 取 ChatID 回落 extra；缺失报错；root_id 带上")
    void sendsReply() throws Exception {
        responder = c -> "{\"id\":\"p_new\"}";
        MattermostAdapter a = adapter("tok", "", false);

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_MATTERMOST;
        incoming.chatId = "c1";
        incoming.extra.put("thread_root_id", "r1");
        a.sendReply(incoming, new ReplyMessage("答案", false, true));

        Captured post = captured.get(0);
        assertEquals("POST", post.method());
        assertEquals("/api/v4/posts", post.path());
        assertEquals("Bearer tk-1", post.headers().get("Authorization").get(0));
        assertTrue(post.body().contains("\"channel_id\":\"c1\""));
        assertTrue(post.body().contains("\"root_id\":\"r1\""));
        assertTrue(post.body().contains("\"message\":\"答案\""));

        // ChatID 空 → 回落 extra 的 channel_id
        captured.clear();
        IncomingMessage fallback = new IncomingMessage();
        fallback.platform = ImTypes.PLATFORM_MATTERMOST;
        fallback.chatId = "";
        fallback.extra.put("channel_id", "c2");
        a.sendReply(fallback, new ReplyMessage("x", false, true));
        assertTrue(captured.get(0).body().contains("\"channel_id\":\"c2\""));

        IncomingMessage missing = new IncomingMessage();
        missing.platform = ImTypes.PLATFORM_MATTERMOST;
        assertEquals("missing channel_id", assertThrows(IllegalArgumentException.class,
                () -> a.sendReply(missing, new ReplyMessage("x", false, true))).getMessage());
    }

    @Test
    @DisplayName("流式：建帖\"正在思考...\" → patch 更新 → 定稿用累积内容；未知流 ID 报错")
    void streamsViaPostPatch() throws Exception {
        responder = c -> c.path().equals("/api/v4/posts") ? "{\"id\":\"p1\"}" : "{}";
        MattermostAdapter a = adapter("tok", "", false);

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_MATTERMOST;
        incoming.chatId = "c1";
        incoming.extra.put("thread_root_id", "r1");

        String streamId = a.startStream(incoming);
        assertEquals("c1:p1", streamId);
        assertTrue(captured.get(0).body().contains("正在思考..."));

        a.updateStreamContent(incoming, streamId, "第一段");
        Captured patch = captured.get(1);
        assertEquals("PUT", patch.method());
        assertEquals("/api/v4/posts/p1/patch", patch.path());
        assertTrue(patch.body().contains("\"message\":\"第一段\""));

        a.endStream(incoming, streamId);
        assertEquals("/api/v4/posts/p1/patch", captured.get(2).path());
        assertTrue(captured.get(2).body().contains("第一段"));   // 累积内容
        assertNull(MattermostAdapter.STREAMS.get(streamId));

        assertThrows(IllegalStateException.class,
                () -> a.updateStreamContent(incoming, "nope", "x"));
        a.endStream(incoming, "nope");   // 未知流：静默（照 Go）
    }

    @Test
    @DisplayName("下载：名字三级回落（info.name → fileName → fileKey）；缺 file_key 报错")
    void downloadsFile() throws Exception {
        responder = c -> c.path().endsWith("/info")
                ? "{\"id\":\"f1\",\"name\":\"报告.pdf\",\"size\":12}"
                : "FILEBYTES";
        MattermostAdapter a = adapter("tok", "", false);

        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_MATTERMOST;
        msg.fileKey = "f1";
        msg.fileName = "ignored.pdf";
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(msg);
        assertEquals("报告.pdf", file.fileName());
        assertEquals("FILEBYTES", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("/api/v4/files/f1/info", captured.get(0).path());
        assertEquals("/api/v4/files/f1", captured.get(1).path());

        responder = c -> c.path().endsWith("/info") ? "{\"id\":\"f2\"}" : "X";
        IncomingMessage noName = new IncomingMessage();
        noName.platform = ImTypes.PLATFORM_MATTERMOST;
        noName.fileKey = "f2";
        noName.fileName = "fallback.bin";
        assertEquals("fallback.bin", a.downloadFile(noName).fileName());

        IncomingMessage missing = new IncomingMessage();
        missing.platform = ImTypes.PLATFORM_MATTERMOST;
        assertEquals("file_key is required", assertThrows(IllegalArgumentException.class,
                () -> a.downloadFile(missing)).getMessage());
    }

    // ── 工厂 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：默认 webhook；非 webhook 报错；outgoing_token 必填；site_url 校验")
    void factoryModes() {
        ImChannelEntity channel = new ImChannelEntity();
        channel.setId("ch-1");
        channel.setCredentials("{\"site_url\":\"" + apiBase + "\",\"bot_token\":\"tk\","
                + "\"outgoing_token\":\"ot\",\"post_to_main\":true}");
        // 未设 mode → 默认 webhook（照 Go）
        var reg = new MattermostAdapterFactory(null).create(channel, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_MATTERMOST, reg.adapter().platform());
        assertNotNull(reg.stop());
        reg.stop();

        ImChannelEntity ws = new ImChannelEntity();
        ws.setId("ch-2");
        ws.setMode("websocket");
        ws.setCredentials("{\"site_url\":\"" + apiBase + "\",\"bot_token\":\"tk\","
                + "\"outgoing_token\":\"ot\"}");
        assertEquals("unsupported mattermost mode: websocket (only webhook is supported)",
                assertThrows(IllegalArgumentException.class,
                        () -> new MattermostAdapterFactory(null).create(ws, (m, c) -> { }))
                        .getMessage());

        ImChannelEntity noToken = new ImChannelEntity();
        noToken.setId("ch-3");
        noToken.setCredentials("{\"site_url\":\"" + apiBase + "\",\"bot_token\":\"tk\"}");
        assertEquals("mattermost outgoing_token is required",
                assertThrows(IllegalArgumentException.class,
                        () -> new MattermostAdapterFactory(null).create(noToken, (m, c) -> { }))
                        .getMessage());

        // client 侧校验文案
        assertEquals("site_url is required", assertThrows(IllegalArgumentException.class,
                () -> new MattermostClient("", "tk", null)).getMessage());
        assertEquals("invalid site_url: must use http or https",
                assertThrows(IllegalArgumentException.class,
                        () -> new MattermostClient("ftp://x", "tk", null)).getMessage());
        assertEquals("bot_token is required", assertThrows(IllegalArgumentException.class,
                () -> new MattermostClient(apiBase, "  ", null)).getMessage());

        // 403 提示文案（照 Go）
        responder = c -> "{\"id\":\"\"}";
        MattermostClient client = new MattermostClient(apiBase, "tk", null);
        MattermostAdapter adapter = new MattermostAdapter(client, "ot", "", false);
        server.stop(0);
        HttpServer failing = null;
        try {
            failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            failing.createContext("/", ex -> {
                ex.sendResponseHeaders(403, 2);
                ex.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                ex.close();
            });
            failing.start();
            MattermostAdapter forbidden = new MattermostAdapter(
                    new MattermostClient("http://127.0.0.1:" + failing.getAddress().getPort(),
                            "tk", null), "ot", "", false);
            IncomingMessage msg = new IncomingMessage();
            msg.platform = ImTypes.PLATFORM_MATTERMOST;
            msg.chatId = "c1";
            String message = assertThrows(IllegalStateException.class,
                    () -> forbidden.sendReply(msg, new ReplyMessage("x", false, true)))
                    .getMessage();
            assertTrue(message.contains("403 forbidden"));
            assertTrue(message.contains("Channel menu → Members → Add"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (failing != null) {
                failing.stop(0);
            }
            assertNotNull(adapter);
        }
    }
}
