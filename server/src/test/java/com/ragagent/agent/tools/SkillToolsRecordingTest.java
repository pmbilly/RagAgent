package com.ragagent.agent.tools;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skill_resources / skill_runtime_guard / skill_file 的 Go 实录回放。
 */
class SkillToolsRecordingTest {

    @Test
    void skillFileTreeMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("skill_resources", "tree").get("cases");
        for (JsonNode c : cases) {
            List<String> files = new java.util.ArrayList<>();
            c.get("in").forEach(n -> files.add(n.asText()));
            assertThat(SkillResources.formatSkillFileTree(files))
                    .as("in=%s", files)
                    .isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void skillGuardMatchesGo() {
        JsonNode g = Tools45cFakes.rec45c("skill_guard", "missing_guidance");
        assertThat(SkillRuntimeGuard.missingSkillPackageGuidance("")).isEqualTo(g.get("empty").asText());
        assertThat(SkillRuntimeGuard.missingSkillPackageGuidance("pdf")).isEqualTo(g.get("named").asText());

        JsonNode kinds = Tools45cFakes.rec45c("skill_guard", "failure_kinds").get("cases");
        for (JsonNode c : kinds) {
            String in = c.get("in").asText();
            assertThat(SkillRuntimeGuard.isSkillVenvInstallFailure(in)).as("venv %s", in)
                    .isEqualTo(c.get("venv_failure").asBoolean());
            assertThat(SkillRuntimeGuard.isReadOnlyFilesystemFailure(in)).as("ro %s", in)
                    .isEqualTo(c.get("readonly").asBoolean());
            assertThat(SkillRuntimeGuard.isPermissionFailure(in)).as("perm %s", in)
                    .isEqualTo(c.get("permission").asBoolean());
        }

        JsonNode vg = Tools45cFakes.rec45c("skill_guard", "venv_guidance");
        assertThat(SkillRuntimeGuard.skillVenvFailureGuidance("pdf", "Read-only file system at .venv"))
                .isEqualTo(vg.get("readonly").asText());
        assertThat(SkillRuntimeGuard.skillVenvFailureGuidance("pdf", ".venv: permission denied"))
                .isEqualTo(vg.get("permission").asText());
        assertThat(SkillRuntimeGuard.skillVenvFailureGuidance("", "No module named pip"))
                .isEqualTo(vg.get("other").asText());
        assertThat(SkillRuntimeGuard.skillVenvFailureGuidance("pdf tools", "No module named pip"))
                .isEqualTo(vg.get("other_named").asText());
    }

    @Test
    void skillFileSchemasAndDescriptionsMatchGo() {
        String skillDir = "/opt/weknora/tenant/skills/pdf";
        WriteSkillFileTool w = new WriteSkillFileTool(null, skillDir);
        EditSkillFileTool e = new EditSkillFileTool(null, skillDir);
        assertThat(w.getDescription()).isEqualTo(Tools45cFakes.rec45c("skill_file", "write_description").get("out").asText());
        assertThat(e.getDescription()).isEqualTo(Tools45cFakes.rec45c("skill_file", "edit_description").get("out").asText());
        assertThat(RecordingSupport.canonicalJson(w.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("skill_file", "write_schema").get("out").asText())));
        assertThat(RecordingSupport.canonicalJson(e.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("skill_file", "edit_schema").get("out").asText())));
    }

    @Test
    void resolveSkillFilePathMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("skill_file", "resolve_path").get("cases");
        for (JsonNode c : cases) {
            String err = "";
            String clean = "";
            try {
                clean = SkillFiles.resolveSkillFilePath(c.get("dir").asText(), c.get("requested").asText());
            } catch (IllegalArgumentException ex) {
                err = ex.getMessage();
            }
            assertThat(clean).as("clean %s/%s", c.get("dir").asText(), c.get("requested").asText())
                    .isEqualTo(c.get("clean").asText());
            assertThat(err).as("err %s/%s", c.get("dir").asText(), c.get("requested").asText())
                    .isEqualTo(c.get("err").asText());
        }
    }

    @Test
    void skillFileExecutePathsMatchGo() {
        String skillDir = "/opt/weknora/tenant/skills/pdf";
        String[][] cases = {
                {"exec_ok", "{}"},
                {"exec_empty_path_arg", "{}"},
                {"exec_binary", "{}"},
                {"exec_write_fails", "{}"},
                {"exec_syntax_hint", "{}"},
                {"exec_outside", "{}"},
                {"exec_edit_ok", "{}"},
                {"exec_edit_is_dir", "{}"},
                {"exec_edit_stat_fails", "{}"},
                {"exec_edit_read_fails", "{}"},
                {"exec_edit_binary_file", "{}"},
                {"exec_edit_not_found", "{}"},
                {"exec_edit_replace_all", "{}"},
                {"exec_edit_no_session", "{}"},
        };
        for (String[] c : cases) {
            String id = c[0];
            JsonNode r = Tools45cFakes.rec45c("skill_file", id);
            ToolResult result = runScenario(id, skillDir);
            assertThat(result.isSuccess()).as("success %s", id).isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            assertThat(result.getError() == null ? "" : result.getError()).as("error %s", id)
                    .isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()
                    && result.getData() != null) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id).isEqualTo(dataJson.asText());
            }
        }
    }

    private ToolResult runScenario(String id, String skillDir) {
        Tools45cFakes.MemStore store = new Tools45cFakes.MemStore();
        switch (id) {
            case "exec_edit_ok" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 12, null);
                store.data = "hello world\nsecond line\n".getBytes();
            }
            case "exec_edit_is_dir" -> store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_DIR, 0, null);
            case "exec_edit_stat_fails" -> store.statErr = Tools45cFakes.boom("no route");
            case "exec_edit_read_fails" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 3, null);
                store.readErr = Tools45cFakes.boom("io");
            }
            case "exec_edit_binary_file" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 4, null);
                store.data = new byte[] {0, 1, 2, 3};
            }
            case "exec_edit_not_found" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 5, null);
                store.data = "hello".getBytes();
            }
            case "exec_edit_replace_all" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 12, null);
                store.data = "hello world\nsecond line\n".getBytes();
            }
            case "exec_write_fails" -> store.writeErr = Tools45cFakes.boom("disk full");
            default -> {
            }
        }
        boolean write = !id.startsWith("exec_edit");
        String args;
        ToolResult result;
        // 探针的 runEdit 全部用 rec45cCtx（带 session）；"no_session" 命名的用例实际测的是
        // 空 store 上的 not-found
        ToolExecContext meta = new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0);
        if (write) {
            WriteSkillFileTool tool = new WriteSkillFileTool(store, skillDir);
            args = switch (id) {
                case "exec_ok" -> "{\"path\":\"requirements.json\",\"content\":\"{\\\"pkg\\\":1}\\n\"}";
                case "exec_empty_path_arg" -> "{\"content\":\"x\"}";
                case "exec_binary" -> "{\"path\":\"a.py\",\"content\":\"\\u0000\\u0001\\u0002\"}";
                case "exec_write_fails" -> "{\"path\":\"a.txt\",\"content\":\"x\"}";
                case "exec_syntax_hint" -> "{\"path\":\"run.py\",\"content\":\"print(\\\"这不是一个\\\"大干快上\\\")\\n\"}";
                case "exec_outside" -> "{\"path\":\"../escape.txt\",\"content\":\"x\"}";
                default -> throw new IllegalArgumentException(id);
            };
            result = tool.execute(new ToolRequest(RecordingSupport.readTree(args), meta, ToolCancellation.LIVE, 0));
        } else {
            EditSkillFileTool tool = new EditSkillFileTool(store, skillDir);
            args = switch (id) {
                case "exec_edit_ok" -> "{\"path\":\"a.txt\",\"old_string\":\"hello\",\"new_string\":\"hi\"}";
                case "exec_edit_is_dir" -> "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
                case "exec_edit_stat_fails" -> "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
                case "exec_edit_read_fails" -> "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
                case "exec_edit_binary_file" -> "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
                case "exec_edit_not_found" -> "{\"path\":\"a.txt\",\"old_string\":\"nope\",\"new_string\":\"b\"}";
                case "exec_edit_replace_all" -> "{\"path\":\"a.txt\",\"old_string\":\"l\",\"new_string\":\"L\",\"replace_all\":true}";
                case "exec_edit_no_session" -> "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}";
                default -> throw new IllegalArgumentException(id);
            };
            result = tool.execute(new ToolRequest(RecordingSupport.readTree(args), meta, ToolCancellation.LIVE, 0));
        }
        return result;
    }
}
