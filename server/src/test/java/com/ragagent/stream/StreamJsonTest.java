package com.ragagent.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ragagent.common.llm.ResponseType;
import com.ragagent.llm.domain.TokenUsage;
import org.junit.jupiter.api.Test;

/**
 * 流事件的 JSON 字节形状——**期望值全部是实测 Go 出来的**，不是照直觉写的。
 *
 * <p>录制方式：把 {@code interfaces.StreamEvent} / {@code liveRunPayload} 的定义抄进一个
 * 独立 Go 程序跑 {@code json.Marshal}。之所以不直接调仓库里的类型，是因为 Go 仓在本项目里
 * 是只读对照。</p>
 *
 * <p>为什么值得逐字节钉死：这些 JSON 落的是 Go 与 Java <b>共用</b>的 Redis 键，且
 * {@code ClearLiveRun} / {@code UpdateSteerEventData} 都在原始字节上做 CAS 比对。</p>
 */
class StreamJsonTest {

    /** timestamp 含时区，随 JVM 默认时区变化——比对前先替换掉，形状另测。 */
    private static String maskTimestamp(String json) {
        return json.replaceFirst("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<TS>\"");
    }

    @Test
    void eventMatchesGoByteForByte() {
        // Go: {"id":"e-1","type":"answer","content":"hi <b>&</b>","done":true,"timestamp":"...",
        //      "data":{"alpha":"a","consumed":true,"zebra":1},
        //      "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7,"cache_reported":false}}
        StreamEvent event = new StreamEvent("e-1", ResponseType.ANSWER, "hi <b>&</b>", true);
        event.setTimestamp(OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 123_456_000, ZoneOffset.ofHours(8)));
        // 刻意乱序插入：Go 的 map 按 key 字母序输出，Java 侧必须对齐
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zebra", 1);
        data.put("alpha", "a");
        data.put("consumed", true);
        event.setData(data);
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(3);
        usage.setCompletionTokens(4);
        usage.setTotalTokens(7);
        event.setUsage(usage);

        String expected = "{\"id\":\"e-1\",\"type\":\"answer\","
                + "\"content\":\"hi \\u003cb\\u003e\\u0026\\u003c/b\\u003e\","
                + "\"done\":true,\"timestamp\":\"<TS>\","
                + "\"data\":{\"alpha\":\"a\",\"consumed\":true,\"zebra\":1},"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7,"
                + "\"cache_reported\":false}}";

        assertEquals(expected, maskTimestamp(StreamJson.write(event)));
    }

    @Test
    void omitsEmptyDataAndUsage() {
        // Go: {"id":"e-2","type":"steer","content":"","done":false,"timestamp":"..."}
        StreamEvent event = new StreamEvent("e-2", ResponseType.STEER, "", false);
        event.setTimestamp(OffsetDateTime.now());

        String expected = "{\"id\":\"e-2\",\"type\":\"steer\",\"content\":\"\",\"done\":false,"
                + "\"timestamp\":\"<TS>\"}";

        assertEquals(expected, maskTimestamp(StreamJson.write(event)));
    }

    @Test
    void stringEscapingMatchesGo() {
        // 实测 Go 对 [hi <b>&</b>] + 换行 + 制表 + 引号 + 反斜杠 + 0x00 + 0x08 + 0x1F 的输出：
        //   · < > &       → < > &（Jackson 默认**不**转义）
        //   · 0x08        → \b（Go 与 Jackson 的短转义一致）
        //   · 0x00 / 0x1F → U+00xx 形态但十六进制**小写**（Jackson 默认大写，是差异点）
        String raw = "hi <b>&</b>\n\t\"\\" + (char) 0x00 + '\b' + (char) 0x1F;
        String expected = "\"hi \\u003cb\\u003e\\u0026\\u003c/b\\u003e\\n\\t\\\"\\\\"
                + "\\u0000\\b\\u001f\"";

        assertEquals(expected, StreamJson.write(raw));

        // 实测 Go 对 0x0B/0x0C/0x07 的输出：0x0C 走 \f 短转义，另两个走小写十六进制
        assertEquals("\"\\u000b\\f\\u0007\"",
                StreamJson.write("" + (char) 0x0B + (char) 0x0C + (char) 0x07));
    }

    @Test
    void nonAsciiPassesThroughUnescaped() {
        // Go 只在 escapeHTML 下动 < > &，中文原样输出
        assertEquals("\"中文 ok\"", StreamJson.write("中文 ok"));
    }

    @Test
    void liveRunPayloadMatchesGo() {
        // Go: {"assistant_message_id":"msg-1","request_id":"req-1"}
        assertEquals(
                "{\"assistant_message_id\":\"msg-1\",\"request_id\":\"req-1\"}",
                StreamJson.write(new LiveRunPayload("msg-1", "req-1")));
    }

    @Test
    void writeStringMatchesGoMarshalOfAString() {
        // ClearLiveRun 的 CAS needle 靠它拼出来，必须带引号且转义一致
        assertEquals("\"msg-1\"", StreamJson.writeString("msg-1"));
        assertEquals("\"a\\u003cb\\u0026c\\u003ed\"", StreamJson.writeString("a<b&c>d"));
    }

    @Test
    void timestampIsRfc3339NanoInLocalZone() {
        // 形状与 Go 的 time.Time 一致：纳秒尾部零裁剪
        OffsetDateTime instant = OffsetDateTime.of(2026, 9, 18, 10, 30, 0, 123_456_000, ZoneOffset.ofHours(8));
        StreamEvent event = new StreamEvent("t", ResponseType.ANSWER, "x", false);
        event.setTimestamp(instant);
        String json = StreamJson.write(event);

        assertTrue(json.matches(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?"
                        + "([+-]\\d{2}:\\d{2}|Z)\".*"),
                "timestamp 不是 RFC3339Nano 形状: " + json);

        // 且还原回来是同一个瞬间
        StreamEvent back = StreamJson.read(json, StreamEvent.class);
        assertEquals(instant.toInstant(), back.getTimestamp().toInstant());
    }

    @Test
    void readsGoWrittenJsonIncludingUnknownKeys() {
        // Go 的行可能带 Java 尚未建模的键；json.Unmarshal 默认忽略，Java 侧必须同样宽容
        String goRow = "{\"id\":\"e-9\",\"type\":\"answer\",\"content\":\"c\",\"done\":false,"
                + "\"timestamp\":\"2026-09-18T10:30:00+08:00\",\"some_future_key\":42}";

        StreamEvent event = StreamJson.read(goRow, StreamEvent.class);
        assertEquals("e-9", event.getId());
        assertEquals(ResponseType.ANSWER, event.getType());
        assertEquals("c", event.getContent());
    }
}
