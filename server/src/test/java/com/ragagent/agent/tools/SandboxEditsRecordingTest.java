package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * edit_sandbox_file 的 Go 实录回放（applySandboxEdits 算法 + Execute 全路径）。
 */
class SandboxEditsRecordingTest {

    private static JsonNode rec(String constant) {
        return GoRecording45C.rec(constant);
    }

    private static List<SandboxEdits.SandboxEdit> parseEdits(JsonNode node) {
        List<SandboxEdits.SandboxEdit> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode e : node) {
                out.add(new SandboxEdits.SandboxEdit(
                        e.path("old_string").asText(""),
                        e.path("new_string").asText(""),
                        e.path("replace_all").asBoolean(false)));
            }
        }
        return out;
    }

    @Test
    void applySandboxEditsMatchesGo() {
        String[] ids = {"apply_ok_single", "apply_ok_multi", "apply_ok_replace_all", "apply_empty_edits",
                "apply_not_found_single", "apply_not_found_multi", "apply_ambiguous_single",
                "apply_ambiguous_multi", "apply_identical_single", "apply_identical_multi",
                "apply_empty_old_single", "apply_empty_old_multi", "apply_overlap",
                "apply_replace_all_preserves", "apply_delete_to_empty"};
        for (String id : ids) {
            JsonNode r = Tools45cFakes.rec45c("sandbox_edit", id);
            List<SandboxEdits.SandboxEdit> edits = parseEdits(
                    RecordingSupport.readTree(r.get("edits_json").asText()));
            String err = "";
            String out = "";
            int replacements = 0;
            try {
                SandboxEdits.Applied applied = SandboxEdits.applySandboxEdits(r.get("content").asText(), edits);
                out = applied.content();
                replacements = applied.replacements();
            } catch (SandboxEdits.EditException e) {
                err = e.getMessage();
            }
            assertThat(out).as("out %s", id).isEqualTo(r.get("out").asText());
            assertThat(replacements).as("replacements %s", id).isEqualTo(r.get("replacements").asInt());
            assertThat(err).as("err %s", id).isEqualTo(r.get("err").asText());
        }
    }

    @Test
    void indexAllNonOverlappingMatchesGo() {
        JsonNode cases = rec(GoRecording45C.R_SANDBOX_EDIT_INDEX_ALL).get("cases");
        for (JsonNode c : cases) {
            List<Integer> found = SandboxEdits.indexAllNonOverlapping(
                    c.get("content").asText(), c.get("sub").asText());
            List<Integer> expected = new ArrayList<>();
            c.get("found").forEach(n -> expected.add(n.asInt()));
            assertThat(found).as("content=%s sub=%s", c.get("content").asText(), c.get("sub").asText())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void errorMessageHelpersMatchGo() {
        JsonNode r = rec(GoRecording45C.R_SANDBOX_EDIT_ERRORS);
        assertThat(SandboxEdits.notFoundSandboxEditError(1, true)).isEqualTo(r.get("not_found_multi").asText());
        assertThat(SandboxEdits.notFoundSandboxEditError(0, false)).isEqualTo(r.get("not_found_single").asText());
        assertThat(SandboxEdits.ambiguousSandboxEditError(2, true, 3)).isEqualTo(r.get("ambiguous_multi").asText());
        assertThat(SandboxEdits.ambiguousSandboxEditError(0, false, 2)).isEqualTo(r.get("ambiguous_single").asText());
        assertThat(SandboxEdits.overlappingSandboxEditError(0, 1)).isEqualTo(r.get("overlap").asText());
    }

    @Test
    void executePathsMatchGo() {
        String[] ids = {"exec_ok", "exec_syntax_hint", "exec_is_dir", "exec_read_failed", "exec_binary",
                "exec_not_found", "exec_outside_scope", "exec_empty_path", "exec_ok_session", "exec_relative"};
        for (String id : ids) {
            JsonNode r = Tools45cFakes.rec45c("sandbox_edit", id);
            ToolResult result = runScenario(id);
            assertThat(result.getOutput()).as("output %s", id).isEqualTo(r.get("output").asText());
            assertThat(result.getError() == null ? "" : result.getError()).as("error %s", id)
                    .isEqualTo(r.get("error").asText());
            if (r.hasNonNull("data_json") && !r.get("data_json").asText().isEmpty()) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", id)
                        .isEqualTo(r.get("data_json").asText());
            }
        }
        // no-session 分支单独断言（无 data 键）
        JsonNode noSession = Tools45cFakes.rec45c("sandbox_edit", "exec_no_session_ctx");
        EditSandboxFileTool tool = new EditSandboxFileTool(new Tools45cFakes.MemStore());
        ToolResult result = tool.execute(ToolRequest.of(RecordingSupport.readTree(
                "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"world\",\"new_string\":\"there\"}]}")));
        assertThat(result.isSuccess()).isEqualTo(noSession.get("success").asBoolean());
        assertThat(result.getError()).isEqualTo(noSession.get("error").asText());
    }

    /** 按探针源码重建各 exec 场景的输入与 fake 状态。 */
    private ToolResult runScenario(String id) {
        Tools45cFakes.MemStore store = new Tools45cFakes.MemStore();
        switch (id) {
            case "exec_ok", "exec_syntax_hint", "exec_ok_session" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 12, null);
                store.data = "hello world\nsecond line\n".getBytes();
            }
            case "exec_is_dir" -> store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_DIR, 0, null);
            case "exec_read_failed" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 5, null);
                store.readErr = Tools45cFakes.boom("io boom");
            }
            case "exec_binary" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 4, null);
                store.data = new byte[] {0, 1, 2, 3};
            }
            case "exec_not_found" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 12, null);
                store.data = "hello world\nsecond line\n".getBytes();
            }
            case "exec_relative" -> {
                store.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 5, null);
                store.data = "hello".getBytes();
            }
            default -> {
                // exec_outside_scope / exec_empty_path：无需 stat
            }
        }
        EditSandboxFileTool tool = new EditSandboxFileTool(store);
        ToolExecContext meta = new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0);
        String args = switch (id) {
            case "exec_ok" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"world\",\"new_string\":\"there\"}]}";
            case "exec_syntax_hint" -> "{\"path\":\"f.py\",\"edits\":[{\"old_string\":\"print(\\\"a\\\")\",\"new_string\":\"print(\\\"a\\\" \\\"b\\\")\"}]}";
            case "exec_is_dir" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}";
            case "exec_read_failed" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}";
            case "exec_binary" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}";
            case "exec_not_found" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"nope\",\"new_string\":\"b\"}]}";
            case "exec_outside_scope" -> "{\"path\":\"../etc/x.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}";
            case "exec_empty_path" -> "{\"path\":\"  \",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"}]}";
            case "exec_ok_session" -> "{\"path\":\"f.txt\",\"edits\":[{\"old_string\":\"world\",\"new_string\":\"there\"}]}";
            case "exec_relative" -> "{\"path\":\"/workspace/sub/../f.txt\",\"edits\":[{\"old_string\":\"hello\",\"new_string\":\"hi\"}]}";
            default -> throw new IllegalArgumentException(id);
        };
        return tool.execute(new ToolRequest(RecordingSupport.readTree(args), meta, ToolCancellation.LIVE, 0));
    }

    @Test
    void emptyEditsRejected() {
        assertThatThrownBy(() -> SandboxEdits.applySandboxEdits("abc", List.of()))
                .isInstanceOf(SandboxEdits.EditException.class)
                .hasMessage("edits is required: an array of {old_string, new_string}, "
                        + "with one entry even for a single change");
    }
}
