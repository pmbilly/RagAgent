package com.ragagent.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * read_file 的 Go 实录回放（描述组合、分页、workspace/skill/web 分支的执行路径）。
 * skill 侧的 fake 镜像探针里真 skills.Manager 在该目录布局下的观测行为。
 */
class ReadFileRecordingTest {

    // 对照 Go loader：frontmatter 之后的正文会去掉尾部换行
    private static final String SKILL_MD_BODY =
            "# Instructions\nUse the bundled guide.";

    /** 探针 readFileSkills 的目录布局 → 内存 fake。 */
    private static Tools45cFakes.MemSkills skillEnv() {
        Tools45cFakes.MemSkills env = new Tools45cFakes.MemSkills();
        env.enabled = true;
        env.metadata = List.of(Tools45cFakes.MemSkills.meta("allowed", "Test file resources"));
        env.documents.put("allowed", new SkillEnvironment.SkillDocument(
                "allowed", "Test file resources", SKILL_MD_BODY));
        env.files.put("allowed", List.of("scripts/run.py", "guide.txt"));
        env.fileContents.put("allowed/guide.txt", "bundled guide\n");
        env.fileContents.put("allowed/scripts/run.py", "print('hi')\n");
        return env;
    }

    @Test
    void schemaMatchesGo() {
        ReadFileTool tool = new ReadFileTool(null);
        assertThat(RecordingSupport.canonicalJson(tool.getParameters()))
                .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(
                        Tools45cFakes.rec45c("read_file", "schema").get("out").asText())));
    }

    @Test
    void descriptionsMatchGo() {
        Tools45cFakes.MemStore source = new Tools45cFakes.MemStore();
        assertThat(new ReadFileTool(null).getDescription())
                .isEqualTo(Tools45cFakes.rec45c("read_file", "description_base").get("out").asText());
        assertThat(new ReadFileTool(null).withWebPages(null).getDescription())
                .isEqualTo(Tools45cFakes.rec45c("read_file", "description_web").get("out").asText());
        assertThat(new ReadFileTool(source).getDescription())
                .isEqualTo(Tools45cFakes.rec45c("read_file", "description_workspace").get("out").asText());
        assertThat(new ReadFileTool(null).withSkills(skillEnv(), false).getDescription())
                .isEqualTo(Tools45cFakes.rec45c("read_file", "description_skills_noshell").get("out").asText());
        assertThat(new ReadFileTool(null).withSkills(skillEnv(), true).getDescription())
                .isEqualTo(Tools45cFakes.rec45c("read_file", "description_skills_shell").get("out").asText());
    }

    @Test
    void paginateMatchesGo() {
        JsonNode cases = Tools45cFakes.rec45c("read_file", "paginate").get("cases");
        for (JsonNode c : cases) {
            JsonNode in = c.get("in");
            WorkspaceFileReader.SandboxFilePage p = WorkspaceFileReader.paginateSandboxFile(
                    in.get("content").asText(), in.get("offset").asInt(), in.get("limit").asInt(),
                    in.get("max_bytes").asLong(), in.get("max_runes").asInt());
            assertThat(p.text()).as("text %s", in).isEqualTo(c.get("text").asText());
            assertThat(p.startLine()).as("start %s", in).isEqualTo(c.get("start").asInt());
            assertThat(p.endLine()).as("end %s", in).isEqualTo(c.get("end").asInt());
            assertThat(p.totalLines()).as("total %s", in).isEqualTo(c.get("total").asInt());
            assertThat(p.nextOffset()).as("next %s", in).isEqualTo(c.get("next").asInt());
            assertThat(p.lineTooLarge()).as("too_large %s", in).isEqualTo(c.get("too_large").asBoolean());
            assertThat(p.lineBytes()).as("line_bytes %s", in).isEqualTo(c.get("line_bytes").asInt());
        }
    }

    @Test
    void executePathsMatchGo() {
        String[] ids = {"exec_workspace_ok", "exec_workspace_empty_path", "exec_workspace_dir",
                "exec_workspace_symlink", "exec_workspace_missing", "exec_workspace_stat_err",
                "exec_workspace_read_err", "exec_workspace_oversize", "exec_workspace_binary",
                "exec_workspace_line_offset", "exec_workspace_outside", "exec_no_source",
                "exec_no_session_ctx",
                "exec_skill_md_noshell", "exec_skill_guide", "exec_skill_tree_nested",
                "exec_skill_traversal", "exec_skill_dot", "exec_skill_double_slash",
                "exec_skill_unknown", "exec_skill_not_listed", "exec_skill_no_skills",
                "exec_skill_bad_form", "exec_skill_line_offset_rejected"};
        for (String id : ids) {
            JsonNode r = Tools45cFakes.rec45c("read_file", id);
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

    private ToolResult runScenario(String id) {
        Tools45cFakes.MemStore source = new Tools45cFakes.MemStore();
        boolean workspaceScenario = id.startsWith("exec_workspace") || id.equals("exec_no_source")
                || id.equals("exec_no_session_ctx");
        ReadFileTool tool;
        String args;
        boolean withSession;
        if (id.startsWith("exec_skill")) {
            boolean shell = false;
            if (id.equals("exec_skill_no_skills")) {
                tool = new ReadFileTool(null);
            } else {
                tool = new ReadFileTool(null).withSkills(skillEnv(), shell);
            }
        } else {
            switch (id) {
                case "exec_workspace_ok" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 12, null);
                    source.data = "hello\nworld\n".getBytes();
                }
                case "exec_workspace_empty_path" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 1, null);
                    source.data = "x".getBytes();
                }
                case "exec_workspace_dir" -> source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_DIR, 0, null);
                case "exec_workspace_symlink" -> source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_OTHER, 3, null);
                case "exec_workspace_stat_err" -> source.statErr = Tools45cFakes.boom("conn reset");
                case "exec_workspace_read_err" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 3, null);
                    source.readErr = Tools45cFakes.boom("boom");
                }
                case "exec_workspace_oversize" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE,
                            WorkspaceFileReader.MAX_READ_SANDBOX_DOWNLOAD_BYTES + 1, null);
                    source.data = "nope".getBytes();
                }
                case "exec_workspace_binary" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 4, null);
                    source.data = new byte[] {0, 1, 2, 3};
                }
                case "exec_workspace_line_offset", "exec_workspace_outside", "exec_no_session_ctx" -> {
                    source.stat = new RemoteStatEntry("", RemoteDirEntry.TYPE_FILE, 2, null);
                    source.data = "hi".getBytes();
                }
                default -> {
                    // exec_workspace_missing / exec_no_source
                }
            }
            tool = "exec_no_source".equals(id) ? new ReadFileTool(null) : new ReadFileTool(source);
        }
        args = switch (id) {
            case "exec_workspace_ok" -> "{\"path\":\"output/report.txt\"}";
            case "exec_workspace_empty_path" -> "{\"path\":\"  \"}";
            case "exec_workspace_dir" -> "{\"path\":\"output\"}";
            case "exec_workspace_symlink" -> "{\"path\":\"output/link\"}";
            case "exec_workspace_missing" -> "{\"path\":\"output/gone.txt\"}";
            case "exec_workspace_stat_err" -> "{\"path\":\"output/x\"}";
            case "exec_workspace_read_err" -> "{\"path\":\"output/x\"}";
            case "exec_workspace_oversize" -> "{\"path\":\"output/big.bin\"}";
            case "exec_workspace_binary" -> "{\"path\":\"output/x.bin\"}";
            case "exec_workspace_line_offset" -> "{\"path\":\"output/x\",\"line_offset\":5}";
            case "exec_workspace_outside" -> "{\"path\":\"/etc/passwd\"}";
            case "exec_no_source" -> "{\"path\":\"output/x\"}";
            case "exec_no_session_ctx" -> "{\"path\":\"output/x\"}";
            case "exec_skill_md_noshell" -> "{\"path\":\"skill://allowed/SKILL.md\"}";
            case "exec_skill_guide" -> "{\"path\":\"skill://allowed/guide.txt\"}";
            case "exec_skill_tree_nested" -> "{\"path\":\"skill://allowed/scripts/run.py\"}";
            case "exec_skill_traversal" -> "{\"path\":\"skill://allowed/../other/SKILL.md\"}";
            case "exec_skill_dot" -> "{\"path\":\"skill://allowed/./guide.txt\"}";
            case "exec_skill_double_slash" -> "{\"path\":\"skill://allowed//guide.txt\"}";
            case "exec_skill_unknown" -> "{\"path\":\"skill://unknown/SKILL.md\"}";
            case "exec_skill_not_listed" -> "{\"path\":\"skill://other/SKILL.md\"}";
            case "exec_skill_no_skills" -> "{\"path\":\"skill://allowed/SKILL.md\"}";
            case "exec_skill_bad_form" -> "{\"path\":\"skill://allowed\"}";
            case "exec_skill_line_offset_rejected" -> "{\"path\":\"skill://allowed/guide.txt\",\"line_offset\":3}";
            default -> throw new IllegalArgumentException(id);
        };
        withSession = workspaceScenario && !id.equals("exec_no_session_ctx");
        ToolExecContext meta = withSession
                ? new ToolExecContext("zz45c-session", "", "", "", "", null, null, 0)
                : null;
        return tool.execute(new ToolRequest(RecordingSupport.readTree(args), meta, ToolCancellation.LIVE, 0));
    }

    // Map/LinkedHashMap 引用锚（renderFilePage 的 data 组装校验经由 goJsonOfData）。
    @SuppressWarnings("unused")
    private static void anchors(Map<String, Object> m) {
        new LinkedHashMap<>(m).isEmpty();
    }
}
