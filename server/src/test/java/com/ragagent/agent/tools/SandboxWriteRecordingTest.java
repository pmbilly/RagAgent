package com.ragagent.agent.tools;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * write_sandbox_file 的 Go 实录回放（预算、尺寸引导、模式归一、描述与 schema、Execute 全路径）。
 */
class SandboxWriteRecordingTest {

    @Test
    void writeBudgetBytesMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_write", "budget").get("cases");
        for (JsonNode c : cases) {
            assertThat(WriteSandboxFileTool.writeBudgetBytes(c.get("tokens").asInt()))
                    .as("tokens=%d", c.get("tokens").asInt())
                    .isEqualTo(c.get("bytes").asInt());
        }
    }

    @Test
    void writeSizeGuidanceMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_write", "size_guidance").get("cases");
        for (JsonNode c : cases) {
            assertThat(WriteSandboxFileTool.writeSizeGuidance(c.get("max_bytes").asInt()))
                    .as("max_bytes=%d", c.get("max_bytes").asInt())
                    .isEqualTo(c.get("out").asText());
        }
    }

    @Test
    void normalizeWriteModeMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_write", "normalize_mode").get("cases");
        for (JsonNode c : cases) {
            String in = c.get("in").asText();
            assertThat(WriteSandboxFileTool.normalizeWriteMode(in)).as("mode %s", in)
                    .isEqualTo(c.get("mode").asText());
            assertThat(WriteSandboxFileTool.normalizeWriteModeMessage(in)).as("err %s", in)
                    .isEqualTo(c.get("err").asText());
        }
    }

    @Test
    void descriptionAndSchemaMatchGo() {
        assertThat(WriteSandboxFileTool.buildDescription(0))
                .isEqualTo(Tools45cFakes.rec45c("sandbox_write", "description_0").get("out").asText());
        assertThat(WriteSandboxFileTool.buildDescription(24576))
                .isEqualTo(Tools45cFakes.rec45c("sandbox_write", "description_24576").get("out").asText());
        WriteSandboxFileTool tool = new WriteSandboxFileTool(null, 0);
        // schema 的 Go 侧是 map marshal（键字母序）；两侧做键序无关的规范化比较
        assertThat(RecordingSupport.canonicalJson(tool.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(
                        RecordingSupport.readTree(Tools45cFakes.rec45c("sandbox_write", "schema").get("out").asText())));
        assertThat(WriteSandboxFileTool.workspaceWriteScopeError("/workspace/input/att.txt"))
                .isEqualTo(Tools45cFakes.rec45c("sandbox_write", "scope_error").get("out").asText());
    }

    @Test
    void matchingWritableRootMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("sandbox_write", "writable_roots").get("cases");
        for (JsonNode c : cases) {
            String[] root = WriteSandboxFileTool.matchingWritableRoot(c.get("in").asText());
            boolean ok = root[1] != null;
            assertThat(ok).as("ok %s", c.get("in").asText()).isEqualTo(c.get("ok").asBoolean());
            if (c.hasNonNull("root") && ok) {
                assertThat(root[0]).isEqualTo(c.get("root").asText());
            }
        }
    }

    @Test
    void executePathsMatchGo() {
        String[][] cases = {
                {"exec_ok", "{}"},
                {"exec_empty_path", "{}"},
                {"exec_outside", "{}"},
                {"exec_input_tree", "{}"},
                {"exec_bad_mode", "{}"},
                {"exec_binary", "{}"},
                {"exec_append_ok", "{}"},
                {"exec_append_missing", "{}"},
                {"exec_append_dir", "{}"},
                {"exec_syntax_hint", "{}"},
        };
        for (String[] c : cases) {
            JsonNode r = Tools45cFakes.rec45c("sandbox_write", c[0]);
            ToolResult result = runScenario(c[0]);
            assertThat(result.isSuccess()).as("success %s", c[0]).isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput()).as("output %s", c[0]).isEqualTo(r.get("output").asText());
            assertThat(result.getError() == null ? "" : result.getError()).as("error %s", c[0])
                    .isEqualTo(r.get("error").asText());
            JsonNode dataJson = r.get("data_json");
            if (dataJson != null && !dataJson.isNull() && !dataJson.asText().isEmpty()) {
                assertThat(RecordingSupport.goJsonOfData(result.getData()))
                        .as("data %s", c[0])
                        .isEqualTo(dataJson.asText());
            }
        }
    }

    private ToolResult runScenario(String id) {
        Tools45cFakes.MemStore sink = new Tools45cFakes.MemStore();
        switch (id) {
            case "exec_append_ok" -> {
                sink.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 6, null);
                sink.data = "part1\n".getBytes();
            }
            case "exec_append_dir" -> sink.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_DIR, 0, null);
            default -> {
            }
        }
        WriteSandboxFileTool tool = new WriteSandboxFileTool(sink, 0);
        String args = switch (id) {
            case "exec_ok" -> "{\"path\":\"out/report.py\",\"content\":\"print(1)\\nprint(2)\\n\"}";
            case "exec_empty_path" -> "{\"content\":\"x\"}";
            case "exec_outside" -> "{\"path\":\"/tmp/evil.txt\",\"content\":\"x\"}";
            case "exec_input_tree" -> "{\"path\":\"input/att.txt\",\"content\":\"x\"}";
            case "exec_bad_mode" -> "{\"path\":\"a.txt\",\"content\":\"x\",\"mode\":\"bogus\"}";
            case "exec_binary" -> "{\"path\":\"a.txt\",\"content\":\"\\u0000\\u0001\\u0002\"}";
            case "exec_append_ok" -> "{\"path\":\"log.txt\",\"content\":\"part2\\n\",\"mode\":\"append\"}";
            case "exec_append_missing" -> "{\"path\":\"log.txt\",\"content\":\"part2\\n\",\"mode\":\"append\"}";
            case "exec_append_dir" -> "{\"path\":\"log.txt\",\"content\":\"part2\\n\",\"mode\":\"append\"}";
            case "exec_syntax_hint" -> "{\"path\":\"broken.py\",\"content\":\"print(\\\"这不是一个\\\"大干快上\\\")\\n\"}";
            default -> throw new IllegalArgumentException(id);
        };
        return tool.execute(new ToolRequest(RecordingSupport.readTree(args),
                new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0), ToolCancellation.LIVE, 0));
    }
}
