package com.ragagent.im.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
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
 * 飞书 / Lark 适配器（W5γ3.6）对照 Go {@code internal/im/feishu/adapter.go}：
 * 验签（明文与加密两形态）、URL 挑战、解析（text/file/image/post + @_user_ 剥离）、
 * 发送（reply 优先 + 可回落码改走 send-message + 路径参数安全）、CardKit 流式三件套
 * （建卡/更新元素/关流回填摘要）、卡片 markdown 图片换 image_key（失败降级为链接）、
 * 资源下载的文件名三级回落。
 *
 * <p>测试自持"加密方向"（AES-256-CBC，密钥 = SHA-256(encrypt_key)，IV 前置），与
 * {@code FeishuWecomCrypt.feishuDecrypt} 互逆。</p>
 */
class FeishuAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String APP_ID = "cli_app";
    private static final String ENCRYPT_KEY = "test-encrypt-key";
    private static final String VERIFY_TOKEN = "vt-1";

    private record Captured(String method, String path, String query, String body,
                            Map<String, List<String>> headers) {
    }

    private record StubResponse(int status, String contentType, byte[] body,
                                Map<String, String> headers) {
        static StubResponse json(String json) {
            return new StubResponse(200, "application/json",
                    json.getBytes(StandardCharsets.UTF_8), Map.of());
        }

        static StubResponse json(String json, Map<String, String> headers) {
            return new StubResponse(200, "application/json",
                    json.getBytes(StandardCharsets.UTF_8), headers);
        }
    }

    private HttpServer server;
    private String apiBase;
    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private Function<Captured, StubResponse> responder =
            c -> StubResponse.json("{\"code\":0,\"msg\":\"ok\"}");

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
        FeishuAdapter.STREAMS.clear();
        FeishuAdapter.IMAGE_KEY_CACHE.clear();
    }

    private FeishuAdapter adapter() {
        return new FeishuAdapter(FeishuRegion.FEISHU, APP_ID, "secret", VERIFY_TOKEN,
                ENCRYPT_KEY, apiBase, null);
    }

    /** 只返回 token 的桩（其余按各自 responder）。 */
    private void stubTokenAnd(Function<Captured, StubResponse> rest) {
        responder = req -> req.path().endsWith("/auth/v3/tenant_access_token/internal")
                ? StubResponse.json("{\"code\":0,\"tenant_access_token\":\"T1\",\"expire\":7200}")
                : rest.apply(req);
    }

    // ── 验签 / 挑战 / 解析 ──────────────────────────────────────────────────

    @Test
    @DisplayName("验签：header.token 比对（明文与加密两形态）；未配 token 跳过；不匹配抛")
    void verifiesToken() throws Exception {
        FeishuAdapter a = adapter();

        String plain = "{\"header\":{\"eventType\":\"im.message.receive_v1\",\"token\":\"vt-1\"}}";
        assertNull(a.verifyCallback(exchange(plain)));
        assertInstanceOf(AdapterInterfaces.VerifyException.class,
                a.verifyCallback(exchange("{\"header\":{\"token\":\"nope\"}}")));

        // 加密形态：{"encrypt": "<base64>"}
        String encrypted = "{\"encrypt\":\"" + encryptFeishu(
                "{\"header\":{\"token\":\"vt-1\"}}") + "\"}";
        assertNull(a.verifyCallback(exchange(encrypted)));

        // 未配 verification_token → 跳过验签
        FeishuAdapter noToken = new FeishuAdapter(FeishuRegion.LARK, APP_ID, "s", "", ENCRYPT_KEY,
                apiBase, null);
        assertNull(noToken.verifyCallback(exchange("{}")));
        assertEquals(ImTypes.PLATFORM_LARK, noToken.platform());
        assertNull(noToken.verifyCallback(exchange("{}")));
    }

    @Test
    @DisplayName("URL 挑战：challenge 回显 200（加密体先解密）；非挑战返回 false")
    void handlesChallenge() throws Exception {
        FeishuAdapter a = adapter();

        RecordingExchange plain = new RecordingExchange("POST",
                "{\"challenge\":\"c1\",\"type\":\"url_verification\"}");
        assertTrue(a.handleURLVerification(plain));
        assertEquals(200, plain.status);
        assertEquals("c1", plain.jsonBody.get("challenge"));

        RecordingExchange encrypted = new RecordingExchange("POST", "{\"encrypt\":\""
                + encryptFeishu("{\"challenge\":\"c2\"}") + "\"}");
        assertTrue(a.handleURLVerification(encrypted));
        assertEquals("c2", encrypted.jsonBody.get("challenge"));

        RecordingExchange other = new RecordingExchange("POST",
                "{\"header\":{\"eventType\":\"x\"}}");
        assertFalse(a.handleURLVerification(other));
    }

    @Test
    @DisplayName("解析：群聊剥 @_user_ 前缀 / root_id 优先 / file / image / post；未知事件类型 → null")
    void parsesEvents() throws Exception {
        FeishuAdapter a = adapter();

        IncomingMessage group = a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"sender\":{\"sender_id\":{\"open_id\":\"ou_1\"}},"
                        + "\"message\":{\"message_id\":\"m1\",\"root_id\":\"r0\","
                        + "\"message_type\":\"text\",\"chat_type\":\"group\",\"chat_id\":\"oc_1\","
                        + "\"content\":\"{\\\"text\\\":\\\"@_user_1 @_user_2 北京天气\\\"}\"}}}"));
        assertNotNull(group);
        assertEquals("ou_1", group.userId);
        assertEquals("oc_1", group.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, group.chatType);
        assertEquals("北京天气", group.content);
        assertEquals("r0", group.threadId);
        assertEquals("m1", group.messageId);

        IncomingMessage direct = a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"message\":{\"message_id\":\"m2\",\"message_type\":\"text\","
                        + "\"chat_type\":\"p2p\",\"content\":\"{\\\"text\\\":\\\" 你好 \\\"}\"}}}"));
        assertEquals(ImTypes.CHAT_TYPE_DIRECT, direct.chatType);
        assertEquals("", direct.chatId);
        assertEquals("你好", direct.content);
        assertEquals("m2", direct.threadId); // root_id 缺省回落 message_id

        IncomingMessage file = a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"message\":{\"message_id\":\"m3\",\"message_type\":\"file\","
                        + "\"chat_type\":\"p2p\",\"content\":"
                        + "\"{\\\"file_key\\\":\\\"fk1\\\",\\\"file_name\\\":\\\"a.pdf\\\"}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("fk1", file.fileKey);
        assertEquals("a.pdf", file.fileName);

        IncomingMessage image = a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"message\":{\"message_id\":\"m4\",\"message_type\":\"image\","
                        + "\"chat_type\":\"p2p\",\"content\":\"{\\\"image_key\\\":\\\"ik1\\\"}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertEquals("ik1", image.fileKey);
        assertEquals("ik1.png", image.fileName);

        IncomingMessage post = a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"message\":{\"message_id\":\"m5\",\"message_type\":\"post\","
                        + "\"chat_type\":\"p2p\",\"content\":\"{\\\"title\\\":\\\"标题\\\","
                        + "\\\"content\\\":[[{\\\"tag\\\":\\\"text\\\",\\\"text\\\":\\\"第一行\\\"}],"
                        + "[{\\\"tag\\\":\\\"a\\\",\\\"text\\\":\\\"链接\\\"},"
                        + "{\\\"tag\\\":\\\"img\\\",\\\"image_key\\\":\\\"x\\\"}]]}\"}}}"));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, post.messageType);
        assertEquals("标题\n第一行\n链接", post.content);

        // 未知事件类型 / 不支持的 message_type → null
        assertNull(a.parseCallback(exchange("{\"header\":{\"eventType\":\"other\"}}")));
        assertNull(a.parseCallback(exchange(
                "{\"header\":{\"eventType\":\"im.message.receive_v1\"},\"event\":{"
                        + "\"message\":{\"message_id\":\"m6\",\"message_type\":\"audio\","
                        + "\"chat_type\":\"p2p\",\"content\":\"{}\"}}}")));
    }

    // ── 发送 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("发送：reply API 优先；230071 回落 send-message（带 receive_id_type）；非法 message_id 拒绝")
    void sendReplyWithFallback() throws Exception {
        stubTokenAnd(req -> StubResponse.json("{\"code\":0,\"msg\":\"ok\"}"));
        FeishuAdapter a = adapter();

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_FEISHU;
        incoming.messageId = "om_1";
        incoming.userId = "ou_1";
        incoming.chatType = ImTypes.CHAT_TYPE_DIRECT;
        a.sendReply(incoming, new ReplyMessage("答案", false, true));

        assertEquals(2, captured.size()); // token + reply
        assertEquals("/open-apis/auth/v3/tenant_access_token/internal", captured.get(0).path());
        Captured reply = captured.get(1);
        assertEquals("POST", reply.method());
        assertEquals("/open-apis/im/v1/messages/om_1/reply", reply.path());
        assertNotNull(reply.headers().get("Authorization"));
        assertTrue(reply.body().contains("\"msg_type\":\"text\""));
        assertTrue(reply.body().contains("\"content\":\"{\\\"text\\\":\\\"答案\\\"}\""));

        // 可回落码 → 改走 send-message（群聊 receive_id_type=chat_id）
        captured.clear();
        stubTokenAnd(req -> req.path().endsWith("/reply")
                ? StubResponse.json("{\"code\":230071,\"msg\":\"no reply in thread\"}")
                : StubResponse.json("{\"code\":0,\"msg\":\"ok\"}"));
        IncomingMessage group = new IncomingMessage();
        group.platform = ImTypes.PLATFORM_FEISHU;
        group.messageId = "om_2";
        group.chatType = ImTypes.CHAT_TYPE_GROUP;
        group.chatId = "oc_9";
        a.sendReply(group, new ReplyMessage("群答", false, true));

        // token 已缓存 → 只有 reply(失败) + send 两发
        assertEquals(2, captured.size());
        assertEquals("/open-apis/im/v1/messages/om_2/reply", captured.get(0).path());
        Captured fallback = captured.get(1);
        assertEquals("/open-apis/im/v1/messages", fallback.path());
        assertEquals("receive_id_type=chat_id", fallback.query());
        assertTrue(fallback.body().contains("\"receive_id\":\"oc_9\""));

        // 不可回落的码 → 抛
        stubTokenAnd(req -> req.path().endsWith("/reply")
                ? StubResponse.json("{\"code\":99999,\"msg\":\"boom\"}")
                : StubResponse.json("{\"code\":0}"));
        assertThrows(IllegalStateException.class,
                () -> a.sendReply(incoming, new ReplyMessage("x", false, true)));

        // 含不安全字符的 message_id → 拒绝（不放 URL 路径）
        IncomingMessage unsafe = new IncomingMessage();
        unsafe.platform = ImTypes.PLATFORM_FEISHU;
        unsafe.messageId = "om_1/../evil";
        unsafe.userId = "ou_1";
        assertThrows(IllegalArgumentException.class,
                () -> a.sendReply(unsafe, new ReplyMessage("x", false, true)));
    }

    // ── 流式（CardKit） ─────────────────────────────────────────────────────

    @Test
    @DisplayName("流式：建卡 → interactive 消息 → PUT 元素（严格递增 seq）→ 关流回填摘要预览")
    void streamsViaCardkit() throws Exception {
        final String[] cardIdHolder = new String[1];
        stubTokenAnd(req -> {
            if (req.path().equals("/open-apis/cardkit/v1/cards")) {
                return StubResponse.json("{\"code\":0,\"data\":{\"card_id\":\"c_1\"}}");
            }
            if (req.path().contains("/cardkit/v1/cards/")
                    && req.path().endsWith("/content")) {
                return StubResponse.json("{\"code\":0,\"msg\":\"ok\"}");
            }
            if (req.path().contains("/cardkit/v1/cards/") && req.path().endsWith("/settings")) {
                return StubResponse.json("{\"code\":0,\"msg\":\"ok\"}");
            }
            return StubResponse.json("{\"code\":0,\"msg\":\"ok\"}");
        });
        FeishuAdapter a = adapter();

        IncomingMessage incoming = new IncomingMessage();
        incoming.platform = ImTypes.PLATFORM_FEISHU;
        incoming.messageId = "om_1";
        incoming.userId = "ou_1";
        incoming.chatType = ImTypes.CHAT_TYPE_DIRECT;

        String streamId = a.startStream(incoming);
        assertEquals("c_1", streamId);
        cardIdHolder[0] = streamId;

        // 建卡请求体：type=card_json + schema 2.0 + streaming_mode
        Captured create = captured.stream()
                .filter(c -> c.path().equals("/open-apis/cardkit/v1/cards")).findFirst().orElseThrow();
        assertTrue(create.body().contains("\"type\":\"card_json\""));
        assertTrue(create.body().contains("streaming_mode"));
        assertTrue(create.body().contains("正在思考..."));

        // 发出卡片：interactive + content.type=card
        Captured send = captured.stream()
                .filter(c -> c.path().equals("/open-apis/im/v1/messages/om_1/reply"))
                .findFirst().orElseThrow();
        assertTrue(send.body().contains("\"msg_type\":\"interactive\""));
        // content 是内嵌 JSON 字符串 → 内部引号被转义
        assertTrue(send.body().contains("\\\"card_id\\\":\\\"c_1\\\""));
        assertTrue(send.body().contains("\\\"type\\\":\\\"card\\\""));

        a.updateStreamContent(incoming, streamId, "第一段内容");
        Captured element = captured.stream()
                .filter(c -> c.path().endsWith("/elements/streaming_content/content"))
                .findFirst().orElseThrow();
        assertEquals("PUT", element.method());
        assertTrue(element.body().contains("\"sequence\":1"));
        assertTrue(element.body().contains("第一段内容"));

        // 未知流 ID → 抛
        assertThrows(IllegalStateException.class,
                () -> a.updateStreamContent(incoming, "nope", "x"));

        a.endStream(incoming, streamId);
        Captured settings = captured.stream()
                .filter(c -> c.path().endsWith("/settings")).findFirst().orElseThrow();
        assertEquals("PATCH", settings.method());
        assertTrue(settings.body().contains("streaming_mode"));
        assertTrue(settings.body().contains("\\\"streaming_mode\\\":false"));
        assertTrue(settings.body().contains("第一段内容")); // 摘要预览来自累积内容
        assertNull(FeishuAdapter.STREAMS.get(streamId));
    }

    @Test
    @DisplayName("卡片图片：外链换 image_key（按 app 缓存 + 去 query）；上传失败降级为纯链接")
    void resolvesMarkdownImages() throws Exception {
        final boolean[] failUpload = {false};
        stubTokenAnd(req -> {
            if (req.path().equals("/img.png")) {
                return new StubResponse(200, "image/png",
                        "PNGDATA".getBytes(StandardCharsets.UTF_8), Map.of());
            }
            if (req.path().equals("/open-apis/im/v1/images")) {
                return failUpload[0]
                        ? StubResponse.json("{\"code\":200570,\"msg\":\"bad image\"}")
                        : StubResponse.json("{\"code\":0,\"data\":{\"image_key\":\"img_k1\"}}");
            }
            return StubResponse.json("{\"code\":0}");
        });
        FeishuAdapter a = adapter();
        String token = a.getTenantAccessToken();

        String resolved = a.resolveMarkdownImages(token,
                "看图 ![图注](" + apiBase + "/img.png?sig=1) 完毕");
        assertEquals("看图 ![图注](img_k1) 完毕", resolved);

        // 缓存命中（同 URL 不同 query → 仍走缓存，不再下载）
        int before = captured.size();
        a.resolveMarkdownImages(token, "![图注](" + apiBase + "/img.png?sig=2)");
        assertEquals(before, captured.size());

        // 上传失败 → 降级为纯链接（label 取 alt）
        failUpload[0] = true;
        FeishuAdapter fresh = new FeishuAdapter(FeishuRegion.FEISHU, "cli_other", "secret",
                VERIFY_TOKEN, ENCRYPT_KEY, apiBase, null);
        String degraded = fresh.resolveMarkdownImages(fresh.getTenantAccessToken(),
                "![](" + apiBase + "/broken.png)");
        assertEquals("[" + FeishuRegion.FEISHU.imageFallbackLabel() + "]("
                + apiBase + "/broken.png)", degraded);
    }

    // ── 下载 / 工具 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("下载：resources API（type=file/image）；文件名取消息 → Content-Disposition → fileKey")
    void downloadsResource() throws Exception {
        stubTokenAnd(req -> req.path().startsWith("/open-apis/im/v1/messages/")
                ? new StubResponse(200, "application/pdf",
                        "PDF".getBytes(StandardCharsets.UTF_8),
                        Map.of("Content-Disposition", "attachment; filename=\"x.pdf\""))
                : StubResponse.json("{\"code\":0}"));
        FeishuAdapter a = adapter();

        IncomingMessage msg = new IncomingMessage();
        msg.platform = ImTypes.PLATFORM_FEISHU;
        msg.messageId = "om_1";
        msg.fileKey = "fk_1";
        msg.messageType = ImTypes.MESSAGE_TYPE_FILE;
        msg.fileName = ""; // 让 Content-Disposition 生效
        AdapterInterfaces.FileDownloader.DownloadedFile file = a.downloadFile(msg);

        assertEquals("PDF", new String(file.content(), StandardCharsets.UTF_8));
        assertEquals("x.pdf", file.fileName());
        Captured resources = captured.stream()
                .filter(c -> c.path().contains("/resources/")).findFirst().orElseThrow();
        assertEquals("type=file", resources.query());

        // 图片走 type=image；file_key 含不安全字符 → 拒绝
        msg.messageType = ImTypes.MESSAGE_TYPE_IMAGE;
        msg.fileName = "ik1.png";
        a.downloadFile(msg);
        assertEquals("type=image", captured.get(captured.size() - 1).query());

        IncomingMessage unsafe = new IncomingMessage();
        unsafe.platform = ImTypes.PLATFORM_FEISHU;
        unsafe.messageId = "om_1";
        unsafe.fileKey = "../etc/passwd";
        assertThrows(IllegalArgumentException.class, () -> a.downloadFile(unsafe));
    }

    @Test
    @DisplayName("工具：摘要预览去图片/链接语法并折叠空白截 120 字符；工厂 webhook 建、websocket 明确未落地")
    void helpersAndFactory() {
        assertEquals("图注 正文", FeishuAdapter.cardSummaryPreview("![图注](https://x/y.png)\n正文"));
        assertEquals("链接", FeishuAdapter.cardSummaryPreview("[链接](https://x/y)"));
        assertEquals(120, FeishuAdapter.cardSummaryPreview("字".repeat(300)).length());

        ImChannelEntity channel = new ImChannelEntity();
        channel.setId("ch-1");
        channel.setMode("webhook");
        channel.setCredentials("{\"app_id\":\"cli\",\"app_secret\":\"s\","
                + "\"verification_token\":\"vt\",\"encrypt_key\":\"ek\"}");
        var reg = new FeishuAdapterFactory(FeishuRegion.FEISHU, null).create(channel, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_FEISHU, reg.adapter().platform());
        assertNull(reg.stop());

        // websocket（Go 默认）：长连接已落地 → 给 stop 句柄（api_base_url 指向不可达端口，
        // 长连接线程失败即退避，stop 后立刻退出）
        ImChannelEntity ws = new ImChannelEntity();
        ws.setId("ch-2");
        ws.setMode("websocket");
        ws.setCredentials("{\"app_id\":\"cli\",\"app_secret\":\"s\","
                + "\"api_base_url\":\"http://127.0.0.1:1\"}");
        var wsReg = new FeishuAdapterFactory(FeishuRegion.LARK, null).create(ws, (m, c) -> { });
        assertNotNull(wsReg.adapter());
        assertNotNull(wsReg.stop());
        wsReg.stop();

        ImChannelEntity bad = new ImChannelEntity();
        bad.setId("ch-3");
        bad.setMode("pigeon");
        bad.setCredentials("{}");
        assertThrows(IllegalArgumentException.class,
                () -> new FeishuAdapterFactory(FeishuRegion.FEISHU, null)
                        .create(bad, (m, c) -> { }));

        // 基址校验：非 http(s) 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> new FeishuAdapter(FeishuRegion.FEISHU, "cli", "s", "", "", "ftp://x", null));
        assertTrue(FeishuAdapter.safePathParam("om_1-2"));
        assertFalse(FeishuAdapter.safePathParam("om/1"));
    }

    // ── 加密方向（与 feishuDecrypt 互逆） ───────────────────────────────────

    private static String encryptFeishu(String plainJson) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8));
        byte[] plain = plainJson.getBytes(StandardCharsets.UTF_8);
        int pad = 16 - (plain.length % 16);
        byte[] padded = java.util.Arrays.copyOf(plain, plain.length + pad);
        for (int i = plain.length; i < padded.length; i++) {
            padded[i] = (byte) pad;
        }
        byte[] iv = new byte[16]; // 固定 IV（测试确定性；解码侧取密文前 16 字节）
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(padded);
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    // ── 桩 ──────────────────────────────────────────────────────────────────

    private static CallbackExchange exchange(String body) {
        return new RecordingExchange("POST", body);
    }

    private static final class RecordingExchange implements CallbackExchange {
        private final String method;
        private final byte[] body;
        int status;
        Map<String, Object> jsonBody = Map.of();

        RecordingExchange(String method, String body) {
            this.method = method;
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return method;
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
        @SuppressWarnings("unchecked")
        public void json(int status, Object payload) {
            this.status = status;
            this.jsonBody = payload instanceof Map ? (Map<String, Object>) payload : Map.of();
        }

        @Override
        public void plain(int status, String contentType, String text) {
            this.status = status;
        }

        @Override
        public boolean committed() {
            return status != 0;
        }
    }
}
