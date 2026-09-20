package com.ragagent.agent.compaction;

import static com.ragagent.agent.GoRecording.STR_ARGS0;
import static com.ragagent.agent.GoRecording.STR_ARGS1;
import static com.ragagent.agent.GoRecording.STR_ARGS10;
import static com.ragagent.agent.GoRecording.STR_ARGS11;
import static com.ragagent.agent.GoRecording.STR_ARGS12;
import static com.ragagent.agent.GoRecording.STR_ARGS13;
import static com.ragagent.agent.GoRecording.STR_ARGS14;
import static com.ragagent.agent.GoRecording.STR_ARGS15;
import static com.ragagent.agent.GoRecording.STR_ARGS2;
import static com.ragagent.agent.GoRecording.STR_ARGS3;
import static com.ragagent.agent.GoRecording.STR_ARGS4;
import static com.ragagent.agent.GoRecording.STR_ARGS5;
import static com.ragagent.agent.GoRecording.STR_ARGS6;
import static com.ragagent.agent.GoRecording.STR_ARGS7;
import static com.ragagent.agent.GoRecording.STR_ARGS8;
import static com.ragagent.agent.GoRecording.STR_ARGS9;
import static com.ragagent.agent.GoRecording.STR_CALLS2;
import static com.ragagent.agent.GoRecording.STR_CALLS_BAD;
import static com.ragagent.agent.GoRecording.STR_CONVERSATION;
import static com.ragagent.agent.GoRecording.STR_CONVERSATION_EMPTY;
import static com.ragagent.agent.GoRecording.STR_RAW_ARCHIVE;
import static com.ragagent.agent.GoRecording.STR_RAW_ARCHIVE_EMPTY;
import static com.ragagent.agent.GoRecording.STR_TRUNC_CJK;
import static com.ragagent.agent.GoRecording.STR_TRUNC_EXACT;
import static com.ragagent.agent.GoRecording.STR_TRUNC_MULTILINE;
import static com.ragagent.agent.GoRecording.STR_TRUNC_NEWLINE_MARKER;
import static com.ragagent.agent.GoRecording.STR_TRUNC_OVER;
import static com.ragagent.agent.GoRecording.STR_TRUNC_SHORT;
import static com.ragagent.agent.GoRecording.STR_TRUNC_TRIM;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.agent.GoRecording;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * 摘要序列化的 Go 实录断言（/tmp/wave42rec：serialize.go 全文内嵌跑真值）。
 * 覆盖：对话转写（system 跳过/正文与工具结果截断/reasoning/空消息省略/中文按 rune
 * 截断）、truncate 七态、rawArchive、renderToolArgs 16 态（键字节序、float64 语义、
 * HTML 转义、非法 JSON 回退、非对象回退、截断参数）、serializeToolCalls。
 */
class ConversationSerializerTest {

    private static final String FILLER30 = "some conversation content ".repeat(30);
    private static final String FILLER40 = "some conversation content ".repeat(40);

    private ChatMessage msg(String role, String content) {
        return new ChatMessage(role, content);
    }

    private ToolCall call(String id, String name, String arguments) {
        ToolCall tc = new ToolCall();
        tc.setId(id);
        tc.setFunction(new com.ragagent.llm.domain.FunctionCall(name, arguments));
        return tc;
    }

    /** 录制脚本 recSerialize 的 convo 夹具。 */
    private List<ChatMessage> recordingConvo() {
        ChatMessage assistantReasoning = msg("assistant", "I'll research first.");
        assistantReasoning.setReasoningContent("need to look up facts");
        ChatMessage assistantCalls = msg("assistant", "");
        assistantCalls.setToolCalls(List.of(
                call("call-a", "web_search", "{\"query\":\"coral reef facts\",\"limit\":5}"),
                call("call-b", "write_sandbox_file",
                        "{\"path\":\"/workspace/out.html\",\"content\":\"" + FILLER40 + "\"}")));
        return List.of(
                msg("system", "you are an agent"),
                msg("user", "build me a deck about coral reefs"),
                assistantReasoning,
                assistantCalls,
                ChatMessage.tool("call-a", "web_search", FILLER30),
                ChatMessage.tool("call-b", "write_sandbox_file", "File written: /workspace/out.html"),
                msg("assistant", ""),
                ChatMessage.tool("call-x", "empty_tool", ""),
                msg("user", "长".repeat(4001)));
    }

    @Test
    void conversationTranscriptMatchesGoByteForByte() {
        assertThat(ConversationSerializer.serializeConversation(recordingConvo()))
                .isEqualTo(STR_CONVERSATION);
        assertThat(ConversationSerializer.serializeConversation(List.of()))
                .isEqualTo(STR_CONVERSATION_EMPTY);
    }

