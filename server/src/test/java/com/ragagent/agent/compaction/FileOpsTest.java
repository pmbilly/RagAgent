package com.ragagent.agent.compaction;

import static com.ragagent.agent.GoRecording.STR_RESOLVE1_FORMAT;
import static com.ragagent.agent.GoRecording.STR_RESOLVE2_FORMAT;
import static com.ragagent.agent.GoRecording.STR_RESOLVE3_FORMAT;
import static com.ragagent.agent.GoRecording.STR_RESOLVE4_FORMAT;
import static com.ragagent.agent.GoRecording.STR_RESOLVE5_FORMAT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ToolCall;

/**
 * fileops 的录制常量断言（场景 =
 * 五个标准用例 + 上限/裁剪/解析往返）。
 */
class FileOpsTest {

    private static ChatMessage toolCallMsg(String name, String arguments) {
        ToolCall tc = new ToolCall();
        tc.setId("call-1");
        tc.setFunction(new com.ragagent.llm.domain.FunctionCall(name, arguments));
        ChatMessage m = new ChatMessage("assistant", "");
        m.setToolCalls(List.of(tc));
        return m;
    }

    @Test
    void resolveSplitsReadsFromWrites() {
        FileOps ops = FileOps.extractFileOps("", List.of(List.of(
                toolCallMsg("write_sandbox_file", "{\"path\":\"/workspace/output/deck.html\",\"content\":\"<html>\"}"),
                toolCallMsg("edit_sandbox_file", "{\"path\":\"/workspace/output/deck.html\",\"old_string\":\"a\"}"),
                toolCallMsg("read_file", "{\"path\":\"/workspace/input/notes.txt\"}"),
                toolCallMsg("read_file", "{\"path\":\"skill://pdf/SKILL.md\"}"),
                toolCallMsg("shell_exec", "{\"command\":\"ls\"}"))));
        FileOps.Resolved r = ops.resolve();
        // 写过又编辑过 = 一条，不是两条
        assertThat(r.modified()).containsExactly("/workspace/output/deck.html");
        assertThat(r.read()).containsExactly("/workspace/input/notes.txt", "skill://pdf/SKILL.md");
        assertThat(ops.format()).isEqualTo(STR_RESOLVE1_FORMAT);
    }

    @Test
    void resolveDropsReadsThatWereAlsoWritten() {
        FileOps ops = FileOps.extractFileOps("", List.of(List.of(
                toolCallMsg("read_sandbox_file", "{\"path\":\"/workspace/output/deck.html\"}"),
                toolCallMsg("write_sandbox_file", "{\"path\":\"/workspace/output/deck.html\",\"content\":\"x\"}"))));
        FileOps.Resolved r = ops.resolve();
        assertThat(r.modified()).containsExactly("/workspace/output/deck.html");
        assertThat(r.read()).isEmpty();
        assertThat(ops.format()).isEqualTo(STR_RESOLVE2_FORMAT);
    }

    @Test
    void ignoresUnparseableArguments() {
        // 截断参数是本功能要存续的场景的常态：跳过而不是污染列表
        FileOps ops = FileOps.extractFileOps("", List.of(List.of(
                toolCallMsg("write_sandbox_file", "{\"path\":\"/workspace/output/a.html\",\"content\":\"<htm"),
                toolCallMsg("write_sandbox_file", "{\"content\":\"no path here\"}"))));
        FileOps.Resolved r = ops.resolve();
        assertThat(r.modified()).isEmpty();
        assertThat(r.read()).isEmpty();
        assertThat(ops.format()).isEqualTo(STR_RESOLVE3_FORMAT);
    }

    @Test
    void inheritsFromPreviousSummary() {
        // Go 测试直接构造 fileOps{written:..., read:...}；Java 用等价的 extractFileOps
        // 攒出同样的三套集合（write 一次 + read 一次）
        FileOps earlier = FileOps.extractFileOps("", List.of(List.of(
                toolCallMsg("write_sandbox_file", "{\"path\":\"/workspace/output/deck.html\"}"),
                toolCallMsg("read_file", "{\"path\":\"/workspace/input/notes.txt\"}"))));

        FileOps ops = FileOps.extractFileOps("prose" + earlier.format(), List.of(List.of(
                toolCallMsg("write_sandbox_file", "{\"path\":\"/workspace/output/report.md\",\"content\":\"x\"}"))));
        FileOps.Resolved r = ops.resolve();
        // 没有继承，第二次压缩会忘掉第一次记的——这正是要防的失败模式
        assertThat(r.modified()).containsExactly("/workspace/output/deck.html", "/workspace/output/report.md");
        assertThat(r.read()).containsExactly("/workspace/input/notes.txt");
        assertThat(ops.format()).isEqualTo(STR_RESOLVE4_FORMAT);
    }

    @Test
    void formatIsEmptyWhenNothingTouched() {
        assertThat(new FileOps().format()).isEmpty();
        assertThat(STR_RESOLVE3_FORMAT).isEmpty();
    }

    @Test
    void capsTrackedPaths() {
        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(toolCallMsg("write_sandbox_file",
                    "{\"path\":\"/workspace/output/" + "a".repeat(i + 1) + ".txt\"}"));
        }
        FileOps.Resolved r = FileOps.extractFileOps("", List.of(msgs)).resolve();
        assertThat(r.modified()).hasSize(50);
        assertThat(r.modified().get(0)).isEqualTo("/workspace/output/a.txt");
        assertThat(r.modified().get(49)).isEqualTo("/workspace/output/" + "a".repeat(50) + ".txt");
    }

    @Test
    void trimsPathsAndSkipsBlank() {
        FileOps ops = FileOps.extractFileOps("", List.of(List.of(
                toolCallMsg("write_sandbox_file", "{\"path\":\"  /workspace/trim.txt  \"}"),
                toolCallMsg("read_file", "{\"path\":\"   \"}"))));
        assertThat(ops.format()).isEqualTo(STR_RESOLVE5_FORMAT);
    }

    @Test
    void parseTaggedRoundTrip() {
        String block = "\n\n<read-files>\n/a.txt\n/b.txt\n/a.txt\n</read-files>"
                + "\n\n<modified-files>\n/c.txt\n</modified-files>";
        var parsed = FileOps.parseFileOpsBlock(block);
        assertThat(parsed.read()).containsExactly("/a.txt", "/b.txt");
        assertThat(parsed.modified()).containsExactly("/c.txt");
        // 未闭合的 modified 块读不到
        var unclosed = FileOps.parseFileOpsBlock("<modified-files>\n/d.txt");
        assertThat(unclosed.modified()).isNull();
        assertThat(unclosed.read()).isNull();
    }
}
