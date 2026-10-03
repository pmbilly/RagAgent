package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
/**
 * {@code JsonFieldExtractor} 全分支用例（11 个用例）。
 *
 * <p>额外补了多字节 UTF-8 边界一例（见 {@link #multibyteSplitAcrossChunks}），
 * 覆盖 Java 侧"停在字符边界前"的取舍。</p>
 */
class JsonFieldExtractorTest {

    @Test
    void basic() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"");
        got += e.feed("Hello");
        got += e.feed(" world");
        got += e.feed("\"}");

        assertEquals("Hello world", got);
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    @Test
    void withEscapes() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"line1\\nline2");
        got += e.feed(" and \\\"quoted");
        got += e.feed("\\\"\"}");

        assertEquals("line1\nline2 and \"quoted\"", got);
    }

    @Test
    void oneChunk() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = e.feed("{\"answer\":\"complete answer here\"}");

        assertEquals("complete answer here", got);
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    /** 逐字符喂 */
    @Test
    void smallChunks() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        String[] chunks = {"{", "\"", "a", "n", "s", "w", "e", "r", "\"", ":", "\"", "H", "i", "\"", "}"};
        for (String c : chunks) {
            got += e.feed(c);
        }

        assertEquals("Hi", got);
    }

    @Test
    void markdown() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"# Title\\n\\n");
        got += e.feed("This is **bold** and ");
        got += e.feed("*italic* text.\\n\\n");
        got += e.feed("- item 1\\n- item 2");
        got += e.feed("\"}");

        assertEquals("# Title\n\nThis is **bold** and *italic* text.\n\n- item 1\n- item 2", got);
    }

    @Test
    void unicodeEscape() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"Hello \\u4e16\\u754c");
        got += e.feed("\"}");

        assertEquals("Hello 世界", got);
    }

    /** 转义序列跨分片 */
    @Test
    void incompleteEscapeAtBoundary() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"before\\");
        got += e.feed("nafter\"}");

        assertEquals("before\nafter", got);
    }

    @Test
    void whitespaceInJson() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        assertEquals("content here", e.feed("{ \"answer\" : \"content here\" }"));
    }

    @Test
    void emptyAnswer() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        assertEquals("", e.feed("{\"answer\":\"\"}"));
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    /** extracting "thought"（thinking 工具用） */
    @Test
    void thoughtField() {
        JsonFieldExtractor e = new JsonFieldExtractor("thought");

        String got = "";
        got += e.feed("{\"thought\":\"Let me analyze");
        got += e.feed(" the problem step by step");
        got += e.feed("\",\"next_thought_needed\":true,\"thought_number\":1,\"total_thoughts\":3}");

        assertEquals("Let me analyze the problem step by step", got);
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    @Test
    void thoughtFieldWithEscapes() {
        JsonFieldExtractor e = new JsonFieldExtractor("thought");

        String got = "";
        got += e.feed("{\"thought\":\"Step 1:\\n- Analyze the query\\n- ");
        got += e.feed("Search for \\\"relevant\\\" info");
        got += e.feed("\",\"thought_number\":1}");

        assertEquals("Step 1:\n- Analyze the query\n- Search for \"relevant\" info", got);
    }

    /**
     * Java 侧补充：多字节（3/4 字节 UTF-8）字符跨分片时必须原样产出，不能被切开——
     * 扫描按 rune 步进而不是按字节步进。
     */
    @Test
    void multibyteCharactersAcrossChunks() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"世");
        got += e.feed("界 🌏");
        got += e.feed("\"}");

        assertEquals("世界 🌏", got);
        assertTrue(e.isDone());
    }

    /** 未找到字段或还没到值的开引号时不产出（字段名未命中即短路）。 */
    @Test
    void waitsForValueStart() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");
        assertEquals("", e.feed("{\"other\":\"x\","));
        assertEquals("", e.feed("\"answer\""));
        assertEquals("", e.feed(":"));
        assertFalse(e.isDone());
        assertEquals("v", e.feed("\"v\""));
    }

    /** 值结束后的额外 feed 一律返回空串（终态短路）。 */
    @Test
    void feedAfterDoneReturnsEmpty() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");
        assertEquals("x", e.feed("{\"answer\":\"x\"}"));
        assertEquals("", e.feed("ignored"));
    }
}