    @Test
    void truncateMatchesGoByteForByte() {
        assertThat(ConversationSerializer.truncate("hello", 10)).isEqualTo(STR_TRUNC_SHORT);
        assertThat(ConversationSerializer.truncate("exactly10!", 10)).isEqualTo(STR_TRUNC_EXACT);
        assertThat(ConversationSerializer.truncate("x".repeat(15), 10)).isEqualTo(STR_TRUNC_OVER);
        assertThat(ConversationSerializer.truncate("中".repeat(15), 10)).isEqualTo(STR_TRUNC_CJK);
        assertThat(ConversationSerializer.truncate("  padded  ", 10)).isEqualTo(STR_TRUNC_TRIM);
        assertThat(ConversationSerializer.truncate("line1\n\nline3", 10)).isEqualTo(STR_TRUNC_MULTILINE);
        assertThat(ConversationSerializer.truncate("abc\n\nmore text here to be cut", 5))
                .isEqualTo(STR_TRUNC_NEWLINE_MARKER);
        // Go TrimSpace 的 unicode 空白（NBSP）——Java strip() 会漏
        assertThat(ConversationSerializer.truncate("\u00A0padded\u00A0", 10)).isEqualTo("padded");
    }

    @Test
    void rawArchiveMatchesGo() {
        assertThat(ConversationSerializer.rawArchive(recordingConvo())).isEqualTo(STR_RAW_ARCHIVE);
        assertThat(ConversationSerializer.rawArchive(List.of())).isEqualTo(STR_RAW_ARCHIVE_EMPTY);
    }

    @Test
    void renderToolArgsMatchesGoByteForByte() {
        String[] cases = {
            "{\"query\":\"coral reef facts\",\"limit\":5}",
            "{\"b\":2,\"a\":1,\"c\":{\"z\":true,\"y\":null}}",
            "{\"path\":\"/workspace/output/deck.html\",\"content\":\"<html>&body</html>\"}",
            "{\"num\":3.5,\"int\":3,\"big\":123456789012345678901234567890,\"neg\":-0.5,\"exp\":1e21,\"small\":1e-7}",
            "{\"arr\":[1,\"two\",null,true,{\"k\":\"v\"}]}",
            "{\"empty_obj\":{},\"empty_arr\":[]}",
            "{\"unicode\":\"你好🎉\",\"quote\":\"he said \\\"hi\\\"\",\"ctrl\":\"line1\\nline2\\ttab\"}",
            "{\"html\":\"<script>alert('&')</script>\"}",
            "{}",
            "not json",
            "[1,2,3]",
            "\"just a string\"",
            "{\"path\":\"/workspace/output/a.html\",\"content\":\"<htm",
            "{\"nested\":{\"deep\":{\"deeper\":[{}]}}}",
            "{\"zz\":\"last\",\"aa\":\"first\",\"AA\":\"uppercase\",\"0\":\"zero\"}",
            "{\"emoji_key🎉\":1,\"emoji_key😀\":2}",
        };
        String[] expected = {
            STR_ARGS0, STR_ARGS1, STR_ARGS2, STR_ARGS3, STR_ARGS4, STR_ARGS5, STR_ARGS6, STR_ARGS7,
            STR_ARGS8, STR_ARGS9, STR_ARGS10, STR_ARGS11, STR_ARGS12, STR_ARGS13, STR_ARGS14, STR_ARGS15,
        };
        for (int i = 0; i < cases.length; i++) {
            assertThat(ConversationSerializer.renderToolArgs(cases[i]))
                    .as("args%d", i)
                    .isEqualTo(expected[i]);
        }
    }

    @Test
    void serializeToolCallsMatchesGo() {
        List<ToolCall> calls = List.of(
                call("c1", "write_sandbox_file", "{\"path\":\"/w/a.html\",\"content\":\"body\"}"),
                call("c2", "knowledge_search", "{\"query\":\"x\"}"));
        assertThat(ConversationSerializer.serializeToolCalls(calls)).isEqualTo(STR_CALLS2);
        assertThat(ConversationSerializer.serializeToolCalls(List.of())).isEqualTo("");
        assertThat(ConversationSerializer.serializeToolCalls(
                List.of(call("c3", "broken", "{\"unclosed")))).isEqualTo(STR_CALLS_BAD);
    }

    @Test
    void goTrimSpaceMatchesUnicodeSpaceSemantics() {
        assertThat(ConversationSerializer.goTrimSpace("  x  ")).isEqualTo("x");
        assertThat(ConversationSerializer.goTrimSpace("\u00A0x")).isEqualTo("x");
        assertThat(ConversationSerializer.goTrimSpace("x\u2028")).isEqualTo("x");
        assertThat(ConversationSerializer.goTrimSpace("")).isEmpty();
        assertThat(ConversationSerializer.goTrimSpace(null)).isEmpty();
        // 该辅助被 AgentPrompts.formatDocSummary 复用，实录见 AgentPromptsTest
        assertThat(GoRecording.STR_DSUM5).isEqualTo("spaced out text");
    }
}
