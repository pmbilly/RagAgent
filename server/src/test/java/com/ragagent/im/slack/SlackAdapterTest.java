package com.ragagent.im.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImAdapterVerify;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * Slack 出站客户端（W5γ3.2）对照 Go {@code internal/im/slack}：验签（v0 签名 + 5 分钟窗）、
 * 入站委托已翻核心、{@code chat.postMessage}/{@code chat.update} 的 body、流生命周期、
 * 私有文件下载、工厂模式分派。
 */
class SlackAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String path, Map<String, Object> body, Map<String, List<String>> headers) {
    }

    private record StubResponse(int status, String contentType, byte[] body) {
        static StubResponse json(String json) {
            return new StubResponse(200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, StubResponse> responder =
            c -> StubResponse.json("{\"ok\":true,\"result\":{}}");

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
            Captured req = new Captured(exchange.getRequestURI().getPath(), parsed,
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
        SlackAdapter.STREAMS.clear();
    }

    // ── 验签与解析（入站委托） ──────────────────────────────────────────────

    @Test
    @DisplayName("验签：v0 签名比对 + 5 分钟时间戳窗；secret 空则免验")
    void verifiesSlackSignature() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c1\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = ImAdapterVerify.slackExpectedSignature("sec", ts,
                body.getBytes(StandardCharsets.UTF_8));

        assertNull(new SlackAdapter("TOK", "").verifyCallback(exchange(body, ts, "whatever")));
        assertNull(new SlackAdapter("TOK", "sec").verifyCallback(exchange(body, ts, signature)));

        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                new SlackAdapter("TOK", "sec").verifyCallback(exchange(body, ts, "v0=deadbeef")));
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                new SlackAdapter("TOK", "sec").verifyCallback(
                        exchange(body, String.valueOf(Instant.now().getEpochSecond() - 600), signature)));
    }

    @Test
    @DisplayName("解析：app_mention 交给 SlackAdapterCore（剥 <@U…> 提及、thread_ts 语义）")
    void parsesMentionViaCore() throws Exception {
        IncomingMessage msg = new SlackAdapter("TOK", "").parseCallback(exchange(
                "{\"type\":\"event_callback\",\"event\":{\"type\":\"app_mention\",\"user\":\"U1\","
                        + "\"channel\":\"C1\",\"text\":\"<@U0> 北京天气\",\"ts\":\"1700000000.000100\"}}",
                "0", "x"));
        assertNotNull(msg);
        assertEquals(ImTypes.PLATFORM_SLACK, msg.platform);
        assertEquals("U1", msg.userId);
        assertEquals("C1", msg.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, msg.chatType);
        assertEquals("北京天气", msg.content);
        assertEquals("1700000000.000100", msg.messageId);
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendReply：chat.postMessage，文本原样（不做展示格式化）+ thread_ts + Bearer")
    void sendReplyPostsRawText() throws Exception {
        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_SLACK;
        incoming.chatId = "C1";
        incoming.messageId = "1700000000.000100";
        new SlackAdapter("TOK", "", apiBase)
                .sendReply(incoming, new ReplyMessage("<think>内部</think>答案", false, true));

        Captured req = captured.get(0);
        assertEquals("/chat.postMessage", req.path());
        assertEquals("C1", req.body().get("channel"));
        assertEquals("<think>内部</think>答案", req.body().get("text")); // 原样，照 Go
        assertEquals("1700000000.000100", req.body().get("thread_ts"));
        assertEquals(List.of("Bearer TOK"), req.headers().get("Authorization"));
    }

    @Test
    @DisplayName("流：startStream 发占位并回 {channel}:{ts}；update=chat.update；endStream 用累积内容收尾")
    void streamLifecycle() throws Exception {
        responder = req -> "/chat.postMessage".equals(req.path())
                ? StubResponse.json("{\"ok\":true,\"ts\":\"1700000000.000200\"}")
                : StubResponse.json("{\"ok\":true,\"ts\":\"1700000000.000200\"}");

        SlackAdapter a = new SlackAdapter("TOK", "", apiBase);
        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_SLACK;
        incoming.userId = "U1"; // DM：channel 回落 user_id

        String streamId = a.startStream(incoming);
        assertEquals("U1:1700000000.000200", streamId);
        assertEquals("正在思考...", captured.get(0).body().get("text"));

        a.updateStreamContent(incoming, streamId, "第一段");
        assertEquals("/chat.update", captured.get(1).path());
        assertEquals("1700000000.000200", captured.get(1).body().get("ts"));
        assertEquals("第一段", captured.get(1).body().get("text"));

        assertThrows(IllegalStateException.class,
                () -> a.updateStreamContent(incoming, "nope:1", "x"));

        a.endStream(incoming, streamId);
        assertEquals(3, captured.size());
        assertEquals("第一段", captured.get(2).body().get("text")); // 用累积内容收尾
        assertNull(SlackAdapter.STREAMS.get(streamId));
    }

    @Test
    @DisplayName("下载：无 extra 时走 files.info 取 url_private_download，再带 Bearer 拉字节")
    void downloadsPrivateFile() throws Exception {
        responder = req -> {
            if ("/files.info".equals(req.path())) {
                return StubResponse.json("{\"ok\":true,\"file\":{\"url_private_download\":\""
                        + apiBase + "/files/private/x.pdf\"}}");
            }
            if ("/files/private/x.pdf".equals(req.path())) {
                return new StubResponse(200, "application/pdf", "PDF".getBytes(StandardCharsets.UTF_8));
            }
            return StubResponse.json("{\"ok\":false,\"error\":\"unexpected\"}");
        };

        IncomingMessage msg = new IncomingMessage();
        msg.fileKey = "F1";
        msg.fileName = "x.pdf";
        AdapterInterfaces.FileDownloader.DownloadedFile file =
                new SlackAdapter("TOK", "", apiBase).downloadFile(msg);

        assertEquals("PDF", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("x.pdf", file.fileName());
        assertEquals("F1", captured.get(0).body().get("file"));
        assertEquals(List.of("Bearer TOK"), captured.get(1).headers().get("Authorization"));

        // extra 里有 url_private_download → 跳过 files.info
        captured.clear();
        msg.extra.put("url_private_download", apiBase + "/files/private/x.pdf");
        new SlackAdapter("TOK", "", apiBase).downloadFile(msg);
        assertEquals(1, captured.size());
        assertEquals("/files/private/x.pdf", captured.get(0).path());
    }

    @Test
    @DisplayName("工厂：webhook 模式建 HTTP 适配器；未知模式照 Go 抛错")
    void factoryDispatchesModes() {
        SlackAdapterFactory factory = new SlackAdapterFactory();

        ImChannelEntity webhook = new ImChannelEntity();
        webhook.setId("ch-1");
        webhook.setMode("webhook");
        webhook.setCredentials("{\"bot_token\":\"TOK\",\"signing_secret\":\"sec\"}");
        com.ragagent.im.service.ImService.AdapterRegistration reg =
                factory.create(webhook, (msg, chId) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_SLACK, reg.adapter().platform());
        assertNull(reg.stop()); // webhook 型没有长连接拆除柄

        ImChannelEntity bad = new ImChannelEntity();
        bad.setId("ch-2");
        bad.setMode("carrier-pigeon");
        bad.setCredentials("{\"bot_token\":\"TOK\"}");
        assertThrows(IllegalArgumentException.class, () -> factory.create(bad, (m, c) -> { }));
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    private static CallbackExchange exchange(String body, String timestamp, String signature) {
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
                if ("X-Slack-Request-Timestamp".equalsIgnoreCase(name)) {
                    return timestamp;
                }
                if ("X-Slack-Signature".equalsIgnoreCase(name)) {
                    return signature;
                }
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
