package com.ragagent.im.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

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
 * 企业微信 webhook 适配器（W5γ3.4）对照 Go {@code internal/im/wecom/webhook_adapter.go}：
 * 验签与 AES 信封解密（含 corp_id 校验）、URL 验证回显、解析（群聊剥 @提及 / image 两型）、
 * 发送（群 appchat 优先、失败回落 message/send）、token 缓存、文件下载的文件名三级推断。
 *
 * <p>测试自持"加密"方向（Go 侧由企业微信发出）：AES-CBC + PKCS#7(32) + 信封，与适配器解密互逆。</p>
 */
class WecomWebhookAdapterTest {

    private static final String CORP_ID = "ww-test-corp";
    private static final String TOKEN = "test-token";
    /** 43 字符（base64 去填充）→ 解出 32 字节（照 Go 的 encoding_aes_key）。 */
    private static final String AES_KEY = Base64.getEncoder().withoutPadding()
            .encodeToString(new byte[32]);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String path, Map<String, Object> body) {
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
            c -> StubResponse.json("{\"errcode\":0}");

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
    }

    private WecomWebhookAdapter adapter() {
        // 本地 stub 是 http：走"跳过端点校验"的包内构造（生产强制 https + SSRF）
        return new WecomWebhookAdapter(CORP_ID, "agent-secret", TOKEN, AES_KEY, 1000002,
                apiBase, null, false);
    }

    // ── 验签 / URL 验证 / 解析 ───────────────────────────────────────────────

    @Test
    @DisplayName("验签：msg_signature 命中放行；不匹配返回 VerifyException（POST 取体里 Encrypt）")
    void verifiesSignature() throws Exception {
        String encrypt = encrypt("<xml><Content>hi</Content></xml>");
        String body = "<xml><Encrypt>" + encrypt + "</Encrypt></xml>";
        String signature = signature(TOKEN, "1700000000", "n1", encrypt);

        WecomWebhookAdapter a = adapter();
        assertNull(a.verifyCallback(exchange("POST", body,
                Map.of("timestamp", "1700000000", "nonce", "n1", "msg_signature", signature))));
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange("POST", body,
                        Map.of("timestamp", "1700000000", "nonce", "n1", "msg_signature", "dead"))));
    }

    @Test
    @DisplayName("URL 验证：GET 的 echostr 解密回显 200；解密失败回 400")
    void handlesUrlVerification() throws Exception {
        WecomWebhookAdapter a = adapter();
        String echo = encrypt("echo-plain");

        RecordingExchange ok = new RecordingExchange("GET", new byte[0],
                Map.of("echostr", echo));
        assertTrue(a.handleURLVerification(ok));
        assertEquals(200, ok.status);
        assertEquals("echo-plain", ok.plainText);

        RecordingExchange bad = new RecordingExchange("GET", new byte[0],
                Map.of("echostr", "not-base64!"));
        assertTrue(a.handleURLVerification(bad));
        assertEquals(400, bad.status);
        assertEquals("decrypt failed", bad.plainText);
    }

    @Test
    @DisplayName("解析：text（群聊剥 @提及）、image（PicUrl 优先）；未知类型 → null；corp_id 不符拒绝")
    void parsesCallback() throws Exception {
        WecomWebhookAdapter a = adapter();

        IncomingMessage direct = a.parseCallback(callback(
                "<xml><FromUserName>u1</FromUserName><MsgType>text</MsgType>"
                        + "<Content>你好</Content><MsgId>m1</MsgId></xml>"));
        assertEquals(ImTypes.PLATFORM_WECOM, direct.platform);
        assertEquals("u1", direct.userId);
        assertEquals("", direct.chatId);
        assertEquals(ImTypes.CHAT_TYPE_DIRECT, direct.chatType);
        assertEquals("你好", direct.content);
        assertEquals("m1", direct.messageId);

        IncomingMessage group = a.parseCallback(callback(
                "<xml><FromUserName>u2</FromUserName><MsgType>text</MsgType>"
                        + "<Content>@WeKnora  北京天气</Content><ChatId>WR1</ChatId>"
                        + "<MsgId>m2</MsgId></xml>"));
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("WR1", group.chatId);
        assertEquals("北京天气", group.content); // 双空格形态剥离

        IncomingMessage image = a.parseCallback(callback(
                "<xml><FromUserName>u3</FromUserName><MsgType>image</MsgType>"
                        + "<PicUrl>https://qyapi.weixin.qq.com/x.png</PicUrl>"
                        + "<MediaId>MID</MediaId><MsgId>m3</MsgId></xml>"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertEquals("https://qyapi.weixin.qq.com/x.png", image.fileKey);
        assertEquals("m3.png", image.fileName);

        assertNull(a.parseCallback(callback(
                "<xml><FromUserName>u4</FromUserName><MsgType>voice</MsgType></xml>")));

        // corp_id 不符（信封尾）→ 抛
        String wrongCorp = encrypt(
                "<xml><MsgType>text</MsgType><Content>x</Content></xml>", "other-corp");
        String wrongBody = "<xml><Encrypt>" + wrongCorp + "</Encrypt></xml>";
        assertThrows(RuntimeException.class,
                () -> a.parseCallback(exchange("POST", wrongBody, Map.of())));
    }

    // ── 出站 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：群先 appchat/send（成功即止）；失败回落 message/send；直发带 agentid")
    void sendReplyPaths() throws Exception {
        responder = req -> "/cgi-bin/gettoken".equals(req.path())
                ? StubResponse.json("{\"errcode\":0,\"access_token\":\"T1\",\"expires_in\":7200}")
                : StubResponse.json("{\"errcode\":0}");
        WecomWebhookAdapter a = adapter();

        IncomingMessage group = new IncomingMessage();
        group.platform = ImTypes.PLATFORM_WECOM;
        group.chatType = ImTypes.CHAT_TYPE_GROUP;
        group.chatId = "WR1";
        group.userId = "u1";
        a.sendReply(group, new ReplyMessage("群答", false, true));

        assertEquals(2, captured.size()); // token + appchat
        assertEquals("/cgi-bin/appchat/send", captured.get(1).path());
        assertEquals("WR1", captured.get(1).body().get("chatid"));
        assertEquals("markdown", captured.get(1).body().get("msgtype"));
        assertEquals(Map.of("content", "群答"), captured.get(1).body().get("markdown"));

        // appchat 报错 → 回落 message/send
        captured.clear();
        responder = req -> "/cgi-bin/gettoken".equals(req.path())
                ? StubResponse.json("{\"errcode\":0,\"access_token\":\"T1\",\"expires_in\":7200}")
                : ("/cgi-bin/appchat/send".equals(req.path())
                        ? StubResponse.json("{\"errcode\":93000,\"errmsg\":\"invalid chatid\"}")
                        : StubResponse.json("{\"errcode\":0}"));
        a.sendReply(group, new ReplyMessage("群答", false, true));
        // token 已有缓存 → 只有 appchat（失败）+ message/send（回落）两发
        assertEquals(2, captured.size());
        assertEquals("/cgi-bin/appchat/send", captured.get(0).path());
        assertEquals("/cgi-bin/message/send", captured.get(1).path());
        assertEquals("u1", captured.get(1).body().get("touser"));
        assertEquals(1000002, captured.get(1).body().get("agentid"));

        // 直发：token 命中缓存（gettoken 只请求过一次）
        captured.clear();
        IncomingMessage direct = new IncomingMessage();
        direct.platform = ImTypes.PLATFORM_WECOM;
        direct.chatType = ImTypes.CHAT_TYPE_DIRECT;
        direct.userId = "u9";
        a.sendReply(direct, new ReplyMessage("直答", false, true));
        assertEquals(1, captured.size());
        assertEquals("/cgi-bin/message/send", captured.get(0).path());
    }

    @Test
    @DisplayName("下载：media_id 走 media/get；文件名取 Content-Disposition，缺失则按 Content-Type 补扩展名")
    void downloadsMedia() throws Exception {
        responder = req -> {
            if ("/cgi-bin/gettoken".equals(req.path())) {
                return StubResponse.json("{\"errcode\":0,\"access_token\":\"T1\",\"expires_in\":7200}");
            }
            if ("/cgi-bin/media/get".equals(req.path())) {
                return new StubResponse(200, "application/pdf",
                        "PDF".getBytes(StandardCharsets.UTF_8));
            }
            return StubResponse.json("{\"errcode\":0}");
        };
        WecomWebhookAdapter a = adapter();

        IncomingMessage msg = new IncomingMessage();
        msg.fileKey = "MID";
        msg.fileName = "报表"; // 无扩展名 → 按 Content-Type 补 .pdf
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(msg);
        assertEquals("PDF", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("报表.pdf", file.fileName());
        assertEquals("MID", captured.get(1).path().contains("media/get") ? "MID" : "?");

        // http(s) 直链：平台白名单主机（无需 SSRF 校验）
        assertTrue(WecomWebhookAdapter.isAllowedImApiHost(
                "https://qyapi.weixin.qq.com/cgi-bin/media/get?x=1", ""));
        assertTrue(WecomWebhookAdapter.isAllowedImApiHost(
                "https://novac2c.cdn.weixin.qq.com/x", ""));
        assertTrue(!WecomWebhookAdapter.isAllowedImApiHost("https://evil.example.com/x", ""));
        assertTrue(WecomWebhookAdapter.isAllowedImApiHost("https://evil.example.com/x", "evil.example.com"));
    }

    @Test
    @DisplayName("工厂：webhook 建适配器；websocket（Go 默认）明确报未落地；未知模式照 Go 报错")
    void factoryModes() {
        ImChannelEntity webhook = new ImChannelEntity();
        webhook.setId("ch-1");
        webhook.setMode("webhook");
        webhook.setCredentials("{\"corp_id\":\"ww\",\"agent_secret\":\"s\",\"token\":\"t\","
                + "\"encoding_aes_key\":\"" + AES_KEY + "\",\"corp_agent_id\":1000002}");
        var reg = new WecomAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                .create(webhook, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_WECOM, reg.adapter().platform());
        assertNull(reg.stop());

        ImChannelEntity ws = new ImChannelEntity();
        ws.setId("ch-2");
        ws.setMode("websocket");
        ws.setCredentials("{}");
        assertThrows(UnsupportedOperationException.class,
                () -> new WecomAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                        .create(ws, (m, c) -> { }));

        ImChannelEntity unknown = new ImChannelEntity();
        unknown.setId("ch-3");
        unknown.setMode("pigeon");
        unknown.setCredentials("{}");
        assertThrows(IllegalArgumentException.class,
                () -> new WecomAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                        .create(unknown, (m, c) -> { }));
    }

    // ── 加密方向（与适配器的解密互逆） ──────────────────────────────────────

    private static String encrypt(String plainXml) throws Exception {
        return encrypt(plainXml, CORP_ID);
    }

    private static String encrypt(String plainXml, String corpId) throws Exception {
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        byte[] msg = plainXml.getBytes(StandardCharsets.UTF_8);
        byte[] receive = corpId.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[16 + 4 + msg.length + receive.length];
        // random(16) 用 0 占位（确定性）
        content[16] = (byte) ((msg.length >> 24) & 0xFF);
        content[17] = (byte) ((msg.length >> 16) & 0xFF);
        content[18] = (byte) ((msg.length >> 8) & 0xFF);
        content[19] = (byte) (msg.length & 0xFF);
        System.arraycopy(msg, 0, content, 20, msg.length);
        System.arraycopy(receive, 0, content, 20 + msg.length, receive.length);

        int pad = 32 - (content.length % 32);
        byte[] padded = Arrays.copyOf(content, content.length + pad);
        for (int i = content.length; i < padded.length; i++) {
            padded[i] = (byte) pad;
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(key, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static String signature(String token, String timestamp, String nonce, String encrypt)
            throws Exception {
        List<String> parts = new ArrayList<>(List.of(token, timestamp, nonce, encrypt));
        parts.sort(String::compareTo);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] out = digest.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : out) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private CallbackExchange callback(String plainXml) throws Exception {
        return exchange("POST", "<xml><Encrypt>" + encrypt(plainXml) + "</Encrypt></xml>", Map.of());
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    private static CallbackExchange exchange(String method, String body, Map<String, String> query) {
        return new RecordingExchange(method, body.getBytes(StandardCharsets.UTF_8), query);
    }

    private static final class RecordingExchange implements CallbackExchange {
        private final String method;
        private final byte[] body;
        private final Map<String, String> query;
        int status;
        String plainText;

        RecordingExchange(String method, byte[] body, Map<String, String> query) {
            this.method = method;
            this.body = body;
            this.query = query == null ? Map.of() : new HashMap<>(query);
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String query(String name) {
            return query.get(name);
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
            this.status = status;
        }

        @Override
        public void plain(int status, String contentType, String text) {
            this.status = status;
            this.plainText = text;
        }

        @Override
        public boolean committed() {
            return status != 0;
        }
    }
}
