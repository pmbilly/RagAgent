package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * write_skill_file：向本次安装的 skill 目录写文本文件（对照 Go {@code skill_file.go} 的
 * WriteSkillFileTool，逐字移植）。
 *
 * <p>安装器曾经的唯一写入手段是 shell_exec heredoc——命令长度封顶加两层引号变形，
 * requirements.json 或小补丁动辄截断。这个工具经文件 API 写同样的字节。作用域：
 * 一次安装写一个 skill；工具以该 skill 的目录构造，目录之外一律拒绝——安装器
 * 碰不到共享镜像里的邻居 skill，哪怕它的 shell 是 root。</p>
 */
public class WriteSkillFileTool extends BaseTool {

    private static final String DESCRIPTION = "Create or overwrite a text file inside the skill directory being installed.\n"
            + "\n"
            + "## Usage\n"
            + "- This is the way to write a file into the skill tree. Do NOT use\n"
            + "  `shell_exec` with `cat`, a heredoc, or `python -c`:\n"
            + "  those hit a command-length cap and mangle quoting.\n"
            + "- Use it for `.weknora/requirements.json`, a small wrapper script, or a\n"
            + "  patch to a shipped file.\n"
            + "- " + PythonSyntax.PYTHON_QUOTE_GUIDANCE + "\n"
            + "\n"
            + "## When NOT to Use\n"
            + "- To change a few lines of an existing file, call `edit_skill_file`.\n"
            + "- To write scratch files — the skill directory is snapshotted; keep it clean.\n"
            + "- Binary content. Have a script produce binary files instead.\n"
            + "\n"
            + "## Path Rules\n"
            + "- `path` MUST be absolute and inside this install's skill directory.\n"
            + "  A relative path is resolved against that directory.\n"
            + "- Any path outside it is refused, including another skill's directory.\n"
            + "\n"
            + "## Size Handling\n"
            + "- Content is capped at 262144 bytes per call.\n"
            + "\n"
            + "## Returns\n"
            + "- The absolute path and byte count. File contents are not echoed back.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Path of the file to write, inside the skill directory being installed. Absolute, or relative to that directory.\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"Full text contents of the file. Overwrites any existing file at path. Maximum 262144 bytes. Do not send binary bytes.\"}},"
                    + "\"required\":[\"path\",\"content\"],\"additionalProperties\":false}";

    protected final SkillFileStore store;
    protected final String skillDir;

    public WriteSkillFileTool(SkillFileStore store, String skillDir) {
        super(ToolDefinitions.TOOL_WRITE_SKILL_FILE, DESCRIPTION, SCHEMA_JSON);
        this.store = store;
        this.skillDir = skillDir;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");

        if (store == null) {
            return SkillFiles.failure("skill file writing is not available in this deployment");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return SkillFiles.failure("no session ID in context; write_skill_file must run inside an agent turn");
        }

        String clean;
        try {
            clean = SkillFiles.resolveSkillFilePath(skillDir, path);
        } catch (IllegalArgumentException e) {
            return SkillFiles.failure(e.getMessage());
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
            return SkillFiles.failure(String.format("content too large (%d bytes; max %d). Split the file",
                    bytes.length, WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
        }
        if (ShellExecTool.isBinaryShellOutput(content)) {
            return SkillFiles.failure("binary content is not accepted; write a text file instead");
        }

        try {
            store.writeSessionFile(sessionId, clean, bytes);
        } catch (Exception e) {
            return SkillFiles.failure("failed to write " + clean + ": " + e.getMessage());
        }

        Map<String, Object> data = SkillFiles.baseData(ToolDefinitions.TOOL_WRITE_SKILL_FILE,
                sessionId, clean, skillDir, bytes.length);

        String hint = PythonSyntax.pythonScriptSyntaxHint(clean, content, ToolDefinitions.TOOL_EDIT_SKILL_FILE);
        if (!hint.isEmpty()) {
            data.put("syntax_error", true);
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(hint);
            r.setOutput("=== Wrote skill file with syntax problems: " + clean + " ===\n\n" + hint + "\n");
            r.setData(data);
            return r;
        }
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput("=== Wrote skill file: " + clean + " ===\n\nbytes=" + bytes.length + "\n");
        r.setData(data);
        return r;
    }
}
