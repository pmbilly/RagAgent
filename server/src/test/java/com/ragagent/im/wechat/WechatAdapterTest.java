package com.ragagent.im.wechat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.domain.ImChannelEntity;
import com.ragagent.im.runtime.AdapterInterfaces;
import com.ragagent.im.runtime.CallbackExchange;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;
import com.ragagent.im.runtime.ReplyMessage;
import com.sun.net.httpserver.HttpServer;

/**
 * 微信 iLink 机器人（W5γ3.10）对照 Go {@code internal/im/wechat/{adapter,crypto,longpoll,factory}.go}：
 * AES-128-ECB 与三形态密钥解析、发送体与认证头、下载（裸/解密两态 + SSRF 拒绝）、
 * 长轮询（游标推进、四型解析、BOT 消息丢弃、token 过期、退避上限）、工厂凭据文案。
 */
class WechatAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] KEY16 = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private record Captured(String method, String path, String body,
                            Map<String, List<String>> headers) {
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, byte[]> responder = c -> "{}".getBytes(StandardCharsets.UTF_8);

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
            byte[] resp = responder.apply(req);
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
    }

    // ── 加密与密钥 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("密钥三形态（base64 裸 16B / base64 的 32 hex / 裸 32 hex）+ 异常形态；ECB 往返")
    void cryptoAndKeyFormats() throws Exception {
        byte[] raw = new byte[16];
        for (int i = 0; i < 16; i++) {
            raw[i] = (byte) i;
        }
        // ① base64(16 字节裸密钥)
        assertArrayEquals(raw, WechatCrypto.parseAesKey(
                Base64.getEncoder().encodeToString(raw)));
        // ② base64("30313233...") = base64(32 字符 hex 串)
        String hex32 = "00112233445566778899aabbccddeeff";
        assertArrayEquals(WechatCrypto.hexDecode(hex32),
                WechatCrypto.parseAesKey(Base64.getEncoder()
                        .encodeToString(hex32.getBytes(StandardCharsets.UTF_8))));
        // ③ 裸 32 字符 hex（ImageItem.aeskey）
        assertArrayEquals(WechatCrypto.hexDecode(hex32), WechatCrypto.parseAesKey(hex32));

        assertThrows(IllegalArgumentException.class, () -> WechatCrypto.parseAesKey(""));
        assertThrows(IllegalArgumentException.class,
                () -> WechatCrypto.parseAesKey("zzzz"));
        assertThrows(IllegalArgumentException.class,
                () -> WechatCrypto.hexDecode("abc"));

        // 往返：加密（PKCS#7）→ 解密还原
        byte[] plain = "媒体内容".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = WechatCrypto.encryptAes128Ecb(plain, KEY16);
        assertEquals(0, cipher.length % 16);
        assertArrayEquals(plain, WechatCrypto.decryptAes128Ecb(cipher, KEY16));

        // 免填充的密文（末字节非合法填充）→ 原样返回，不裁（照 Go）
        byte[] block = new byte[16];
        block[15] = 0x00;
        byte[] noPaddingPlain = new byte[16];
        javax.crypto.Cipher raw2 = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
        raw2.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(KEY16, "AES"));
        assertArrayEquals(noPaddingPlain,
                WechatCrypto.decryptAes128Ecb(raw2.doFinal(block), KEY16));

        assertTrue(WechatAdapter.buildCdnDownloadUrl("a b&c")
                .startsWith(WechatAdapter.CDN_BASE_URL + "/download?encrypted_query_param="));
        assertTrue(WechatAdapter.buildCdnDownloadUrl("a b").contains("a+b"));
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：/ilink/bot/sendmessage 体（BOT/FINISH/文本 item/context_token/base_info）+ 认证头")
    void sendsReplyAndTyping() throws Exception {
        WechatAdapter a = new WechatAdapter("tk-1", "bot-1", apiBase, null);
        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_WECHAT;
        incoming.userId = "u_1";
        incoming.messageId = "m1";
        incoming.extra.put("context_token", "ct-9");
        a.sendReply(incoming, new ReplyMessage("你好", false, true));

        assertEquals(1, captured.size());
        Captured send = captured.get(0);
        assertEquals("/ilink/bot/sendmessage", send.path());
        JsonNode body = MAPPER.readTree(send.body());
        assertEquals("", body.path("msg").path("from_user_id").asText());
        assertEquals("u_1", body.path("msg").path("to_user_id").asText());
        assertEquals(2, body.path("msg").path("message_type").asInt());
        assertEquals(2, body.path("msg").path("message_state").asInt());
        assertEquals(1, body.path("msg").path("item_list").get(0).path("type").asInt());
        assertEquals("你好", body.path("msg").path("item_list").get(0)
                .path("text_item").path("text").asText());
        assertEquals("ct-9", body.path("msg").path("context_token").asText());
        assertTrue(body.path("msg").path("client_id").asText().startsWith("weknora_"));
        assertEquals(WechatAdapter.CHANNEL_VERSION,
                body.path("base_info").path("channel_version").asText());

        assertEquals("Bearer tk-1", send.headers().get("Authorization").get(0));
        assertEquals("ilink_bot_token", send.headers().get("Authorizationtype").get(0));
        // X-WECHAT-UIN = base64(十进制随机 uint32)
        String uin = send.headers().get("X-wechat-uin").get(0);
        String decoded = new String(Base64.getDecoder().decode(uin), StandardCharsets.UTF_8);
        assertTrue(decoded.matches("\\d+"));

        a.sendTyping(incoming);
        assertEquals("/ilink/bot/sendtyping", captured.get(1).path());
        JsonNode typing = MAPPER.readTree(captured.get(1).body());
        assertEquals("u_1", typing.path("ilink_user_id").asText());
        assertEquals(1, typing.path("status").asInt());
    }

    @Test
    @DisplayName("回调三面明确不支持（照 Go 文案）；平台常量是 wechat")
    void webhookFacesUnsupported() {
        WechatAdapter a = new WechatAdapter("tk", "bot", apiBase, null);
        assertEquals(ImTypes.PLATFORM_WECHAT, a.platform());
        Exception e = a.verifyCallback(null);
        assertInstanceOf(AdapterInterfaces.VerifyException.class, e);
        assertEquals("WeChat adapter does not support webhook callbacks", e.getMessage());
        assertEquals("WeChat adapter does not support webhook callbacks",
                assertThrows(IllegalArgumentException.class, () -> a.parseCallback(null))
                        .getMessage());
        assertTrue(!a.handleURLVerification(stubExchange()));
    }

    // ── 下载 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("下载：无 aes_key 原样；有则 ECB 解密；缺 URL 报错；SSRF 守卫拒绝时折错")
    void downloadsWithOptionalDecryption() throws Exception {
        byte[] plain = "PDF内容".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = WechatCrypto.encryptAes128Ecb(plain, KEY16);
        responder = c -> c.path().equals("/media") ? cipher : "{}".getBytes(StandardCharsets.UTF_8);

        WechatAdapter a = new WechatAdapter("tk", "bot", apiBase, null);
        IncomingMessage raw = new IncomingMessage();
        raw.platform = ImTypes.PLATFORM_WECHAT;
        raw.fileKey = apiBase + "/media";
        raw.fileName = "a.pdf";
        assertArrayEquals(cipher, a.downloadFile(raw).content());
        assertEquals("a.pdf", a.downloadFile(raw).fileName());

        IncomingMessage encrypted = new IncomingMessage();
        encrypted.platform = ImTypes.PLATFORM_WECHAT;
        encrypted.fileKey = apiBase + "/media";
        encrypted.fileName = "";
        encrypted.extra.put("aes_key", Base64.getEncoder().encodeToString(KEY16));
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(encrypted);
        assertArrayEquals(plain, file.content());
        assertEquals(apiBase + "/media", file.fileName());   // 文件名缺省回落 URL（照 Go）

        IncomingMessage noUrl = new IncomingMessage();
        noUrl.platform = ImTypes.PLATFORM_WECHAT;
        assertThrows(IllegalArgumentException.class, () -> a.downloadFile(noUrl));

        WechatAdapter guarded = new WechatAdapter("tk", "bot", apiBase,
                new com.ragagent.common.security.SsrfGuard() {
                    @Override
                    public void validateURLForSSRF(String url) {
                        throw new IllegalArgumentException("blocked: " + url);
                    }
                });
        IncomingMessage blocked = new IncomingMessage();
        blocked.platform = ImTypes.PLATFORM_WECHAT;
        blocked.fileKey = apiBase + "/media";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> guarded.downloadFile(blocked)).getMessage()
                .contains("rejected by SSRF policy"));
    }

    // ── 长轮询 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("长轮询：游标推进 + 文本派发；token 过期（errcode -14）抛专用异常；退避上限 30s")
    void pollsUpdates() throws Exception {
        List<IncomingMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        WechatLongPollClient client = new WechatLongPollClient("tk", "bot", apiBase, null, "ch-1",
                (msg, cid) -> {
                    received.add(msg);
                    latch.countDown();
                });

        responder = c -> ("{\"ret\":0,\"errcode\":0,\"get_updates_buf\":\"buf-2\",\"msgs\":["
                + "{\"message_id\":11,\"from_user_id\":\"u1\",\"message_type\":1,"
                + "\"context_token\":\"ct\",\"item_list\":[{\"type\":1,"
                + "\"text_item\":{\"text\":\" 你好 \"}}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        client.poll();
        assertEquals("buf-2", client.cursor());
        assertTrue(latch.await(3, TimeUnit.SECONDS), "handler should receive the text message");
        assertEquals("你好", received.get(0).content);
        assertEquals("ct", received.get(0).extra.get("context_token"));

        responder = c -> "{\"ret\":0,\"errcode\":-14,\"errmsg\":\"token expired\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(WechatLongPollClient.TokenExpiredException.class, client::poll);

        responder = c -> "{\"ret\":1,\"errcode\":40001,\"errmsg\":\"bad\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, client::poll);

        assertEquals(1_000L, WechatLongPollClient.pollReconnectDelayMs(0));
        assertEquals(1_000L, WechatLongPollClient.pollReconnectDelayMs(1));
        assertEquals(2_000L, WechatLongPollClient.pollReconnectDelayMs(2));
        assertEquals(30_000L, WechatLongPollClient.pollReconnectDelayMs(6));
        assertEquals(30_000L, WechatLongPollClient.pollReconnectDelayMs(7));
        assertEquals(30_000L, WechatLongPollClient.pollReconnectDelayMs(64));

        WechatLongPollClient idle = new WechatLongPollClient("tk", "bot", apiBase, null, "ch",
                (m, c) -> { });
        idle.stop();   // 未启动也可安全停
    }

    @Test
    @DisplayName("解析：text/voice/image/file 四型 + BOT 消息与空 item 丢弃；图片优先 aeskey，文件读 len")
    void parsesMessageItems() {
        WechatLongPollClient c = new WechatLongPollClient("tk", "bot", apiBase, null, "ch",
                (m, cid) -> { });

        assertNull(c.parseMessage(json("{\"message_type\":2,\"item_list\":[{\"type\":1}]}")));
        assertNull(c.parseMessage(json("{\"message_type\":1,\"item_list\":[]}")));
        assertNull(c.parseMessage(json("{\"message_type\":1,\"message_id\":1,"
                + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"   \"}}]}")));
        assertNull(c.parseMessage(json("{\"message_type\":1,\"message_id\":1,"
                + "\"item_list\":[{\"type\":5}]}")));

        IncomingMessage voice = c.parseMessage(json("{\"message_type\":1,\"message_id\":2,"
                + "\"from_user_id\":\"u\",\"item_list\":[{\"type\":3,"
                + "\"voice_item\":{\"text\":\" 转写 \"}}]}"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, voice.messageType);
        assertEquals("转写", voice.content);

        IncomingMessage image = c.parseMessage(json("{\"message_type\":1,\"message_id\":33,"
                + "\"from_user_id\":\"u\",\"context_token\":\"ct\",\"item_list\":[{\"type\":2,"
                + "\"image_item\":{\"aeskey\":\"00112233445566778899aabbccddeeff\","
                + "\"media\":{\"encrypt_query_param\":\"p1\",\"aes_key\":\"b64key\"}}}]}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertTrue(image.fileKey.contains("encrypted_query_param=p1"));
        assertEquals("33.png", image.fileName);
        assertEquals("00112233445566778899aabbccddeeff", image.extra.get("aes_key"));

        IncomingMessage file = c.parseMessage(json("{\"message_type\":1,\"message_id\":44,"
                + "\"from_user_id\":\"u\",\"item_list\":[{\"type\":4,\"file_item\":"
                + "{\"file_name\":\"\",\"len\":\"2048\",\"media\":{\"encrypt_query_param\":\"p2\","
                + "\"aes_key\":\"b64\"}}}]}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("file_44", file.fileName);
        assertEquals(2048, file.fileSize);
        assertEquals("b64", file.extra.get("aes_key"));
    }

    // ── 工厂 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：缺凭据报 Go 文案；齐备则建长轮询并给 stop（不读 mode，照 Go）")
    void factoryWiresLongPoll() throws Exception {
        ImChannelEntity bad = new ImChannelEntity();
        bad.setId("ch-bad");
        bad.setCredentials("{\"bot_token\":\"tk\"}");
        assertEquals("wechat credentials require bot_token and ilink_bot_id",
                assertThrows(IllegalArgumentException.class,
                        () -> new WechatAdapterFactory(null, apiBase)
                                .create(bad, (m, c) -> { })).getMessage());

        // 快应答的 stub（避免测试里出现紧凑轮询）
        responder = c -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"ret\":0,\"get_updates_buf\":\"x\",\"msgs\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
        };
        ImChannelEntity channel = new ImChannelEntity();
        channel.setId("ch-1");
        channel.setMode("anything");   // 照 Go：不读 mode
        channel.setCredentials("{\"bot_token\":\"tk\",\"ilink_bot_id\":\"bot-9\"}");
        var reg = new WechatAdapterFactory(null, apiBase).create(channel, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_WECHAT, reg.adapter().platform());
        assertNotNull(reg.stop());
        reg.stop();
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CallbackExchange stubExchange() {
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
        };
    }
}
