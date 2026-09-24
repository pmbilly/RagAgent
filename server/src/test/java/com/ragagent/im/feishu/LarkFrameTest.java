package com.ragagent.im.feishu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * lark {@code pbbp2} 帧编解码的字节契约。
 *
 * <p>三段 fixture 由<b>独立 Go 程序</b>用官方 SDK 的 {@code ws} 包录出（{@code ws.NewPingFrame}、
 * 事件数据帧、回执帧三例；录制程序用 {@code Frame.Marshal()} 打 hex），断言 Java 侧
 * <b>解码字段一致</b>且<b>重编码逐字节相等</b>——覆盖 Go 的无条件书写规则（空串写成 0 长度字段、
 * {@code payload} 非 nil 才写、{@code headers} 非空才写）与字段升序。</p>
 */
class LarkFrameTest {

    /** Go: ws.NewPingFrame(41) + SeqID=0, LogID=7 + Marshal()。 */
    private static final String PING_HEX =
            "08001007182920002a0c0a0474797065120470696e6732003a004a00";

    /** Go: 事件数据帧（method=1 + type/message_id/sum/seq/trace_id + json/event + payload）。 */
    private static final String DATA_HEX =
            "08031009182920012a0d0a047479706512056576656e742a120a0a6d6573736167655f696412"
                    + "046f6d5f312a080a0373756d1201312a080a037365711201302a100a0874726163655f69"
                    + "64120474725f3132046a736f6e3a056576656e7442407b22736368656d61223a22322e30"
                    + "222c22686561646572223a7b226576656e745f74797065223a22696d2e6d6573736167"
                    + "652e726563656976655f7631227d7d4a00";

    /** Go: 同上并追加 biz_rt=12、payload={"code":200}（即回执帧）。 */
    private static final String ACK_HEX =
            "08031009182920012a0d0a047479706512056576656e742a120a0a6d6573736167655f696412"
                    + "046f6d5f312a080a0373756d1201312a080a037365711201302a100a0874726163655f69"
                    + "64120474725f312a0c0a0662697a5f72741202313232046a736f6e3a056576656e74420c"
                    + "7b22636f6465223a3230307d4a00";

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    @Test
    @DisplayName("ping 帧：解码字段一致；重编码与 Go 逐字节相等（无 payload、空串照写）")
    void decodesAndReEncodesPing() {
        LarkFrame frame = LarkFrame.decode(hex(PING_HEX));
        assertEquals(LarkFrame.METHOD_CONTROL, frame.method);
        assertEquals(41, frame.service);
        assertEquals(7, frame.logId);
        assertEquals(0, frame.seqId);
        assertEquals("ping", frame.header("type"));
        assertNull(frame.payload);                       // Go 未写 payload 字段
        assertEquals("", frame.payloadEncoding);
        assertArrayEquals(hex(PING_HEX), frame.encode());

        // 构造口与 fixture 等价（仅 service/logId 需显式给）
        LarkFrame built = LarkFrame.ping(41);
        built.logId = 7;
        assertArrayEquals(hex(PING_HEX), built.encode());
    }

    @Test
    @DisplayName("数据帧：头表有序、payload/编码字段按 Go 写法；重编码逐字节相等")
    void decodesAndReEncodesData() {
        LarkFrame frame = LarkFrame.decode(hex(DATA_HEX));
        assertEquals(3, frame.seqId);
        assertEquals(9, frame.logId);
        assertEquals(41, frame.service);
        assertEquals(LarkFrame.METHOD_DATA, frame.method);
        assertEquals("event", frame.header("type"));
        assertEquals("om_1", frame.header("message_id"));
        assertEquals(1, frame.intHeader("sum"));
        assertEquals(0, frame.intHeader("seq"));
        assertEquals("tr_1", frame.header("trace_id"));
        assertEquals("json", frame.payloadEncoding);
        assertEquals("event", frame.payloadType);
        assertTrue(new String(frame.payload, StandardCharsets.UTF_8)
                .contains("im.message.receive_v1"));
        assertArrayEquals(hex(DATA_HEX), frame.encode());
    }

    @Test
    @DisplayName("回执帧：同帧 + 追加 biz_rt + 换 payload → 与 Go 逐字节相等")
    void ackFrameMatchesGo() throws Exception {
        LarkFrame frame = LarkFrame.decode(hex(DATA_HEX));
        frame.addHeader("biz_rt", "12");
        frame.payload = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(hex(ACK_HEX), frame.encode());

        LarkFrame ack = LarkFrame.decode(hex(ACK_HEX));
        assertEquals("12", ack.header("biz_rt"));
        assertEquals("{\"code\":200}", new String(ack.payload, StandardCharsets.UTF_8));
        assertEquals("om_1", ack.header("message_id"));
    }

    @Test
    @DisplayName("未知字段跳过：混入 fixed64/未知 varint/未知长字段仍能解出已知字段")
    void skipsUnknownFields() {
        // 手写：field1 varint=5、field 10 fixed64、field 11 varint、field 12 bytes、field2 varint=8
        byte[] raw = HexFormat.of().parseHex(
                "0805"                  // SeqID = 5
                        + "510000000000000000"   // field 10, wire 1 (fixed64) 8 字节
                        + "5807"                  // field 11, wire 0, varint 7
                        + "6203616263"            // field 12, wire 2, "abc"
                        + "1008"                  // LogID = 8
        );
        LarkFrame frame = LarkFrame.decode(raw);
        assertEquals(5, frame.seqId);
        assertEquals(8, frame.logId);
    }
}
