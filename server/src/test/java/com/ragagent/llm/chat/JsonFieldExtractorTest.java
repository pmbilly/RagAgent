package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/chat/json_field_extractor_test.go 全文（11 个用例逐条移植）。
 *
 * <p>额外补了多字节 UTF-8 边界一例（见 {@link #multibyteSplitAcrossChunks}），
 * 覆盖 Java 侧"停在字符边界前"的取舍。</p>
 */
class JsonFieldExtractorTest {

    /** 对照 Go TestJSONFieldExtractor_Basic */
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

    /** 对照 Go TestJSONFieldExtractor_WithEscapes */
    @Test
    void withEscapes() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"line1\\nline2");
        got += e.feed(" and \\\"quoted");
        got += e.feed("\\\"\"}");

        assertEquals("line1\nline2 and \"quoted\"", got);
    }

    /** 对照 Go TestJSONFieldExtractor_OneChunk */
    @Test
    void oneChunk() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = e.feed("{\"answer\":\"complete answer here\"}");

        assertEquals("complete answer here", got);
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    /** 对照 Go TestJSONFieldExtractor_SmallChunks：逐字符喂 */
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

    /** 对照 Go TestJSONFieldExtractor_Markdown */
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

    /** 对照 Go TestJSONFieldExtractor_UnicodeEscape */
    @Test
    void unicodeEscape() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"Hello \\u4e16\\u754c");
        got += e.feed("\"}");

        assertEquals("Hello 世界", got);
    }

    /** 对照 Go TestJSONFieldExtractor_IncompleteEscapeAtBoundary：转义序列跨分片 */
    @Test
    void incompleteEscapeAtBoundary() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        String got = "";
        got += e.feed("{\"answer\":\"before\\");
        got += e.feed("nafter\"}");

        assertEquals("before\nafter", got);
    }

    /** 对照 Go TestJSONFieldExtractor_WhitespaceInJSON */
    @Test
    void whitespaceInJson() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        assertEquals("content here", e.feed("{ \"answer\" : \"content here\" }"));
    }

    /** 对照 Go TestJSONFieldExtractor_EmptyAnswer */
    @Test
    void emptyAnswer() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");

        assertEquals("", e.feed("{\"answer\":\"\"}"));
        assertTrue(e.isDone(), "expected extractor to be done");
    }

    /** 对照 Go TestJSONFieldExtractor_ThoughtField：extracting "thought"（thinking 工具用） */
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

    /** 对照 Go TestJSONFieldExtractor_ThoughtFieldWithEscapes */
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

    /** 未找到字段或还没到值的开引号时不产出（对照 Go 的 valueStart<0 短路）。 */
    @Test
    void waitsForValueStart() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");
        assertEquals("", e.feed("{\"other\":\"x\","));
        assertEquals("", e.feed("\"answer\""));
        assertEquals("", e.feed(":"));
        assertFalse(e.isDone());
        assertEquals("v", e.feed("\"v\""));
    }

    /** 值结束后的额外 feed 一律返回空串（对照 Go 的 done 短路）。 */
    @Test
    void feedAfterDoneReturnsEmpty() {
        JsonFieldExtractor e = new JsonFieldExtractor("answer");
        assertEquals("x", e.feed("{\"answer\":\"x\"}"));
        assertEquals("", e.feed("ignored"));
    }
}
