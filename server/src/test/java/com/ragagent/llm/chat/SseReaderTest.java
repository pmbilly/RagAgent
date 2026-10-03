package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * SSE 读取的兼容性纪律，逐条钉死。
 */
class SseReaderTest {

    private static SseReader reader(String text) {
        return new SseReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** data: 前缀（带空格）；event:/id: 与空行跳过；EOF 结束。 */
    @Test
    void readsDataLinesAndSkipsOthers() throws IOException {
        SseReader r = reader("""
                event: message

                id: 1

                data: {"a":1}

                data: {"b":2}

                data: [DONE]

                """);

        assertEquals("{\"a\":1}", r.readEvent().orElseThrow().dataText());
        assertEquals("{\"b\":2}", r.readEvent().orElseThrow().dataText());
        assertTrue(r.readEvent().orElseThrow().done());
        assertEquals(Optional.empty(), r.readEvent(), "EOF 之后返回空");
        assertEquals(Optional.empty(), r.readEvent(), "再次读取仍为空");
    }

    /** data: 后没有空格（增强兼容性），以及 data: 后直接是空。 */
    @Test
    void readsDataWithoutSpace() throws IOException {
        SseReader r = reader("data:{\"a\":1}\n" + "data:\n" + "data: [DONE]\n");

        assertEquals("{\"a\":1}", r.readEvent().orElseThrow().dataText());
        SseReader.SseEvent empty = r.readEvent().orElseThrow();
        assertEquals("", empty.dataText());
        assertFalse(empty.done());
        assertTrue(r.readEvent().orElseThrow().done());
    }

    /** [DONE] 必须是整行精确匹配：带尾空格的 "data: [DONE] " 只当普通数据。 */
    @Test
    void doneMarkerRequiresExactLine() throws IOException {
        SseReader r = reader("data: [DONE] \ndata: [DONE]\n");

        SseReader.SseEvent first = r.readEvent().orElseThrow();
        assertFalse(first.done(), "带尾空格的 [DONE] 只当普通数据");
        assertEquals("[DONE] ", first.dataText());
        assertTrue(r.readEvent().orElseThrow().done());
    }

    /** 行尾 \r\n 与最后一行没有换行符（EOF 收尾）都要兼容。 */
    @Test
    void handlesCrlfAndMissingTrailingNewline() throws IOException {
        SseReader r = reader("data: {\"a\":1}\r\n\r\ndata: [DONE]");

        assertEquals("{\"a\":1}", r.readEvent().orElseThrow().dataText());
        assertTrue(r.readEvent().orElseThrow().done());
        assertEquals(Optional.empty(), r.readEvent());
    }

    /** 超长行（> 1MB）报 "bufio.Scanner: token too long"。 */
    @Test
    void rejectsOverlongLine() {
        StringBuilder sb = new StringBuilder("data: ");
        sb.append("x".repeat(SseReader.MAX_LINE_BYTES + 10));
        sb.append('\n');

        SseReader r = reader(sb.toString());
        IOException e = assertThrows(IOException.class, r::readEvent);
        assertEquals("bufio.Scanner: token too long", e.getMessage());
    }

    /** 1MB 以内的大行可以正常读出（思维链内容可能很长）。 */
    @Test
    void acceptsLargeLineWithinLimit() throws IOException {
        String payload = "y".repeat(600 * 1024);
        SseReader r = reader("data: " + payload + "\n");

        assertEquals(payload, r.readEvent().orElseThrow().dataText());
    }

    /** 空流立即 EOF。 */
    @Test
    void emptyStreamIsImmediatelyEof() throws IOException {
        assertEquals(Optional.empty(), new SseReader(new ByteArrayInputStream(new byte[0])).readEvent());
    }

    /** 中文与 emoji 的 data 逐字节往返（多字节 UTF-8 不被破坏）。 */
    @Test
    void preservesMultibyteData() throws IOException {
        SseReader r = reader("data: {\"content\":\"世界 🌏\"}\n");

        assertEquals("{\"content\":\"世界 🌏\"}", r.readEvent().orElseThrow().dataText());
    }

    /** 构造器接受的 InputStream 会被包成带缓冲的流（不影响语义）。 */
    @Test
    void worksWithPlainInputStream() throws IOException {
        InputStream in = new ByteArrayInputStream("data: x\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("x", new SseReader(in).readEvent().orElseThrow().dataText());
    }
}
