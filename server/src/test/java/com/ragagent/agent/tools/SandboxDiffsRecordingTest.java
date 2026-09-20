package com.ragagent.agent.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sandbox_diff / output_snapshot 的 Go 实录回放（/tmp/toolrec45c 探针 → GoRecording45C）。
 * 探针只录输出值的用例，输入按探针源码原样重建（纯函数、确定性）。
 */
class SandboxDiffsRecordingTest {

    private static JsonNode rec(String constant) {
        return GoRecording45C.rec(constant);
    }

    @Test
    void countContentLinesMatchesGo() {
        JsonNode cases = rec(GoRecording45C.R_SANDBOX_DIFF_COUNT_LINES).get("cases");
        for (JsonNode c : cases) {
            int out = SandboxDiffs.countContentLines(c.get("in").asText());
            assertThat(out).as("in=%s", c.get("in").asText()).isEqualTo(c.get("out").asInt());
        }
    }

    @Test
    void contentPreviewMatchesGo() {
        JsonNode cases = rec(GoRecording45C.R_SANDBOX_DIFF_CONTENT_PREVIEW).get("cases");
        for (JsonNode c : cases) {
            String out = SandboxDiffs.sandboxContentPreview(c.get("in").asText());
            assertThat(out).as("in=%s", c.get("in").asText()).isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void writeCallProgressMatchesGo() throws Exception {
        String[][] cases = {
                {"plain", "{\"path\":\"/workspace/output/x.py\",\"content\":\"print(1)\\nprint(2)\"}"},
                {"mode", "{\"path\":\"b.txt\",\"mode\":\"append\",\"content\":\"hello\"}"},
                {"cjk", "{\"path\":\"/workspace/output/报.txt\",\"content\":\"第一行\\n第二行\\n\"}"},
                {"long_preview", "{\"path\":\"/workspace/output/l.txt\",\"content\":\"1\\n2\\n3\\n4\\n5\\n6\\n7\\n8\\n9\\n10\\n11\\n12\\n\"}"},
                {"file_path_key", "{\"file_path\":\"/workspace/alias.txt\",\"content\":\"x\"}"},
                {"no_path", "{\"content\":\"x\"}"},
                {"empty", "{}"},
        };
        for (String[] c : cases) {
            JsonNode r = rec((String) GoRecording45C.class.getField(
                    "R_SANDBOX_DIFF_PROGRESS_" + c[0].toUpperCase().replace('-', '_')).get(null));
            Map<String, Object> args = RecordingSupport.PLAIN.convertValue(
                    RecordingSupport.readTree(c[1]), Map.class);
            Map<String, Object> out = SandboxDiffs.sandboxFileCallProgress(
                    ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, args);
            assertThat(RecordingSupport.goJsonOfData(out))
                    .as("case %s", c[0])
                    .isEqualTo(r.get("out_json").asText());
        }
    }

    @Test
    void editCallProgressMatchesGo() throws Exception {
        String[][] cases = {
                {"edits", "{\"path\":\"/workspace/f.txt\",\"edits\":[{\"old_string\":\"a\\nb\",\"new_string\":\"x\"},{\"old_string\":\"c\",\"new_string\":\"y\\nz\"}]}"},
                {"flat", "{\"path\":\"/workspace/f.txt\",\"old_string\":\"a\\nb\\nc\",\"new_string\":\"z\"}"},
                {"mixed_keys", "{\"oldText\":\"a\",\"newText\":\"b\\nc\"}"},
                {"non_string", "{\"edits\":[{\"old_string\":1,\"new_string\":true},\"junk\"]}"},
                {"zeros", "{\"edits\":[{\"old_string\":\"k\",\"new_string\":\"k\"}]}"},
        };
        for (String[] c : cases) {
            JsonNode r = rec((String) GoRecording45C.class.getField(
                    "R_SANDBOX_DIFF_PROGRESS_EDIT_" + c[0].toUpperCase().replace('-', '_')).get(null));
            Map<String, Object> args = RecordingSupport.PLAIN.convertValue(
                    RecordingSupport.readTree(c[1]), Map.class);
            Map<String, Object> out = SandboxDiffs.sandboxFileCallProgress(
                    ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, args);
            assertThat(RecordingSupport.goJsonOfData(out))
                    .as("case %s", c[0])
                    .isEqualTo(r.get("out_json").asText());
        }
    }

    @Test
    void sanitizeMatchesGo() throws Exception {
        // content：write 工具带 content → 进度形态
        Map<String, Object> out = SandboxDiffs.sanitizeSandboxFileCallArgs(
                ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, Map.of("path", "p", "content", "BODY"));
        assertThat(RecordingSupport.goJsonOfData(out))
                .isEqualTo(rec(GoRecording45C.R_SANDBOX_DIFF_SANITIZE_CONTENT).get("out_json").asText());
        // edits：edit 工具带 edits → 进度形态
        Map<String, Object> outEdits = SandboxDiffs.sanitizeSandboxFileCallArgs(
                ToolDefinitions.TOOL_EDIT_SANDBOX_FILE,
                Map.of("path", "p", "edits", List.of(Map.of("old_string", "o", "new_string", "n"))));
        assertThat(RecordingSupport.goJsonOfData(outEdits))
                .isEqualTo(rec(GoRecording45C.R_SANDBOX_DIFF_SANITIZE_EDITS).get("out_json").asText());
        // 非 mutation 工具：原样返回同一引用
        Map<String, Object> raw = Map.of("content", "BODY");
        assertThat(SandboxDiffs.sanitizeSandboxFileCallArgs("shell_exec", raw)).isSameAs(raw);
        // mutation 工具但无正文：原样返回
        Map<String, Object> rawPathOnly = Map.of("path", "p");
        assertThat(SandboxDiffs.sanitizeSandboxFileCallArgs(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, rawPathOnly))
                .isSameAs(rawPathOnly);
        // nil args → null
        assertThat(SandboxDiffs.sanitizeSandboxFileCallArgs(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, null)).isNull();
    }

    @Test
    void formatStatMatchesGo() {
        JsonNode cases = rec(GoRecording45C.R_SANDBOX_DIFF_FORMAT_STAT).get("cases");
        for (JsonNode c : cases) {
            String out = SandboxDiffs.formatSandboxDiffStat(c.get("added").asInt(), c.get("removed").asInt());
            assertThat(out).isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void editDiffStatsMatchesGo() {
        // 输入按探针源码重建；期望值是 Go 实录。
        List<SandboxEdits.SandboxEdit> single = List.of(new SandboxEdits.SandboxEdit("a\nb", "x", false));
        long[] s1 = SandboxDiffs.sandboxEditDiffStats("a\nb\nc", single);
        assertThat(new int[] {(int) s1[0], (int) s1[1]}).isEqualTo(new int[] {
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_SINGLE).get("added").asInt(),
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_SINGLE).get("removed").asInt()});

        List<SandboxEdits.SandboxEdit> replaceAll = List.of(new SandboxEdits.SandboxEdit("aa", "bbb\nccc", true));
        long[] s2 = SandboxDiffs.sandboxEditDiffStats("aa\naa", replaceAll);
        assertThat(new int[] {(int) s2[0], (int) s2[1]}).isEqualTo(new int[] {
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_REPLACE_ALL).get("added").asInt(),
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_REPLACE_ALL).get("removed").asInt()});

        List<SandboxEdits.SandboxEdit> noMatch = List.of(new SandboxEdits.SandboxEdit("zz", "y", false));
        long[] s3 = SandboxDiffs.sandboxEditDiffStats("abc", noMatch);
        assertThat(new int[] {(int) s3[0], (int) s3[1]}).isEqualTo(new int[] {
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_NO_MATCH).get("added").asInt(),
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_NO_MATCH).get("removed").asInt()});

        List<SandboxEdits.SandboxEdit> multi = List.of(
                new SandboxEdits.SandboxEdit("1", "one", false),
                new SandboxEdits.SandboxEdit("4\n", "", true));
        long[] s4 = SandboxDiffs.sandboxEditDiffStats("1\n2\n3\n4", multi);
        assertThat(new int[] {(int) s4[0], (int) s4[1]}).isEqualTo(new int[] {
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_MULTI).get("added").asInt(),
                rec(GoRecording45C.R_SANDBOX_DIFF_EDIT_STATS_MULTI).get("removed").asInt()});
    }

    @Test
    void outputSnapshotChangedLinksMatchGo() {
        JsonNode r = rec(GoRecording45C.R_OUTPUT_SNAPSHOT_CHANGED);
        Tools45cFakes.RecExecutor exec = new Tools45cFakes.RecExecutor();
        exec.listed = new java.util.ArrayList<>(List.of(
                new OutputLinks.DirEntry("/workspace/output/new.txt", true, 5,
                        SandboxDiffsRecordingTest.instant(2026, 9, 20, 9, 0, 0)),
                new OutputLinks.DirEntry("/workspace/output/same.txt", true, 5,
                        SandboxDiffsRecordingTest.instant(2026, 9, 20, 9, 0, 0)),
                new OutputLinks.DirEntry("/workspace/output/changed.txt", true, 9,
                        SandboxDiffsRecordingTest.instant(2026, 9, 20, 9, 0, 0)),
                new OutputLinks.DirEntry("/workspace/output/dir", false, 0,
                        SandboxDiffsRecordingTest.instant(2026, 9, 20, 9, 0, 0))));
        Map<String, OutputLinks.DirEntry> before = SandboxDiffs.sandboxOutputSnapshot(exec, "zz45c-session");
        assertThat(before).isNotNull();
        before.remove("/workspace/output/new.txt");
        Map<String, OutputLinks.DirEntry> after = SandboxDiffs.sandboxOutputSnapshot(exec, "zz45c-session");
        List<String> links = OutputLinks.changedOutputLinks(before, after);
        assertThat(links).containsExactlyElementsOf(
                RecordingSupport.PLAIN.convertValue(r.get("links"), List.class));
        assertThat(OutputLinks.sandboxOutputLinks("/workspace/output/new.txt", "elsewhere.txt",
                "/workspace/output/changed.txt"))
                .containsExactlyElementsOf(RecordingSupport.PLAIN.convertValue(r.get("direct"), List.class));
    }

    static java.time.Instant instant(int y, int m, int d, int h, int mi, int s) {
        return java.time.OffsetDateTime.of(y, m, d, h, mi, s, 0, java.time.ZoneOffset.UTC).toInstant();
    }
}
