package com.ragagent.im.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * Telegram 出站客户端（W5γ3 第一支）对照 Go {@code internal/im/telegram} 的行为验收。
 *
 * <p>用 JDK {@code HttpServer} 做 stub（与 OTLP 端到端同一套路）：断言请求路径/体、
 * 节流、Markdown 失败后的纯文本重试、文件下载。</p>
 */
class TelegramAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String path, Map<String, Object> body) {
    }

    private record StubResponse(int status, String contentType, byte[] body) {
        static StubResponse json(String json) {
            return new StubResponse(200, "application/json",
                    json.getBytes(StandardCharsets.UTF_8));
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
            Map<String, Object> parsed = raw.length == 0 ? Map.of()
                    : MAPPER.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            Captured req = new Captured(exchange.getRequestURI().getPath(), parsed);
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
        TelegramAdapter.STREAMS.clear();
    }

    private TelegramAdapter adapter(String secretToken) {
        return new TelegramAdapter("TOK", secretToken, apiBase);
    }

    // ── 解析与验签 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("解析：私聊取 from/文本；群聊取 chat_id 且剥 @bot 前缀；document/photo 映射类型")
    void parsesUpdates() throws Exception {
        TelegramAdapter a = adapter("");

        IncomingMessage direct = a.parseCallback(exchange(
                "{\"message\":{\"message_id\":7,\"from\":{\"id\":42,\"first_name\":\"张\","
                        + "\"last_name\":\"三\"},\"chat\":{\"id\":42,\"type\":\"private\"},"
                        + "\"text\":\"你好\"}}"));
        assertEquals(ImTypes.PLATFORM_TELEGRAM, direct.platform);
        assertEquals("42", direct.userId);
        assertEquals("张 三", direct.userName);
        assertEquals("", direct.chatId);
        assertEquals(ImTypes.CHAT_TYPE_DIRECT, direct.chatType);
        assertEquals("7", direct.messageId);
        assertEquals("你好", direct.content);

        IncomingMessage group = a.parseCallback(exchange(
                "{\"message\":{\"message_id\":8,\"message_thread_id\":5,\"from\":{\"id\":42,"
                        + "\"username\":\"u42\"},\"chat\":{\"id\":-100,\"type\":\"supergroup\"},"
                        + "\"text\":\"/ask@mybot 北京天气\"}}"));
        assertEquals("u42", group.userName); // first/last 皆空 → 回落 username
        assertEquals("-100", group.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("5", group.threadId);
        assertEquals("北京天气", group.content);

        IncomingMessage file = a.parseCallback(exchange(
                "{\"message\":{\"message_id\":9,\"chat\":{\"id\":1,\"type\":\"private\"},"
                        + "\"document\":{\"file_id\":\"F1\",\"fileName\":\"a.pdf\","
                        + "\"fileSize\":1234}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("F1", file.fileKey);
        assertEquals("a.pdf", file.fileName);
        assertEquals(1234L, file.fileSize);

        IncomingMessage photo = a.parseCallback(exchange(
                "{\"message\":{\"message_id\":10,\"chat\":{\"id\":1,\"type\":\"private\"},"
                        + "\"photo\":[{\"file_id\":\"small\",\"fileSize\":10},"
                        + "{\"file_id\":\"big\",\"fileSize\":99}]}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, photo.messageType);
        assertEquals("big", photo.fileKey); // 取最大那张（末位，照 Go）
        assertEquals("photo.jpg", photo.fileName);

        // 非消息事件（如 edited_message）→ null
        assertNull(a.parseCallback(exchange("{\"edited_message\":{\"message_id\":1}}")));
    }

    @Test
    @DisplayName("验签：无 secret 放行；有 secret 时错 token 抛 VerifyException")
    void verifiesSecretToken() {
        assertNull(adapter("").verifyCallback(exchangeWithHeader("{\"message\":null}", null)));

        TelegramAdapter withSecret = adapter("s3cret");
        assertNull(withSecret.verifyCallback(exchangeWithHeader("{}", "s3cret")));
        // 失败是"返回异常对象"（照 Go 的 error 约定），不是抛出
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                withSecret.verifyCallback(exchangeWithHeader("{}", "wrong")));
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                withSecret.verifyCallback(exchangeWithHeader("{}", null)));
        assertTrue(!withSecret.handleURLVerification(exchangeWithHeader("{}", null)));
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendReply：POST /botTOK/sendMessage，Markdown + chat_id 回落 user_id + thread_id")
    void sendReplyPostsMarkdown() throws Exception {
        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_TELEGRAM;
        incoming.userId = "42";
        incoming.threadId = "5";
        new TelegramAdapter("TOK", "", apiBase)
                .sendReply(incoming, new ReplyMessage("**答案**", false, true));

        assertEquals(1, captured.size());
        Captured req = captured.get(0);
        assertEquals("/botTOK/sendMessage", req.path());
        assertEquals("42", req.body().get("chat_id")); // 私聊回落 user_id
        assertEquals("**答案**", req.body().get("text"));
        assertEquals("Markdown", req.body().get("parse_mode"));
        assertEquals(5, req.body().get("message_thread_id"));
    }

    @Test
    @DisplayName("流式：startStream 发占位并回 {chatId}:{msgId}；窗口内重复更新被节流；endStream 清表")
    void streamLifecycle() throws Exception {
        responder = req -> "/botTOK/sendMessage".equals(req.path())
                ? StubResponse.json("{\"ok\":true,\"result\":{\"message_id\":77}}")
                : StubResponse.json("{\"ok\":true,\"result\":{}}");

        TelegramAdapter a = new TelegramAdapter("TOK", "", apiBase);
        IncomingMessage incoming = new IncomingMessage();
        incoming.chatId = "-100";
        incoming.userId = "42";

        String streamId = a.startStream(incoming);
        assertEquals("-100:77", streamId);
        assertEquals("正在思考...", captured.get(0).body().get("text"));
        assertNull(captured.get(0).body().get("parse_mode")); // 占位消息不带 Markdown

        a.updateStreamContent(incoming, streamId, "第一段");
        assertEquals(2, captured.size());
        assertEquals("/botTOK/editMessageText", captured.get(1).path());
        assertEquals("77", captured.get(1).body().get("message_id"));

        // 节流窗口内：不再发请求（照 Go 的 minEditInterval=500ms）
        a.updateStreamContent(incoming, streamId, "第二段");
        assertEquals(2, captured.size());

        // 未知流 ID → 抛
        assertThrows(IllegalStateException.class,
                () -> a.updateStreamContent(incoming, "nope:1", "x"));

        a.endStream(incoming, streamId);
        assertNull(TelegramAdapter.STREAMS.get(streamId));
    }

    @Test
    @DisplayName("finalize：Markdown 失败回落纯文本重试（两次编辑都发出）")
    void finalizeRetriesAsPlainText() throws Exception {
        responder = req -> {
            boolean isEdit = "/botTOK/editMessageText".equals(req.path());
            boolean markdown = isEdit && "Markdown".equals(req.body().get("parse_mode"));
            return markdown
                    ? StubResponse.json("{\"ok\":false,\"description\":\"can't parse entities\"}")
                    : StubResponse.json("{\"ok\":true,\"result\":{\"message_id\":77}}");
        };

        TelegramAdapter a = new TelegramAdapter("TOK", "", apiBase);
        IncomingMessage incoming = new IncomingMessage();
        incoming.chatId = "9";
        String streamId = a.startStream(incoming);
        a.finalizeStream(incoming, streamId, "最终答案");

        List<Captured> edits = new ArrayList<>();
        for (Captured c : captured) {
            if ("/botTOK/editMessageText".equals(c.path())) {
                edits.add(c);
            }
        }
        assertEquals(2, edits.size());
        assertEquals("Markdown", edits.get(0).body().get("parse_mode"));
        assertNull(edits.get(1).body().get("parse_mode")); // 回落纯文本
        assertEquals("最终答案", edits.get(1).body().get("text"));
    }

    @Test
    @DisplayName("下载：getFile 取 file_path，再 GET /file/botTOK/{path} 回字节与原文件名")
    void downloadsFile() throws Exception {
        responder = req -> {
            if (req.path().startsWith("/botTOK/getFile")) {
                return StubResponse.json("{\"ok\":true,\"result\":{\"file_path\":\"docs/x.pdf\"}}");
            }
            if (req.path().startsWith("/file/botTOK/docs/x.pdf")) {
                return new StubResponse(200, "application/pdf",
                        "PDF-BYTES".getBytes(StandardCharsets.UTF_8));
            }
            return StubResponse.json("{\"ok\":false,\"description\":\"unexpected\"}");
        };

        IncomingMessage msg = new IncomingMessage();
        msg.fileKey = "F1";
        msg.fileName = "x.pdf";
        AdapterInterfaces.FileDownloader.DownloadedFile file =
                new TelegramAdapter("TOK", "", apiBase).downloadFile(msg);

        assertNotNull(file);
        assertEquals("PDF-BYTES", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("x.pdf", file.fileName());
        assertEquals("getFile", captured.get(0).body().containsKey("file_id") ? "getFile" : "?");
        assertEquals("F1", captured.get(0).body().get("file_id"));

        // 缺 file_key → 抛
        IncomingMessage empty = new IncomingMessage();
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramAdapter("TOK", "", apiBase).downloadFile(empty));
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    private static CallbackExchange exchange(String body) {
        return exchangeWithHeader(body, null);
    }

    private static CallbackExchange exchangeWithHeader(String body, String secretHeader) {
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
                return "X-Telegram-Bot-Api-Secret-Token".equalsIgnoreCase(name)
                        ? secretHeader : null;
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
