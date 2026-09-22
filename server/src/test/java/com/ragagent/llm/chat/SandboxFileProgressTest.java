package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.domain.ResponseType;
import com.ragagent.llm.domain.StreamResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 对照 Go internal/models/chat/sandbox_file_progress_test.go 全文（6 个用例逐条移植），
 * 外加一条接线点回归（{@link #progressRidesToolCallEvents}，走
 * {@link RemoteApiChat#processStreamDelta} 的 stub 场景，与 Go openai_stream.go 的
 * processToolCallsDelta 布线同构）。
 *
 * <p>关键值实录（独立推演自 Go 语义 + Go 测试实跑 PASS）：</p>
 * <ul>
 *   <li>write 第三次 feed 的 {@code bytes} = 17（{@code line1\nline2\nline3} 反转义后 5+1+5+1+5）；</li>
 *   <li>edit 完整 JSON 一条 feed 的 {@code bytes} = 88（原始 arguments 的 UTF-8 字节长，
 *       {@code printf '%s' '{"path":...}' | wc -c} 实测）；</li>
 *   <li>edit 半截 JSON 的 {@code bytes} = 82（同法）；闭合后 added=3 / removed=1
 *       （old="foo" 1 行 / new="foo\nbar\nbaz" 3 行）。</li>
 * </ul>
 */
class SandboxFileProgressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 对照 Go TestCountContentLines 的表。 */
    static Stream<Arguments> contentLinesTable() {
        return Stream.of(
                Arguments.of("", 0),
                Arguments.of("hello", 1),
                Arguments.of("hello\n", 1),
                Arguments.of("hello\nworld", 2),
                Arguments.of("hello\nworld\n", 2),
                Arguments.of("a\n\nb", 3));
    }

    @ParameterizedTest(name = "[{index}] countContentLines(\"{0}\") = {1}")
    @MethodSource("contentLinesTable")
    void countContentLinesTable(String in, int want) {
        assertEquals(want, SandboxFileProgress.countContentLines(in));
    }

    /** 对照 Go TestSandboxFileProgressWriteStreamsLineCount。 */
    @Test
    void writeStreamsLineCount() {
        SandboxFileProgress p = new SandboxFileProgress(SandboxFileProgress.WRITE_SANDBOX_FILE);

        // Go 的反引号串：\n 在这里是字面两个字符（JSON 转义形式）
        assertNull(p.feed("{\"path\":\""), "path still open, should not emit yet");

        Map<String, Object> payload = p.feed("/workspace/output/deck.py\",\"content\":\"");
        assertNotNull(payload, "expected emit once path is complete");
        assertEquals("/workspace/output/deck.py", payload.get("path"));
        assertEquals(0, payload.get("added_lines"), "no content yet, added_lines = 0（Go：零值也发）");
        assertEquals(0, payload.get("removed_lines"));
        assertEquals(0, payload.get("bytes"));
        assertFalse(payload.containsKey("preview"), "空预览整键省略");

        payload = p.feed("line1\\nline2\\nline3");
        assertNotNull(payload, "expected emit when line count grows");
        assertEquals(3, payload.get("added_lines"));
        assertEquals(17, payload.get("bytes"), "bytes = 反转义后 content 的 UTF-8 字节长");
        String preview = (String) payload.get("preview");
        assertTrue(preview.contains("line1") && preview.contains("line3"), "preview = " + preview);
        assertFalse(payload.containsKey("content"), "progress payload must not carry the file body");
    }

    /** 对照 Go TestSandboxFileProgressWritePreviewCapsAtTenLines。 */
    @Test
    void writePreviewCapsAtTenLines() {
        SandboxFileProgress p = new SandboxFileProgress(SandboxFileProgress.WRITE_SANDBOX_FILE);
        StringBuilder body = new StringBuilder("{\"path\":\"/workspace/a.py\",\"content\":\"");
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                body.append("\\n");
            }
            body.append("x");
        }
        body.append("\"}");

        Map<String, Object> payload = p.feed(body.toString());
        assertNotNull(payload, "expected emit");
        assertEquals(15, payload.get("added_lines"));
        String preview = (String) payload.get("preview");
        assertEquals(10, preview.split("\n", -1).length, // 10 = sandboxFilePreviewMaxLines
                "preview lines = " + preview);
    }

    /** 对照 Go TestSandboxFileProgressEditCountsOldAndNew。 */
    @Test
    void editCountsOldAndNew() {
        SandboxFileProgress p = new SandboxFileProgress(SandboxFileProgress.EDIT_SANDBOX_FILE);
        Map<String, Object> payload = p.feed(
                "{\"path\":\"/workspace/a.py\",\"edits\":[{\"old_string\":\"a\\nb\\n\",\"new_string\":\"a\\nb\\nc\\nd\\n\"}]}");
        assertNotNull(payload, "expected emit for complete edit JSON");
        assertEquals("/workspace/a.py", payload.get("path"));
        assertEquals(2, payload.get("removed_lines"));
        assertEquals(4, payload.get("added_lines"));
        assertEquals(88, payload.get("bytes"), "edit 的 bytes = 原始 arguments 缓冲的 UTF-8 字节长（实录 88）");
        assertFalse(payload.containsKey("preview"), "edit 不出预览");
    }

    /** 对照 Go TestSandboxFileProgressEditPartialJSON（闭合法修复半截 JSON）。 */
    @Test
    void editPartialJson() {
        SandboxFileProgress p = new SandboxFileProgress(SandboxFileProgress.EDIT_SANDBOX_FILE);
        Duration original = SandboxFileProgress.sandboxProgressMinInterval;
        SandboxFileProgress.sandboxProgressMinInterval = Duration.ZERO; // 对照 Go：包级 var 置 0
        try {
            Map<String, Object> payload = p.feed(
                    "{\"path\":\"/workspace/a.py\",\"edits\":[{\"old_string\":\"foo\",\"new_string\":\"foo\\nbar\\nbaz");
            assertNotNull(payload, "expected emit for closable partial JSON");
            assertEquals("/workspace/a.py", payload.get("path"));
            // Go 只断言 added >= 2；确切值按闭合后的 JSON 实录钉死：
            // old="foo" → removed=1，new="foo\nbar\nbaz" → added=3，缓冲 82 字节
            assertEquals(3, payload.get("added_lines"));
            assertEquals(1, payload.get("removed_lines"));
            assertEquals(82, payload.get("bytes"));
        } finally {
            SandboxFileProgress.sandboxProgressMinInterval = original; // 对照 Go 的 t.Cleanup
        }
    }

    /** 对照 Go TestClosePartialJSON。 */
    @Test
    void closePartialJson() throws Exception {
        String closed = SandboxFileProgress.closePartialJson("{\"path\":\"/x\",\"content\":\"hello");
        // 实录：闭合序列 = 补 `"` + 补 `}`（栈里只有一个 `}`）
        assertEquals("{\"path\":\"/x\",\"content\":\"hello\"}", closed);

        JsonNode m = MAPPER.readTree(closed);
        assertEquals("hello", m.get("content").asText(), "closed JSON should parse");
    }

    // ------------------------------------------------------------------
    // 接线点回归：RemoteApiChat.processToolCallsDelta 的沙箱进度布线（stub 场景）
    // ------------------------------------------------------------------

    /** 对照 Go newTestRemoteChat：无 provider、无 baseURL 的默认实例。 */
    private static RemoteApiChat newTestRemoteChat() {
        ChatConfig config = new ChatConfig();
        config.setSource("remote");
        config.setModelName("test-model");
        config.setApiKey("test-key");
        config.setModelId("test-model");
        return new RemoteApiChat(config);
    }

    /**
     * write_sandbox_file 的三条增量走 {@code processStreamDelta}：
     * 标记事件不提前、进度搭首发的 tool_call 标记、后续进度独立成事件——
     * 事件形态逐字段对照 Go（data 键：arguments / tool_call_id / tool_name）。
     */
    @Test
    void progressRidesToolCallEvents() throws Exception {
        RemoteApiChat chat = newTestRemoteChat();
        OpenAiStreamState state = new OpenAiStreamState();
        BlockingQueue<StreamResponse> ch = new LinkedBlockingQueue<>();

        // delta 1：名字首现 + id + arguments → 名字未稳定，不发标记；进度也无物可发
        // （arguments 与 Go 测试同界：以 path 值的开引号收尾，引号不齐字段抽取不出值）
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",
                 "function":{"name":"write_sandbox_file","arguments":"{\\"path\\":\\""}}]},
                 "finish_reason":""}"""), state, ch, "");
        assertTrue(ch.isEmpty(), "名字未稳定且无进度 → 无事件");

        // delta 2：名字稳定 + 进度命中（path 抽取完成）→ 首个 tool_call 标记搭车 arguments
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"write_sandbox_file",
                 "arguments":"/w/a.py\\",\\"content\\":\\""}}]},"finish_reason":""}"""), state, ch, "");
        assertEquals(1, ch.size());
        StreamResponse marker = ch.poll();
        assertEquals(ResponseType.TOOL_CALL, marker.getResponseType());
        assertFalse(marker.isDone());
        assertEquals("write_sandbox_file", marker.getData().get("tool_name"));
        assertEquals("call_1", marker.getData().get("tool_call_id"));
        assertEquals(
                Map.of("added_lines", 0, "removed_lines", 0, "bytes", 0, "path", "/w/a.py"),
                marker.getData().get("arguments"));

        // delta 3：行数增长 → 进度独立成 tool_call 事件（名字标记不重发）
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"line1\\\\nline2\\\\nline3"}}]},
                 "finish_reason":""}"""), state, ch, "");
        assertEquals(1, ch.size());
        StreamResponse progress = ch.poll();
        assertEquals(ResponseType.TOOL_CALL, progress.getResponseType());
        assertEquals("write_sandbox_file", progress.getData().get("tool_name"));
        assertEquals("call_1", progress.getData().get("tool_call_id"));
        assertEquals(
                Map.of("added_lines", 3, "removed_lines", 0, "bytes", 17,
                        "path", "/w/a.py", "preview", "line1\nline2\nline3"),
                progress.getData().get("arguments"));

        // 每条 arguments 不得携带文件正文
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) progress.getData().get("arguments");
        assertFalse(args.containsKey("content"));

        // 非 write/edit 工具不建进度器
        chat.processStreamDelta(MAPPER.readTree("""
                {"index":1,"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function",
                 "function":{"name":"wiki_search","arguments":"{\\"query\\":\\"x\\"}"}}]},
                 "finish_reason":""}"""), state, ch, "");
        assertNull(state.fileProgress.get(1), "非沙箱工具不得挂进度器");
        assertTrue(state.fileProgress.containsKey(0), "write_sandbox_file 已挂进度器");
    }
}
