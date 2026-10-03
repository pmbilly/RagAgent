package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatTool;
import com.ragagent.llm.domain.MessageContentPart;
import com.ragagent.llm.domain.ToolCall;

/**
 * token 估算的录制常量断言（期望值由录制程序对旧分词器跑出真值后逐字节抄入）。
 * 覆盖：36 条字符串语料（英文/中文/日韩/混合/代码/JSON/
 * emoji/空白/URL/长词）+ 消息族（reasoning/图片/multimodal/工具调用/命名）+
 * EstimateMessages 尾部 + EstimateTools。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenEstimatorTest {

    private TokenEstimator e;

    @BeforeAll
    void setUp() {
        e = new TokenEstimator();
    }

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    void corpusMatchesGoRecording() {
        String[] corpus = {
            "",
            "hello world",
            "你好世界测试数据中文",
            repeat("a", 100),
            repeat("中", 100),
            "hello",
            "thinking...",
            "{\"query\": \"test\"}",
            "You are a helpful assistant.",
            "Hello",
            "Hi there!",
            repeat("let me think about this step by step. ", 200),
            "short answer",
            "what is this",
            "The quick brown fox jumps over the lazy dog. 0123456789, punctuation! And a URL: https://example.com/path?q=1&r=2",
            "上下五千年，纵横九万里。千里江陵一日还。",
            "Mixed 中英文 text with numbers 12345 and symbols #$%^&*()",
            "function greet(name) {\n  return `hello ${name}`;\n}\n// comment",
            "# Heading\n\n- bullet one\n- bullet two\n\n```python\nprint('hi')\n```",
            "a\nb\r\nc\n\nd\t e",
            "   leading and trailing spaces   ",
            "🎉 emoji and 👨‍👩‍👧‍👦 family emoji",
            repeat("supercalifragilisticexpialidocious", 5),
            "JSON: {\"key\": \"value\", \"n\": 3.14159, \"arr\": [1,2,3]}",
            "<html><body>&amp; &lt;tag&gt;</body></html>",
            "tabbed\tcolumns\tand    runs    of    spaces",
            "ünïcödé äccénts and ß",
            repeat("word ", 250),
            "_LINE_1_\n_LINE_2_\n_LINE_3_",
            "とんかつ、ラーメン、すし。ひらがなとカタカナ。",
            "한국어 텍스트도 세어 봅니다.",
            "\n\n",
            " ",
            "1",
            "12345678901234567890",
            "https://very-long-subdomain.example.museum/with/a/very/long/path?query=parameter&another=one",
        };
        int[] expected = {
            0, 2, 9, 13, 100, 1, 2, 6, 6, 1, 3, 1801, 2, 3, 33, 27, 17, 15, 19, 9,
            6, 24, 63, 27, 16, 11, 13, 251, 12, 24, 16, 1, 1, 1, 7, 25,
        };
        assertThat(corpus).hasSameSizeAs(expected);
        for (int i = 0; i < corpus.length; i++) {
            assertThat(e.estimateString(corpus[i])).as("str%d %s", i, corpus[i]).isEqualTo(expected[i]);
        }
    }

    private ChatMessage msg(String role, String content) {
        return new ChatMessage(role, content);
    }

    @Test
    void messageFixturesMatchGoRecording() {
        // m_empty = {}（全零值）
        assertThat(e.estimateMessage(new ChatMessage())).isEqualTo(3);

        // m_assistant
        assertThat(e.estimateMessage(msg("assistant", "hello"))).isEqualTo(5);

        // m_toolcall
        ChatMessage toolCall = msg("assistant", "thinking...");
        ToolCall tc = new ToolCall();
        tc.setId("c1");
        tc.setFunction(new com.ragagent.llm.domain.FunctionCall("knowledge_search", "{\"query\": \"test\"}"));
        toolCall.setToolCalls(List.of(tc));
        // id/type 取零值时与空消息同构
        assertThat(e.estimateMessage(toolCall)).isEqualTo(18);

        // m_reasoning / m_plain：reasoning 必须计数（130k 量成 26k 的回归）
        String reasoning = repeat("let me think about this step by step. ", 200);
        ChatMessage plain = msg("assistant", "short answer");
        ChatMessage thinking = msg("assistant", "short answer");
        thinking.setReasoningContent(reasoning);
        assertThat(e.estimateMessage(thinking)).isEqualTo(1807);
        assertThat(e.estimateMessage(plain)).isEqualTo(6);
        assertThat(e.estimateMessage(thinking)).isGreaterThan(e.estimateMessage(plain) + 1000);
        // 差值 ≈ reasoning 自身的 token 数（±2，Go InDelta 同断言）
        assertThat(Math.abs(e.estimateString(reasoning)
                - (e.estimateMessage(thinking) - e.estimateMessage(plain)))).isLessThanOrEqualTo(2);

        // m_tool
        ChatMessage tool = ChatMessage.tool("call-abc-1", "web_search", "search results body");
        assertThat(e.estimateMessage(tool)).isEqualTo(14);

        // m_named_user
        ChatMessage named = msg("user", "hi there");
        named.setName("alice");
        assertThat(e.estimateMessage(named)).isEqualTo(7);
    }

    @Test
    void imageFixturesMatchGoRecording() {
        // m_images：图片计常量 1200，与 URL 长短无关
        ChatMessage withImages = msg("user", "what is this");
        withImages.setImages(List.of("https://x/a.png"));
        assertThat(e.estimateMessage(withImages)).isEqualTo(1207);

        // m_multi：MultiContent 才是实际发送形态
        ChatMessage multi = msg("user", "");
        multi.setMultiContent(List.of(
                MessageContentPart.text("describe"),
                MessageContentPart.image("data:image/png;base64,AAAA", "")));
        assertThat(e.estimateMessage(multi)).isEqualTo(1205);

        // Images + MultiContent 并存时同一批图只计一次（MultiContent 优先）
        ChatMessage both = msg("user", "");
        both.setImages(List.of("https://x/a.png"));
        both.setMultiContent(multi.getMultiContent());
        assertThat(e.estimateMessage(both)).isEqualTo(e.estimateMessage(multi));

        // m_multitext：纯文本 parts 逐个 BPE
        ChatMessage multitext = msg("user", "");
        multitext.setMultiContent(List.of(
                MessageContentPart.text("first part"),
                MessageContentPart.text("second part")));
        assertThat(e.estimateMessage(multitext)).isEqualTo(8);
    }

    @Test
    void conversationTailAndToolsMatchGoRecording() {
        // messages3
        List<ChatMessage> msgs = List.of(
                msg("system", "You are a helpful assistant."),
                msg("user", "Hello"),
                msg("assistant", "Hi there!"));
        assertThat(e.estimateMessages(msgs)).isEqualTo(25);
        assertThat(e.estimateMessages(List.of())).isEqualTo(3);

        // tools2（parameters 以紧凑 JSON 计数）
        ObjectMapper mapper = new ObjectMapper();
        ChatTool shell;
        ChatTool search;
        try {
            shell = new ChatTool("shell_exec",
                    repeat("run a shell command in the sandbox. ", 20),
                    mapper.readTree("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}}}"));
            search = new ChatTool("knowledge_search", "Search the knowledge base",
                    mapper.readTree("{}"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(e.estimateTools(List.of(shell, search))).isEqualTo(200);
        assertThat(e.estimateTools(null)).isEqualTo(0);
    }
}
