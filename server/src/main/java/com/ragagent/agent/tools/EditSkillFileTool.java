package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SandboxEdits.Applied;

/**
 * edit_skill_file：对本次安装的 skill 目录内文件做精确替换（对照 Go {@code skill_file.go} 的
 * EditSkillFileTool，逐字移植）。与 sandbox 编辑共用 {@link SandboxEdits} 的应用算法。
 */
public class EditSkillFileTool extends BaseTool {

    private static final String DESCRIPTION = "Replace exact text in a file inside the skill directory being installed.\n"
            + "\n"
            + "## Usage\n"
            + "- Use this when only a few lines of an existing file need to change — a wrong\n"
            + "  path, an import, a constant.\n"
            + "- `old_string` must match the file exactly, including whitespace and\n"
            + "  quotes. Include a few surrounding lines so the match is unique.\n"
            + "- Default: the snippet must occur exactly once. Set `replace_all=true`\n"
            + "  only when you intentionally want every occurrence changed.\n"
            + "- " + PythonSyntax.PYTHON_QUOTE_GUIDANCE + "\n"
            + "\n"
            + "## When NOT to Use\n"
            + "- Creating a new file — use `write_skill_file`.\n"
            + "- Replacing most of the file — rewrite it with `write_skill_file`.\n"
            + "- Binary files.\n"
            + "\n"
            + "## Path Rules\n"
            + "- `path` MUST be inside this install's skill directory. Absolute, or\n"
            + "  relative to that directory.\n"
            + "\n"
            + "## Size Handling\n"
            + "- The file (and the result) must stay within 262144 bytes.\n"
            + "\n"
            + "## Returns\n"
            + "- The path, how many replacements were made, and the new byte count.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Path of an existing text file inside the skill directory being installed. Absolute, or relative to that directory.\"},"
                    + "\"old_string\":{\"type\":\"string\",\"description\":\"Exact text to find. Include enough surrounding lines so the match is unique unless replace_all is true.\"},"
                    + "\"new_string\":{\"type\":\"string\",\"description\":\"Replacement text. Use an empty string to delete the matched text.\"},"
                    + "\"replace_all\":{\"type\":\"boolean\",\"description\":\"If true, replace every occurrence. If false (default), old_string must match exactly once.\"}},"
                    + "\"required\":[\"path\",\"old_string\",\"new_string\"],\"additionalProperties\":false}";

    private final SkillFileStore store;
    private final String skillDir;

    public EditSkillFileTool(SkillFileStore store, String skillDir) {
        super(ToolDefinitions.TOOL_EDIT_SKILL_FILE, DESCRIPTION, SCHEMA_JSON);
        this.store = store;
        this.skillDir = skillDir;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String path = args.path("path").asText("");
        String oldString = args.path("old_string").asText("");
        String newString = args.path("new_string").asText("");
        boolean replaceAll = args.path("replace_all").asBoolean(false);

        if (store == null) {
            return SkillFiles.failure("skill file editing is not available in this deployment");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return SkillFiles.failure("no session ID in context; edit_skill_file must run inside an agent turn");
        }

        String clean;
        try {
            clean = SkillFiles.resolveSkillFilePath(skillDir, path);
        } catch (IllegalArgumentException e) {
            return SkillFiles.failure(e.getMessage());
        }

        RemoteStatEntry stat;
        try {
            stat = store.statSessionFile(sessionId, clean);
        } catch (Exception e) {
            return SkillFiles.failure("failed to stat " + clean + ": " + e.getMessage());
        }
        if (stat != null && stat.isDir()) {
            return SkillFiles.failure(clean + " is a directory; edit_skill_file only edits files");
        }
        if (stat != null && stat.size() > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
            return SkillFiles.failure(String.format(
                    "file too large to edit (%d bytes; max %d). Rewrite a smaller file with write_skill_file",
                    stat.size(), WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
        }

        byte[] raw;
        try {
            raw = store.readSessionFile(sessionId, clean);
        } catch (Exception e) {
            return SkillFiles.failure("failed to read " + clean + ": " + e.getMessage());
        }
        if (raw.length > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
            return SkillFiles.failure(String.format("file too large to edit (%d bytes; max %d)",
                    raw.length, WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
        }
        String original = new String(raw, StandardCharsets.UTF_8);
        if (ShellExecTool.isBinaryShellOutput(original)) {
            return SkillFiles.failure("binary files cannot be edited");
        }

        Applied applied;
        try {
            applied = SandboxEdits.applySandboxEdits(original, List.of(
                    new SandboxEdits.SandboxEdit(oldString, newString, replaceAll)));
        } catch (SandboxEdits.EditException e) {
            return SkillFiles.failure(e.getMessage());
        }
        String updated = applied.content();
        int replacements = applied.replacements();
        byte[] content = updated.getBytes(StandardCharsets.UTF_8);
        if (content.length > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
            return SkillFiles.failure(String.format(
                    "result too large (%d bytes; max %d). Shrink new_string or split the file",
                    content.length, WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
        }
        if (ShellExecTool.isBinaryShellOutput(updated)) {
            return SkillFiles.failure("replacement would introduce binary content, which is not accepted");
        }

        try {
            store.writeSessionFile(sessionId, clean, content);
        } catch (Exception e) {
            return SkillFiles.failure("failed to write " + clean + ": " + e.getMessage());
        }

        Map<String, Object> data = SkillFiles.baseData(ToolDefinitions.TOOL_EDIT_SKILL_FILE,
                sessionId, clean, skillDir, content.length);
        data.put("replacements", replacements);
        String hint = PythonSyntax.pythonScriptSyntaxHint(clean, updated, ToolDefinitions.TOOL_EDIT_SKILL_FILE);
        if (!hint.isEmpty()) {
            data.put("syntax_error", true);
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(hint);
            r.setOutput("=== Edited skill file with syntax problems: " + clean + " ===\n\n" + hint + "\n");
            r.setData(data);
            return r;
        }
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput("=== Edited skill file: " + clean + " ===\n\nreplacements=" + replacements
                + "\nbytes=" + content.length + "\n");
        r.setData(data);
        return r;
    }
}
