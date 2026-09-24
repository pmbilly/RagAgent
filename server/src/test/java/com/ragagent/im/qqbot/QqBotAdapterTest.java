package com.ragagent.im.qqbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * QQ 机器人出站客户端（W5γ3.3）对照 Go {@code internal/im/qqbot}：
 * 网关载荷解析（C2C/群/非 dispatch）、发送路径与鉴权头、token 缓存与失效余量、
 * 工厂只支持 websocket、以及客户端参数的照抄文案。
 */
class QqBotAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String path, String method, Map<String, Object> body,
                            Map<String, List<String>> headers) {
    }

    private record StubResponse(byte[] body, String contentType) {
        static StubResponse json(String json) {
            return new StubResponse(json.getBytes(StandardCharsets.UTF_8), "application/json");
        }
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, StubResponse> responder =
            c -> StubResponse.json("{\"access_token\":\"T1\",\"expires_in\":7200}");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Map<String, Object> parsed = raw.length == 0
                    ? Map.of()
                    : MAPPER.readValue(raw,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
            Captured req = new Captured(exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod(), parsed, exchange.getRequestHeaders());
            captured.add(req);
            StubResponse resp = responder.apply(req);
            exchange.getResponseHeaders().add("Content-Type", resp.contentType());
            exchange.sendResponseHeaders(200, resp.body().length);
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

    /** 带 stub 注入的客户端（token 与网关发现都指本地）。 */
    private QqBotClient client() {
        QqBotClient client = new QqBotClient("APP", "SECRET", apiBase, "",
                apiBase + "/getAppAccessToken", null);
        client.gatewayDiscoveryUrl = apiBase + "/gateway";
        return client;
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：dispatch 的 C2C/群两形态；心跳等非 dispatch 与未知事件 → null")
    void parsesGatewayPayload() throws Exception {
        QqBotAdapter a = new QqBotAdapter(client());

        IncomingMessage c2c = a.parseCallback(exchange(
                "{\"op\":0,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"m1\",\"content\":\" 你好 \","
                        + "\"author\":{\"user_openid\":\"U1\",\"username\":\"阿一\"}}}"));
        assertNotNull(c2c);
        assertEquals(ImTypes.PLATFORM_QQBOT, c2c.platform);
        assertEquals("U1", c2c.userId);
        assertEquals("阿一", c2c.userName);
        assertEquals("", c2c.chatId);
        assertEquals(ImTypes.CHAT_TYPE_DIRECT, c2c.chatType);
        assertEquals("你好", c2c.content); // trim
        assertEquals("m1", c2c.messageId);
        assertEquals("m1", c2c.extra.get("message_id"));
        assertEquals("c2c", c2c.extra.get("chat_kind"));

        IncomingMessage group = a.parseCallback(exchange(
                "{\"op\":0,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"d\":{\"id\":\"m2\",\"content\":\"群问\","
                        + "\"group_openid\":\"G1\",\"author\":{\"member_openid\":\"M1\","
                        + "\"user_openid\":\"U2\"}}}"));
        assertEquals("M1", group.userId); // member_openid 优先
        assertEquals("G1", group.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("group", group.extra.get("chat_kind"));

        // 非 dispatch（心跳/hello）与未知事件 → null
        assertNull(a.parseCallback(exchange("{\"op\":1,\"d\":null}")));
        assertNull(a.parseCallback(exchange("{\"op\":0,\"t\":\"OTHER_EVENT\",\"d\":{}}")));
        assertNull(a.verifyCallback(exchange("{}"))); // 不做验签
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendReply：先取 token（QQBot 头），C2C/群两条路径；展示格式化为空则不发")
    void sendReplyPaths() throws Exception {
        responder = req -> "/getAppAccessToken".equals(req.path())
                ? StubResponse.json("{\"access_token\":\"TOK\",\"expires_in\":7200}")
                : StubResponse.json("{}");
        QqBotClient client = client();
        QqBotAdapter a = new QqBotAdapter(client);

        IncomingMessage direct = new IncomingMessage();
        direct.platform = ImTypes.PLATFORM_QQBOT;
        direct.userId = "U1";
        direct.chatType = ImTypes.CHAT_TYPE_DIRECT;
        direct.extra.put("message_id", "m1");
        a.sendReply(direct, new ReplyMessage("**答**", false, true));

        assertEquals(2, captured.size());
        assertEquals("/getAppAccessToken", captured.get(0).path());
        assertEquals("POST", captured.get(0).method());
        Captured send = captured.get(1);
        assertEquals("/v2/users/U1/messages", send.path());
        assertEquals(List.of("QQBot TOK"), send.headers().get("Authorization"));
        assertEquals(2, send.body().get("msg_type")); // msg_type=2（markdown）
        assertEquals(Map.of("content", "**答**"), send.body().get("markdown"));
        assertEquals("m1", send.body().get("msg_id"));
        assertEquals(1, send.body().get("msg_seq"));

        // 群：
        IncomingMessage group = new IncomingMessage();
        group.platform = ImTypes.PLATFORM_QQBOT;
        group.chatId = "G1";
        group.chatType = ImTypes.CHAT_TYPE_GROUP;
        a.sendReply(group, new ReplyMessage("群答", false, true));
        assertEquals("/v2/groups/G1/messages", captured.get(2).path());
        // token 命中缓存：不再请求 token 端点（captured 里 getAppAccessToken 只有一次）
        assertEquals(1, captured.stream().filter(c -> c.path().equals("/getAppAccessToken")).count());

        // 展示格式化为空（纯思考块）→ 不发请求
        int before = captured.size();
        a.sendReply(direct, new ReplyMessage("<think>内部</think>", false, true));
        assertEquals(before, captured.size());
    }

    // ── 工厂与参数校验 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：只支持 websocket（其它模式照 Go 报错）；客户端校验文案逐字")
    void factoryAndValidation() {
        ImChannelEntity channel = new ImChannelEntity();
        channel.setId("ch-1");
        channel.setMode("webhook");
        channel.setCredentials("{\"app_id\":\"APP\",\"client_secret\":\"S\"}");
        assertThrows(IllegalArgumentException.class,
                () -> new QqBotAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                        .create(channel, (m, c) -> { }));

        assertEquals("qqbot app_id is required", assertThrows(IllegalArgumentException.class,
                () -> new QqBotClient("", "S", "", "", null)).getMessage());
        assertEquals("qqbot client_secret is required", assertThrows(IllegalArgumentException.class,
                () -> new QqBotClient("APP", "", "", "", null)).getMessage());
        assertEquals("gateway_url must use wss", assertThrows(IllegalArgumentException.class,
                () -> new QqBotClient("APP", "S", "", "https://example.com/gateway", null))
                .getMessage());
    }

    @Test
    @DisplayName("expires_in 两形态：数字与字符串都认，异常回落 7200")
    void parsesExpiresIn() throws Exception {
        assertEquals(3600, QqBotClient.parseExpiresIn(MAPPER.readTree("3600")));
        assertEquals(1800, QqBotClient.parseExpiresIn(MAPPER.readTree("\"1800\"")));
        assertEquals(7200, QqBotClient.parseExpiresIn(MAPPER.readTree("\"abc\"")));
        assertEquals(7200, QqBotClient.parseExpiresIn(MAPPER.readTree("{}")));
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    private static CallbackExchange exchange(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
                return null;
            }

            @Override
            public Map<String, String> headers() {
                return Map.of();
            }

            @Override
            public byte[] body() {
                return bytes;
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
}
