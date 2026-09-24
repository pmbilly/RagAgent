package com.ragagent.im.yunzhijia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
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
 * 云之家（W5γ3.12）对照 Go {@code internal/im/yunzhijia/{adapter,sign,url,websocket,factory}.go}：
 * 签名（Go 录向量交叉核对）、回调解析（@提及三形态 / 内嵌图 / ID 回落链 / 线程根）、
 * 出站体（引用语义 + markdown 开关 + group_type=3 不发 notifyParams）、下载
 * （token 缓存 / 手动一次重定向且不转发 token / 文件名与扩展名）、端点校验与 WS 帧分类、
 * 工厂（模式与凭据）。
 */
class YunzhijiaAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "sec-1";

    private record Captured(String method, String path, String query, String body,
                            Map<String, List<String>> headers) {
    }

    /** 打开出站校验的桩（照 Go 覆写包级 var 的测试手法）。 */
    private static final class StubAdapter extends YunzhijiaAdapter {
        StubAdapter(String sendMsgUrl, String secret, String appId, String appSecret,
                    String allowedSuffix, String authUrl, String downloadBaseUrl) {
            super(sendMsgUrl, secret, appId, appSecret, 10, allowedSuffix, null, authUrl,
                    downloadBaseUrl, true);
        }

        @Override
        void validateSendUrl() {
            // 桩：跳过 https + 后缀校验
        }

        @Override
        void validateDownloadFileUrl(String rawUrl) {
            // 桩：跳过 https + yunzhijia.com 校验
        }
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, Resp> responder = c -> Resp.json("{}");

    private record Resp(int status, String contentType, byte[] body,
                        Map<String, String> headers) {
        static Resp json(String json) {
            return new Resp(200, "application/json", json.getBytes(StandardCharsets.UTF_8),
                    Map.of());
        }

        static Resp raw(String contentType, byte[] body, Map<String, String> headers) {
            return new Resp(200, contentType, body, headers);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Captured req = new Captured(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                    new String(raw, StandardCharsets.UTF_8), exchange.getRequestHeaders());
            captured.add(req);
            Resp resp = responder.apply(req);
            exchange.getResponseHeaders().add("Content-Type", resp.contentType());
            resp.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
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
    }

    private StubAdapter adapter() {
        return new StubAdapter(apiBase + "/send", SECRET, "app-1", "app-secret", "yunzhijia.com",
                apiBase + "/token", apiBase + "/download");
    }

    private static CallbackExchange exchange(String body, Map<String, String> headers) {
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
                return headers.get(name);
            }

            @Override
            public Map<String, String> headers() {
                return headers;
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

    // ── 签名 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("签名：与独立 Go 程序录的向量逐字节一致；字段任一变化即变")
    void signatureMatchesGoVector() throws Exception {
        YunzhijiaTypes.CallbackMessage msg = MAPPER.readValue(
                "{\"robotId\":\"robot-1\",\"robotName\":\"小云\",\"operatorOpenid\":\"op-openid-1\","
                        + "\"operatorName\":\"张三\",\"time\":1727000000000,\"msgId\":\"msg-1\","
                        + "\"content\":\"你好，请帮我查一下\"}",
                YunzhijiaTypes.CallbackMessage.class);
        // 录自独立 Go 程序（crypto/hmac + sha1，基串 = 七字段逗号连接）
        assertEquals("EGVf29MzZBaLN+EmUcSFqSOfWck=",
                YunzhijiaSign.computeSignature(SECRET, msg));

        msg.content = "改一下";
        assertFalse("EGVf29MzZBaLN+EmUcSFqSOfWck=".equals(
                YunzhijiaSign.computeSignature(SECRET, msg)));
    }

    @Test
    @DisplayName("验签：sign 头三形态 + 定长比较；缺头/坏体各自报错；未配 secret 跳过")
    void verifiesSignature() {
        StubAdapter a = adapter();
        String body = "{\"robotId\":\"robot-1\",\"robotName\":\"小云\","
                + "\"operatorOpenid\":\"op\",\"operatorName\":\"张三\",\"time\":1,"
                + "\"msgId\":\"m1\",\"content\":\"hi\"}";
        String sign = YunzhijiaSign.computeSignature(SECRET,
                parseJson(body));
        assertNull(a.verifyCallback(exchange(body, Map.of("sign", sign))));
        assertNull(a.verifyCallback(exchange(body, Map.of("Sign", sign))));
        assertNull(a.verifyCallback(exchange(body, Map.of("SIGN", sign))));

        AdapterInterfaces.VerifyException bad = assertInstanceOf(
                AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange(body, Map.of("sign", "AAAA"))));
        assertEquals("invalid signature", bad.getMessage());
        assertEquals("missing sign header", assertInstanceOf(
                AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange(body, Map.of()))).getMessage());
        assertTrue(assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange("not-json", Map.of("sign", "x")))).getMessage()
                .startsWith("parse callback for verification: "));

        StubAdapter noSecret = new StubAdapter(apiBase + "/send", "", "a", "s", "x.com",
                apiBase + "/token", apiBase + "/download");
        assertNull(noSecret.verifyCallback(exchange("{}", Map.of())));
    }

    private static YunzhijiaTypes.CallbackMessage parseJson(String raw) {
        try {
            return MAPPER.readValue(raw, YunzhijiaTypes.CallbackMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：非文本丢 / 必须@机器人（三形态 + notifyTo + at 描述）/ 内嵌图 / ID 回落链")
    void parsesCallbacks() throws Exception {
        // 非文本（type != 2）
        assertNull(YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":3,\"msgId\":\"m0\",\"content\":\"x\"}"));

        // 未 @ 机器人 → 丢
        assertNull(YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m1\","
                        + "\"content\":\"没@\"}"));

        // @小云: 命中（并剥掉）
        IncomingMessage hit = YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m2\","
                        + "\"time\":99,\"groupType\":1,\"groupId\":\"g1\",\"openId\":\"o1\","
                        + "\"operatorOpenid\":\"op1\",\"operatorName\":\"张三\","
                        + "\"content\":\"@小云: 你好\"}");
        assertEquals(ImTypes.PLATFORM_YUNZHIJIA, hit.platform);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, hit.chatType);
        assertEquals("你好", hit.content);
        assertEquals("op1", hit.userId);
        assertEquals("张三", hit.userName);
        assertEquals("g1", hit.chatId);
        assertEquals("m2", hit.messageId);
        assertEquals("m2", hit.threadId);
        assertEquals("r1", hit.extra.get("robot_id"));
        assertEquals("1", hit.extra.get("group_type"));
        assertEquals("99", hit.extra.get("time"));

        // 仅 notifyTo 命中（内容不含 @）+ replyRootMsgId 决定线程根
        IncomingMessage notify = YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m3\","
                        + "\"content\":\"问题\",\"msgParam\":\"{\\\"notifyTo\\\":[\\\"r1\\\"],"
                        + "\\\"replyRootMsgId\\\":\\\"root-9\\\"}\"}");
        assertEquals("问题", notify.content);
        assertEquals("root-9", notify.threadId);

        // desc 里 at 机器人
        IncomingMessage atDesc = YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m4\","
                        + "\"content\":\"q\",\"msgParam\":\"{\\\"desc\\\":[{\\\"type\\\":\\\"at\\\","
                        + "\\\"data\\\":\\\"r1\\\"}]}\"}");
        assertEquals("q", atDesc.content);

        // @后无分隔符（@小云你好）不算提及 → 丢
        assertNull(YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m5\","
                        + "\"content\":\"@小云你好\"}"));

        // 内嵌图：即使没文本也收（@提及靠 notifyTo），文件名 <msgId>.png + 宽高进 extra
        IncomingMessage image = YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m6\","
                        + "\"content\":\"\",\"msgParam\":\"{\\\"notifyTo\\\":[\\\"r1\\\"],"
                        + "\\\"desc\\\":[{\\\"type\\\":\\\"image\\\",\\\"data\\\":\\\"file-1\\\","
                        + "\\\"w\\\":100,\\\"h\\\":50}]}\"}");
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertEquals("file-1", image.fileKey);
        assertEquals("m6.png", image.fileName);
        assertEquals("100", image.extra.get("yunzhijia_image_width"));
        assertEquals("50", image.extra.get("yunzhijia_image_height"));

        // 坏 msgParam：只告警，走内容@ 判定（这里没有 → 丢）
        assertNull(YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m7\","
                        + "\"content\":\"hi\",\"msgParam\":\"{bad\"}"));

        // userId 回落链：operatorOid → openId → senderId
        IncomingMessage fallback = YunzhijiaAdapter.toIncomingMessage(
                "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\",\"msgId\":\"m8\","
                        + "\"operatorOid\":\"oid-1\",\"openId\":\"o2\",\"senderId\":\"s2\","
                        + "\"content\":\"@小云 好\"}");
        assertEquals("oid-1", fallback.userId);
    }

    @Test
    @DisplayName("cleanAtMention：空白/冒号/逗号才算分隔符；无分隔符不剥；名后无内容算命中")
    void cleanAtMentionIsStrict() {
        assertEquals("你好", YunzhijiaAdapter.cleanAtMention(" @小云: 你好", "小云").content());
        assertTrue(YunzhijiaAdapter.cleanAtMention(" @小云: 你好", "小云").mentioned());
        assertTrue(YunzhijiaAdapter.cleanAtMention("@小云，你好", "小云").mentioned());
        assertTrue(YunzhijiaAdapter.cleanAtMention("@小云：你好", "小云").mentioned());
        assertEquals("", YunzhijiaAdapter.cleanAtMention("@小云", "小云").content());
        assertTrue(YunzhijiaAdapter.cleanAtMention("@小云", "小云").mentioned());

        assertFalse(YunzhijiaAdapter.cleanAtMention("@小云你好", "小云").mentioned());
        assertEquals("@小云你好", YunzhijiaAdapter.cleanAtMention("@小云你好", "小云").content());
        assertFalse(YunzhijiaAdapter.cleanAtMention("@小云 你好", "").mentioned());

        assertEquals("yunzhijia-image.png",
                YunzhijiaAdapter.defaultImageFileName(""));
        assertEquals("m1.png", YunzhijiaAdapter.defaultImageFileName("m1"));
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：msgtype=2 + markdown + 引用（paramType=3/摘要/人名）+ notifyParams；group_type=3 不发")
    void sendsReply() throws Exception {
        responder = c -> Resp.json("{\"success\":true}");
        StubAdapter a = adapter();

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_YUNZHIJIA;
        incoming.userId = "op1";
        incoming.userName = "张三";
        incoming.content = "原问题";
        incoming.messageId = "m1";
        incoming.extra.put("group_type", "1");
        a.sendReply(incoming, new ReplyMessage("**答案**", false, true));

        Captured send = captured.get(0);
        assertEquals("POST", send.method());
        assertEquals("/send", send.path());
        com.fasterxml.jackson.databind.JsonNode body = MAPPER.readTree(send.body());
        assertEquals(2, body.path("msgtype").asInt());
        assertEquals("**答案**", body.path("content").asText());
        assertEquals("markdown", body.path("param").path("formatType").asText());
        assertEquals(3, body.path("paramType").asInt());
        assertEquals("m1", body.path("param").path("replyMsgId").asText());
        assertTrue(body.path("param").path("isReference").asBoolean());
        assertEquals("原问题", body.path("param").path("replySummary").asText());
        assertEquals("张三", body.path("param").path("replyPersonName").asText());
        assertEquals("openIds", body.path("notifyParams").get(0).path("type").asText());
        assertEquals("op1", body.path("notifyParams").get(0).path("values").get(0).asText());

        // group_type=3 → 不带 notifyParams
        captured.clear();
        incoming.extra.put("group_type", "3");
        a.sendReply(incoming, new ReplyMessage("x", false, true));
        assertTrue(!MAPPER.readTree(captured.get(0).body()).has("notifyParams"));

        // 无 messageId + 显式关掉 markdown → 整体不出 param（照 Go 的 opt-out）
        captured.clear();
        IncomingMessage noRef = new IncomingMessage();
        noRef.platform = ImTypes.PLATFORM_YUNZHIJIA;
        noRef.userId = "";
        ReplyMessage optOut = new ReplyMessage("x", false, true);
        optOut.extra.put("yunzhijia_format_type", "");
        a.sendReply(noRef, optOut);
        assertTrue(!MAPPER.readTree(captured.get(0).body()).has("param"));

        // 非 2xx 折错
        responder = c -> new Resp(500, "application/json", "boom".getBytes(), Map.of());
        assertThrows(IllegalStateException.class,
                () -> a.sendReply(incoming, new ReplyMessage("x", false, true)));
    }

    // ── 下载与 token ────────────────────────────────────────────────────────

    @Test
    @DisplayName("下载：token 缓存 + 手动一次重定向（第二跳不带 token）+ 文件名与扩展名补全")
    void downloadsWithTokenAndRedirect() throws Exception {
        responder = req -> switch (req.path()) {
            case "/token" -> Resp.json("{\"success\":true,\"errorCode\":0,\"data\":"
                    + "{\"accessToken\":\"AT-1\",\"expireIn\":3600}}");
            case "/download" -> new Resp(302, "text/plain", new byte[0],
                    Map.of("Location", apiBase + "/signed"));
            case "/signed" -> Resp.raw("image/png", "PNGDATA".getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Disposition", "attachment; filename=\"photo\""));
            default -> Resp.json("{}");
        };
        StubAdapter a = adapter();

        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_YUNZHIJIA;
        msg.fileKey = "file-1";
        msg.fileName = "";
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(msg);

        assertEquals("PNGDATA", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("photo.png", file.fileName());   // CD 名 + 无扩展名时按 CT 补
        assertEquals("/token", captured.get(0).path());
        assertEquals("/download", captured.get(1).path());
        assertEquals("fileId=file-1", captured.get(1).query());
        assertEquals("Bearer AT-1", captured.get(1).headers().get("Authorization").get(0));
        assertEquals("/signed", captured.get(2).path());
        assertNull(captured.get(2).headers().get("Authorization"));   // 重定向不转发 token

        // token 缓存：再下一次不再打 token 端点
        int before = captured.size();
        a.downloadFile(msg);
        long tokenCalls = captured.stream().filter(c -> c.path().equals("/token")).count();
        assertEquals(1, tokenCalls);
        assertTrue(captured.size() > before);

        // 两次重定向 → too many redirects
        responder = req -> switch (req.path()) {
            case "/download" -> new Resp(302, "text/plain", new byte[0],
                    Map.of("Location", apiBase + "/hop1"));
            case "/hop1" -> new Resp(302, "text/plain", new byte[0],
                    Map.of("Location", apiBase + "/hop2"));
            default -> Resp.json("{}");
        };
        assertTrue(assertThrows(RuntimeException.class, () -> a.downloadFile(msg)).getMessage()
                .contains("too many redirects"));

        // fileId 校验
        assertEquals("yunzhijia file id is required", assertThrows(IllegalArgumentException.class,
                () -> a.downloadFile(new IncomingMessage())).getMessage());
        IncomingMessage badId = new IncomingMessage();
        badId.platform = ImTypes.PLATFORM_YUNZHIJIA;
        badId.fileKey = "a/b";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> a.downloadFile(badId))
                .getMessage().contains("invalid yunzhijia file id"));
        assertThrows(IllegalArgumentException.class, () -> YunzhijiaAdapter.validateFileId("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> YunzhijiaAdapter.validateFileId("a b"));
        assertEquals("x", YunzhijiaAdapter.firstNonEmpty("", "  ", " x ", "y"));

        // token 端点失败（新实例，避免命中上一个 token 的缓存）
        responder = req -> req.path().equals("/token")
                ? new Resp(500, "application/json", "bad".getBytes(), Map.of()) : Resp.json("{}");
        StubAdapter fresh = adapter();
        assertThrows(IllegalStateException.class, () -> fresh.downloadFile(msg));
        StubAdapter noApp = new StubAdapter(apiBase + "/send", SECRET, "", "", "x.com",
                apiBase + "/token", apiBase + "/download");
        assertTrue(assertThrows(IllegalStateException.class, () -> noApp.downloadFile(msg))
                .getMessage().contains("app_id and app_secret are required"));
    }

    // ── 端点校验与 WS 帧 ────────────────────────────────────────────────────

    @Test
    @DisplayName("端点校验：scheme/userinfo/localhost/IP 字面量/后缀缺失或不匹配都拒；WS 推导要 yzjtoken")
    void validatesEndpoints() throws Exception {
        assertNotNull(YunzhijiaUrl.validateEndpointUrl("https://ok.yunzhijia.com/send", "https",
                "yunzhijia.com"));
        assertEquals("URL must use https", assertThrows(IllegalArgumentException.class,
                () -> YunzhijiaUrl.validateEndpointUrl("http://ok.yunzhijia.com", "https",
                        "yunzhijia.com")).getMessage());
        assertEquals("URL must not contain user information",
                assertThrows(IllegalArgumentException.class,
                        () -> YunzhijiaUrl.validateEndpointUrl("https://u@ok.yunzhijia.com", "https",
                                "yunzhijia.com")).getMessage());
        assertEquals("URL host must be a DNS name", assertThrows(IllegalArgumentException.class,
                () -> YunzhijiaUrl.validateEndpointUrl("https://localhost/x", "https", "x.com"))
                .getMessage());
        assertEquals("URL host must be a DNS name", assertThrows(IllegalArgumentException.class,
                () -> YunzhijiaUrl.validateEndpointUrl("https://127.0.0.1/x", "https", "x.com"))
                .getMessage());
        assertEquals("allowed host suffix is required",
                assertThrows(IllegalArgumentException.class,
                        () -> YunzhijiaUrl.validateEndpointUrl("https://ok.yunzhijia.com", "https",
                                "")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> YunzhijiaUrl.validateEndpointUrl("https://evil.com/x", "https",
                        "yunzhijia.com")).getMessage().contains("does not match allowed suffix"));

        assertEquals("wss://ok.yunzhijia.com/xuntong/websocket?yzjtoken=tk-1",
                YunzhijiaUrl.deriveWebSocketUrl(
                        "https://ok.yunzhijia.com/send?yzjtoken=tk-1&other=1", "yunzhijia.com"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> YunzhijiaUrl.deriveWebSocketUrl("https://ok.yunzhijia.com/send",
                        "yunzhijia.com")).getMessage().contains("missing yzjtoken"));
        assertTrue(YunzhijiaUrl.isPublicIp(InetAddress.getByName("8.8.8.8")));
        assertFalse(YunzhijiaUrl.isPublicIp(InetAddress.getByName("127.0.0.1")));
        assertFalse(YunzhijiaUrl.isPublicIp(InetAddress.getByName("10.1.2.3")));
        assertFalse(YunzhijiaUrl.isPublicIp(InetAddress.getByName("100.64.0.1")));
    }

    @Test
    @DisplayName("WS 帧：ping/pong 两形态；业务消息直认；robotMessage 信封；directpush/msgchg 回 ack")
    void parsesWebSocketFrames() throws Exception {
        assertEquals("ping",
                YunzhijiaLongConnClient.parseWebSocketFrame("ping").control());
        assertEquals("pong",
                YunzhijiaLongConnClient.parseWebSocketFrame("\"pong\"").control());

        String business = "{\"type\":2,\"robotId\":\"r1\",\"robotName\":\"小云\","
                + "\"operatorOpenid\":\"op\",\"operatorName\":\"张三\",\"time\":1,"
                + "\"msgId\":\"m1\",\"content\":\"hi\"}";
        assertNotNull(YunzhijiaLongConnClient.parseWebSocketFrame(business).message());

        String envelope = "{\"type\":\"robotMessage\",\"msg\":" + business + "}";
        assertNotNull(YunzhijiaLongConnClient.parseWebSocketFrame(envelope).message());

        // robotMessage 信封里 msg 不合法 → 报错
        assertThrows(RuntimeException.class, () -> YunzhijiaLongConnClient.parseWebSocketFrame(
                "{\"type\":\"robotMessage\",\"msg\":{\"x\":1}}"));

        // directpush + needAck → 回 {"cmd":"ack","seq":N}
        YunzhijiaLongConnClient.ParsedFrame ack = YunzhijiaLongConnClient.parseWebSocketFrame(
                "{\"cmd\":\"directpush\",\"needAck\":true,\"seq\":7}");
        assertEquals("directpush", ack.control());
        assertEquals("{\"cmd\":\"ack\",\"seq\":7}", new String(ack.ack(), StandardCharsets.UTF_8));

        // msgchg 不带 needAck → 无 ack
        YunzhijiaLongConnClient.ParsedFrame chg = YunzhijiaLongConnClient.parseWebSocketFrame(
                "{\"type\":\"msgchg\",\"seq\":9}");
        assertEquals("msgchg", chg.control());
        assertNull(chg.ack());

        // 纯控制帧
        assertEquals("customcmd", YunzhijiaLongConnClient.parseWebSocketFrame(
                "{\"cmd\":\"customCmd\"}").control());
        // 空帧 / 无类型
        assertThrows(RuntimeException.class,
                () -> YunzhijiaLongConnClient.parseWebSocketFrame("  "));
        assertThrows(RuntimeException.class,
                () -> YunzhijiaLongConnClient.parseWebSocketFrame("{}"));
        // 业务消息缺字段 → 不认
        assertNull(YunzhijiaLongConnClient.decodeBusinessMessage(
                MAPPER.readTree("{\"robotId\":\"r1\"}")));

        assertEquals(1_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(0));
        assertEquals(2_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(1));
        assertEquals(5_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(2));
        assertEquals(60_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(5));
        assertEquals(60_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(99));
        assertEquals(1_000L, YunzhijiaLongConnClient.webSocketReconnectDelayMs(-3));
    }

    // ── 工厂 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：send_msg_url 必填 + 端点校验；timeout_seconds 两形态；默认 webhook；未知模式报错")
    void factoryModes() {
        ImChannelEntity missing = new ImChannelEntity();
        missing.setId("ch-1");
        missing.setCredentials("{}");
        assertEquals("yunzhijia send_msg_url is required",
                assertThrows(IllegalArgumentException.class,
                        () -> new YunzhijiaAdapterFactory(null).create(missing, (m, c) -> { }))
                        .getMessage());

        // 端点校验失败（http + 无后缀）
        ImChannelEntity badUrl = new ImChannelEntity();
        badUrl.setId("ch-2");
        badUrl.setCredentials("{\"send_msg_url\":\"http://x.com/send\"}");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new YunzhijiaAdapterFactory(null).create(badUrl, (m, c) -> { }))
                .getMessage().startsWith("invalid send_msg_url: "));

        ImChannelEntity ok = new ImChannelEntity();
        ok.setId("ch-3");
        ok.setCredentials("{\"send_msg_url\":\"https://ok.yunzhijia.com/send\","
                + "\"allowed_webhook_host_suffix\":\"yunzhijia.com\",\"timeout_seconds\":\"7\"}");
        var reg = new YunzhijiaAdapterFactory(null).create(ok, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_YUNZHIJIA, reg.adapter().platform());
        assertNull(reg.stop());

        ImChannelEntity unknown = new ImChannelEntity();
        unknown.setId("ch-4");
        unknown.setMode("pigeon");
        unknown.setCredentials("{\"send_msg_url\":\"https://ok.yunzhijia.com/send\","
                + "\"allowed_webhook_host_suffix\":\"yunzhijia.com\"}");
        assertEquals("unsupported yunzhijia mode: pigeon",
                assertThrows(IllegalArgumentException.class,
                        () -> new YunzhijiaAdapterFactory(null).create(unknown, (m, c) -> { }))
                        .getMessage());

        // positiveIntCredential：数字 / 数字串 / 非法 / 负数 / 缺省
        assertEquals(7, YunzhijiaAdapterFactory.positiveIntCredential(
                Map.of("timeout_seconds", 7), "timeout_seconds", 10));
        assertEquals(7, YunzhijiaAdapterFactory.positiveIntCredential(
                Map.of("timeout_seconds", "7"), "timeout_seconds", 10));
        assertEquals(10, YunzhijiaAdapterFactory.positiveIntCredential(
                Map.of("timeout_seconds", "abc"), "timeout_seconds", 10));
        assertEquals(10, YunzhijiaAdapterFactory.positiveIntCredential(
                Map.of("timeout_seconds", -3), "timeout_seconds", 10));
        assertEquals(10, YunzhijiaAdapterFactory.positiveIntCredential(
                Map.of(), "timeout_seconds", 10));
    }
}
