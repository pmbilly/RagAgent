package com.ragagent.agent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * list_sandbox_files 的 Go 实录回放（roots 判定、路径错误、mod time、Execute 全路径）。
 */
class SandboxLsRecordingTest {

    @Test
    void schemaAndDescriptionMatchGo() {
        ListSandboxFilesTool tool = new ListSandboxFilesTool(new Tools45cFakes.MemStore());
        assertThat(tool.getDescription())
                .isEqualTo(Tools45cFakes.rec45c("sandbox_ls", "description").get("out").asText());
        // schema 是 map marshal（键字母序）：键序无关规范化比较
        assertThat(RecordingSupport.canonicalJson(tool.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("sandbox_ls", "schema").get("out").asText())));
    }

    @Test
    void matchingInspectableRootMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_ls", "matching_root").get("cases");
        for (JsonNode c : cases) {
            String[] root = ListSandboxFilesTool.matchingInspectableRoot(c.get("in").asText());
            boolean ok = root[1] != null;
            assertThat(ok).as("ok %s", c.get("in").asText()).isEqualTo(c.get("ok").asBoolean());
            assertThat(root[0]).isEqualTo(c.get("root").asText());
        }
    }

    @Test
    void inspectablePathErrorsMatchGo() {
        JsonNode r = Tools45cFakes.rec45c("sandbox_ls", "path_errors");
        assertThat(ListSandboxFilesTool.inspectablePathError("/opt/weknora/tenant/skills/pdf/SKILL.md"))
                .isEqualTo(r.get("skill_image").asText());
        assertThat(ListSandboxFilesTool.inspectablePathError("/etc/passwd"))
                .isEqualTo(r.get("plain").asText());
        assertThat(ListSandboxFilesTool.inspectablePathError("/workspacefoo"))
                .isEqualTo(r.get("workspace_").asText());
    }

    @Test
    void relativeSkillFileFromImagePathMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_ls", "rel_from_image").get("cases");
        for (JsonNode c : cases) {
            assertThat(ListSandboxFilesTool.relativeSkillFileFromImagePath(
                    c.get("clean").asText(), c.get("skill").asText()))
                    .as("clean=%s", c.get("clean").asText())
                    .isEqualTo(c.get("rel").asText());
        }
    }

    @Test
    void formatSandboxModTimeMatchesGo() {
        JsonNode r = Tools45cFakes.rec45c("sandbox_ls", "mod_time");
        assertThat(ListSandboxFilesTool.formatSandboxModTime(null)).isEqualTo(r.get("zero").asText());
        java.time.Instant utc = java.time.OffsetDateTime.parse("2026-09-20T08:30:05Z").toInstant();
        assertThat(ListSandboxFilesTool.formatSandboxModTime(utc)).isEqualTo(r.get("utc").asText());
        java.time.Instant tz = java.time.OffsetDateTime.parse("2026-09-20T16:30:05+08:00").toInstant();
        assertThat(ListSandboxFilesTool.formatSandboxModTime(tz)).isEqualTo(r.get("tz").asText());
        java.time.Instant frac = java.time.OffsetDateTime.parse("2026-09-20T08:30:05.123456789Z").toInstant();
        assertThat(ListSandboxFilesTool.formatSandboxModTime(frac)).isEqualTo(r.get("frac").asText());
    }

    @Test
    void executePathsMatchGo() {
        String[] ids = {"exec_files", "exec_empty", "exec_explicit_path", "exec_outside",
                "exec_list_err", "exec_truncated", "exec_no_session"};
        for (String id : ids) {
            JsonNode r = Tools45cFakes.rec45c("sandbox_ls", id);
            ToolResult result = runScenario(id);
            assertThat(result.isSuccess()).as("success %s", id).isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            assertThat(result.getError() == null ? "" : result.getError()).as("error %s", id)
                    .isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()
                    && result.getData() != null) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id)
                        .isEqualTo(dataJson.asText());
            }
        }
    }

    private static final java.time.Instant T1 =
            java.time.OffsetDateTime.parse("2026-09-20T08:30:05Z").toInstant();
    private static final java.time.Instant T2 =
            java.time.OffsetDateTime.parse("2026-09-19T08:00:00Z").toInstant();

    private ToolResult runScenario(String id) {
        Tools45cFakes.MemStore source = new Tools45cFakes.MemStore();
        switch (id) {
            case "exec_files" -> source.entries = new java.util.ArrayList<>(List.of(
                    new RemoteDirEntry("b.txt", "/workspace/output/b.txt", RemoteDirEntry.TYPE_FILE, 12, T1),
                    new RemoteDirEntry("a.txt", "/workspace/output/a.txt", RemoteDirEntry.TYPE_FILE, 3, T2)));
            case "exec_explicit_path" -> source.entries = new java.util.ArrayList<>(List.of(
                    new RemoteDirEntry("x", "/workspace/input/x", RemoteDirEntry.TYPE_FILE, 1, null)));
            case "exec_list_err" -> source.listErr = Tools45cFakes.boom("dial failed");
            case "exec_truncated" -> source.entries = new java.util.ArrayList<>(List.of(
                    new RemoteDirEntry("f1", "/workspace/output/f1", RemoteDirEntry.TYPE_FILE, 1, null),
                    new RemoteDirEntry("f2", "/workspace/output/f2", RemoteDirEntry.TYPE_FILE, 2, null)));
            default -> {
                // exec_empty / exec_outside / exec_no_session：空目录
            }
        }
        ListSandboxFilesTool tool = new ListSandboxFilesTool(source);
        String args = switch (id) {
            case "exec_explicit_path" -> "{\"path\":\"/workspace/input\"}";
            case "exec_outside" -> "{\"path\":\"/etc\"}";
            case "exec_truncated" -> "{\"max_entries\":1}";
            default -> "{}";
        };
        ToolExecContext meta = "exec_no_session".equals(id) ? null
                : new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0);
        return tool.execute(new ToolRequest(RecordingSupport.readTree(args), meta, ToolCancellation.LIVE, 0));
    }
}
