package com.ragagent.im.wecom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.im.runtime.ImTypes;
import com.ragagent.im.runtime.IncomingMessage;

/**
 * 企业微信长连接（W5γ3.5）对照 Go {@code wecom/longconn.go} + {@code ws_adapter.go} 的
 * 确定性面：逐消息文件解密（含容错填充）、@提及剥离（带机器人名学习）、回调体解析
 * （五种类型 + 引用上下文 + 事件）、流帧构造、退避曲线、工厂模式分派。
 *
 * <p>WS 连接本身（拨号/订阅/重连）不在此测——它要真端点；帧的构造与解析已覆盖协议面。</p>
 */
class WecomLongConnTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AES_KEY_43 = Base64.getEncoder().withoutPadding()
            .encodeToString(new byte[32]);

    // ── 逐消息文件解密 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("decryptAesCbc：AES-256-CBC + PKCS#7；填充畸形时原样返回（照 Go 的容错）")
    void decryptsPerMessageFile() throws Exception {
        byte[] plain = "文件内容".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encryptCbc(plain, AES_KEY_43);
        assertArrayEquals(plain, WecomSupport.decryptAesCbc(encrypted, AES_KEY_43));

        // 无有效填充（末字节 0）→ 原样返回
        byte[] key = Base64.getDecoder().decode(AES_KEY_43 + "=");
        byte[] noPadding = Arrays.copyOf(plain, 16);
        byte[] raw = encryptNoPadding(noPadding, key);
        assertArrayEquals(noPadding, WecomSupport.decryptAesCbc(raw, AES_KEY_43));

        assertThrows(IllegalArgumentException.class,
                () -> WecomSupport.decryptAesCbc(new byte[15], AES_KEY_43));
        assertThrows(IllegalArgumentException.class,
                () -> WecomSupport.decryptAesCbc(new byte[16], "short"));
    }

    // ── @提及剥离（有状态） ─────────────────────────────────────────────────

    @Test
    @DisplayName("stripAtMention：双空格形态学机器人名；之后按名前缀剥离；否则走无状态兜底")
    void stripsMentionsAndLearnsBotName() {
        WecomLongConnClient client = client("");

        // 双空格：学名 + 剥离
        assertEquals("/stop", client.stripAtMention("@WeKnora Bot  /stop"));
        assertEquals("WeKnora Bot", client.botDisplayName);

        // 学到名后：单空格前缀剥离
        assertEquals("北京天气", client.stripAtMention("@WeKnora Bot 北京天气"));

        // 非 @ 开头原样；CJK 正文走启发式
        assertEquals("你好", client.stripAtMention("你好"));
        WecomLongConnClient fresh = client("");
        assertEquals("北京天气", fresh.stripAtMention("@Bot 北京天气"));
    }

    // ── 回调体解析 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("回调解析：text/voice/image/file/mixed 五型 + req_id 保留 + 群聊剥提及 + quote")
    void parsesCallbackTypes() throws Exception {
        WecomLongConnClient client = client("");

        IncomingMessage text = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","headers":{"req_id":"r1"},"body":{
                  "msgid":"m1","msgtype":"text","chattype":"group","chatid":"g1",
                  "from":{"userid":"u1"},"text":{"content":"@Bot  你好"}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, text.messageType);
        assertEquals("u1", text.userId);
        assertEquals("g1", text.chatId);
        assertEquals(ImTypes.CHAT_TYPE_GROUP, text.chatType);
        assertEquals("你好", text.content);
        assertEquals("r1", text.extra.get("req_id"));

        IncomingMessage voice = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m2","msgtype":"voice",
                  "chattype":"single","from":{"userid":"u2"},"voice":{"content":"语音转写"}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, voice.messageType); // 语音按文本查询
        assertEquals("语音转写", voice.content);

        IncomingMessage image = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m3","msgtype":"image",
                  "chattype":"single","from":{"userid":"u3"},
                  "image":{"url":"https://x/y.png","aeskey":"KEY"}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, image.messageType);
        assertEquals("https://x/y.png", image.fileKey);
        assertEquals("m3.png", image.fileName);
        assertEquals("KEY", image.extra.get("aes_key"));

        IncomingMessage file = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m4","msgtype":"file",
                  "chattype":"single","from":{"userid":"u4"},
                  "file":{"url":"https://x/doc.pdf","aeskey":"K2"}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_FILE, file.messageType);
        assertEquals("m4", file.fileName); // WeCom 不给文件名 → 用 msgid（照 Go）

        IncomingMessage mixedText = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m5","msgtype":"mixed",
                  "chattype":"single","from":{"userid":"u5"},
                  "mixed":{"msg_item":[{"msgtype":"text","text":{"content":"A"}},
                                       {"msgtype":"image","image":{"url":"https://x/i.png","aeskey":"K3"}},
                                       {"msgtype":"text","text":{"content":"B"}}]}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_TEXT, mixedText.messageType);
        assertEquals("A\nB", mixedText.content);

        IncomingMessage mixedImage = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m6","msgtype":"mixed",
                  "chattype":"single","from":{"userid":"u6"},
                  "mixed":{"msg_item":[{"msgtype":"image","image":{"url":"https://x/only.png","aeskey":"K4"}}]}}}
                """));
        assertEquals(ImTypes.MESSAGE_TYPE_IMAGE, mixedImage.messageType);
        assertEquals("https://x/only.png", mixedImage.fileKey);

        // 引用：文本引用带内容与 bot 身份；图片引用只给 nonTextType
        IncomingMessage quoted = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m7","msgtype":"text","chattype":"single",
                  "from":{"userid":"u7"},"aibotid":"BOT1","text":{"content":"追问"},
                  "quote":{"msgid":"q1","msgtype":"text","from":{"userid":"BOT1"},
                            "text":{"content":"上一轮回答"}}}}
                """));
        assertNotNull(quoted.quote);
        assertEquals("上一轮回答", quoted.quote.content);
        assertEquals("q1", quoted.quote.messageId);
        assertTrue(quoted.quote.isBotMessage);

        IncomingMessage quotedImage = client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m8","msgtype":"text","chattype":"single",
                  "from":{"userid":"u8"},"text":{"content":"这是啥"},
                  "quote":{"msgid":"q2","msgtype":"image","from":{"userid":"u9"}}}}
                """));
        assertEquals("", quotedImage.quote.content);
        assertEquals("image", quotedImage.quote.nonTextType);

        // 事件帧与非消息帧 → null
        assertNull(client.parseCallbackBody(frame("""
                {"cmd":"aibot_event_callback","body":{"msgtype":"event",
                  "event":{"eventtype":"disconnected_event"}}}
                """)));
        assertNull(client.parseCallbackBody(frame("""
                {"cmd":"aibot_msg_callback","body":{"msgid":"m9","msgtype":"video"}}
                """)));
    }

    // ── 流帧与退避 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("流帧：aibot_respond_msg + {msgtype:stream,stream:{id,finish,content}}；退避 1s·2^n 上限 30s")
    void buildsStreamFramesAndBackoff() throws Exception {
        String frame = WecomLongConnClient.buildStreamFrame("stream_7", "全量内容", true, "r1");
        JsonNode node = MAPPER.readTree(frame);
        assertEquals("aibot_respond_msg", node.path("cmd").asText());
        assertEquals("r1", node.path("headers").path("req_id").asText());
        assertEquals("stream", node.path("body").path("msgtype").asText());
        assertEquals("stream_7", node.path("body").path("stream").path("id").asText());
        assertTrue(node.path("body").path("stream").path("finish").asBoolean());
        assertEquals("全量内容", node.path("body").path("stream").path("content").asText());

        assertEquals(1000, WecomLongConnClient.reconnectDelayMs(0));
        assertEquals(1000, WecomLongConnClient.reconnectDelayMs(1));
        assertEquals(4000, WecomLongConnClient.reconnectDelayMs(3));
        assertEquals(30_000, WecomLongConnClient.reconnectDelayMs(9));
        assertEquals(30_000, WecomLongConnClient.reconnectDelayMs(60));
    }

    @Test
    @DisplayName("WS 适配器：入站三面明确不支持（文案照 Go）；出站委托；缺 req_id 报错")
    void wsAdapterDelegates() throws Exception {
        WecomWSAdapter adapter = new WecomWSAdapter(client(""), null);

        assertEquals("WeCom bot adapter does not support webhook callbacks",
                adapter.verifyCallback(null).getMessage());
        assertThrows(IllegalStateException.class, () -> adapter.parseCallback(null));
        assertTrue(!adapter.handleURLVerification(null));

        IncomingMessage noReqId = new IncomingMessage();
        noReqId.platform = ImTypes.PLATFORM_WECOM;
        assertThrows(IllegalStateException.class,
                () -> adapter.sendReply(noReqId, new com.ragagent.im.runtime.ReplyMessage("x")));
    }

    // ── 工厂 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂：websocket（Go 默认）建长连接适配器并给 stop；ws_endpoint 非 wss 照 Go 报错")
    void factoryModes() {
        com.ragagent.im.domain.ImChannelEntity channel = new com.ragagent.im.domain.ImChannelEntity();
        channel.setId("ch-ws");
        channel.setMode("websocket");
        channel.setCredentials("{\"bot_id\":\"B1\",\"bot_secret\":\"S1\","
                + "\"ws_endpoint\":\"wss://127.0.0.1:1/\",\"bot_name\":\"WeKnora Bot\"}");

        com.ragagent.im.service.ImService.AdapterRegistration reg =
                new WecomAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                        .create(channel, (m, c) -> { });
        assertNotNull(reg.adapter());
        assertEquals(ImTypes.PLATFORM_WECOM, reg.adapter().platform());
        assertNotNull(reg.stop());
        reg.stop().run(); // 停掉后台重连线程（端点不可达，只会在退避里打转）

        com.ragagent.im.domain.ImChannelEntity bad = new com.ragagent.im.domain.ImChannelEntity();
        bad.setId("ch-bad");
        bad.setMode("websocket");
        bad.setCredentials("{\"bot_id\":\"B\",\"bot_secret\":\"S\",\"ws_endpoint\":\"https://x/\"}");
        assertThrows(IllegalArgumentException.class,
                () -> new WecomAdapterFactory((com.ragagent.common.security.SsrfGuard) null)
                        .create(bad, (m, c) -> { }));
    }

    // ── 桩与工具 ────────────────────────────────────────────────────────────

    private static WecomLongConnClient client(String botName) {
        return new WecomLongConnClient("B1", "S1", "wss://127.0.0.1:1/", botName, "ch-1",
                (m, c) -> { }, null);
    }

    private static JsonNode frame(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static byte[] encryptCbc(byte[] plain, String aesKey43) throws Exception {
        byte[] key = Base64.getDecoder().decode(aesKey43 + "=");
        int pad = 16 - (plain.length % 16);
        byte[] padded = Arrays.copyOf(plain, plain.length + pad);
        for (int i = plain.length; i < padded.length; i++) {
            padded[i] = (byte) pad;
        }
        return aesCrypt(padded, key, Cipher.ENCRYPT_MODE);
    }

    private static byte[] encryptNoPadding(byte[] block, byte[] key) throws Exception {
        return aesCrypt(block, key, Cipher.ENCRYPT_MODE);
    }

    private static byte[] aesCrypt(byte[] data, byte[] key, int mode) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        return cipher.doFinal(data);
    }
}
