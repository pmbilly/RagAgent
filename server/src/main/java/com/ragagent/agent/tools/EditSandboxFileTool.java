package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;
import com.ragagent.agent.tools.SandboxEdits.Applied;

/**
 * edit_sandbox_file：对会话 sandbox 里已有文本文件的手术式替换
 * （对照 Go {@code sandbox_edit.go}，逐字移植）。
 *
 * <p>write_sandbox_file 是新文件的第一步；这个工具让一行路径修正不必重新生成整个文件。
 * 唯一输入形状：每个变更都是 edits[] 的一项，全部对原始内容求值——批与顺序无关，
 * 重叠被拒绝而不是静默损坏。缺省要求唯一匹配，per-entry replace_all 替换全部。</p>
 */
public class EditSandboxFileTool extends BaseTool {

    private static final String DESCRIPTION =
            "Apply exact text replacements to an existing text file under /workspace, excluding /workspace/input.\n"
                    + "Read the relevant content first. Send edits as an array, even for one replacement. Every old_string matches the original file, must be unique unless replace_all=true, and must not overlap another edit. Include enough surrounding text to identify the intended occurrence.\n"
                    + "All replacements are validated before writing; a failed match leaves the file unchanged. The result includes a diff. Use write_sandbox_file for new files.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute or /workspace-relative sandbox path of an existing text file under /workspace (not /workspace/input).\"},"
                    + "\"edits\":{\"type\":\"array\",\"description\":\"Replacements to apply, as an array even for a single change. Each old_string is matched against the original file, not against the result of earlier edits, so they must not overlap. Send every change to one file in one call rather than calling the tool repeatedly.\",\"items\":{"
                    + "\"type\":\"object\",\"properties\":{\"old_string\":{\"type\":\"string\",\"description\":\"Exact text to find, unique in the file and not overlapping any other edit in this call.\"},"
                    + "\"new_string\":{\"type\":\"string\",\"description\":\"Replacement text. Use an empty string to delete the matched text.\"},"
                    + "\"replace_all\":{\"type\":\"boolean\",\"description\":\"If true, replace every occurrence of this old_string instead of requiring it to be unique.\"}},"
                    + "\"required\":[\"old_string\",\"new_string\"],\"additionalProperties\":false}}},"
                    + "\"required\":[\"path\",\"edits\"],\"additionalProperties\":false}";

    private final SandboxFileEditor editor;

    public EditSandboxFileTool(SandboxFileEditor editor) {
        super(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, DESCRIPTION, SCHEMA_JSON);
        this.editor = editor;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String path = args.path("path").asText("");
        List<SandboxEdits.SandboxEdit> edits;
        try {
            edits = parseEdits(args.get("edits"));
        } catch (SandboxEdits.EditException e) {
            // 对照 Go 的 json.Unmarshal 失败 → "Failed to parse args: ..."
            return failure("Failed to parse args: " + e.getMessage());
        }

        if (editor == null) {
            return failure("sandbox file editing is not available in this deployment");
        }

        String trimmed = path.strip();
        if (trimmed.isEmpty()) {
            return failure("path is required; edit a file under /workspace (not /workspace/input)");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return failure("no session ID in context; edit_sandbox_file must run inside an agent turn");
        }

        String clean = SandboxPaths.resolveWorkspacePath(trimmed);
        String[] rootHit = WriteSandboxFileTool.matchingWritableRoot(clean);
        if (rootHit[1] == null) {
            return failure(WriteSandboxFileTool.workspaceWriteScopeError(path));
        }
        String rootDir = rootHit[0];

        // 读-改-写必须对并发兄弟全程持锁，不只是写。
        Runnable release = FileMutationQueue.lockSandboxFile(sessionId, clean);
        try {
            RemoteStatEntry stat;
            try {
                stat = editor.statSessionFile(sessionId, clean);
            } catch (Exception e) {
                return failure("failed to stat " + clean + ": " + e.getMessage());
            }
            if (stat != null && stat.isDir()) {
                return failure(clean + " is a directory; edit_sandbox_file only edits files");
            }
            if (stat != null && stat.size() > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
                return failure(String.format(
                        "file too large to edit (%d bytes; max %d). Split the work or rewrite a smaller file with write_sandbox_file",
                        stat.size(), WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
            }

            byte[] raw;
            try {
                raw = editor.readSessionFile(sessionId, clean);
            } catch (Exception e) {
                return failure("failed to read " + clean + ": " + e.getMessage());
            }
            if (raw.length > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
                return failure(String.format("file too large to edit (%d bytes; max %d)",
                        raw.length, WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
            }
            String original = new String(raw, StandardCharsets.UTF_8);
            if (ShellExecTool.isBinaryShellOutput(original)) {
                return failure("binary files cannot be edited; write a text script and have it produce binary artifacts under /workspace/output");
            }

            Applied applied;
            try {
                applied = SandboxEdits.applySandboxEdits(original, edits);
            } catch (SandboxEdits.EditException e) {
                return failure(e.getMessage());
            }
            String updated = applied.content();
            int replacements = applied.replacements();

            byte[] content = updated.getBytes(StandardCharsets.UTF_8);
            if (content.length > WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES) {
                return failure(String.format(
                        "result too large (%d bytes; max %d). Shrink new_string or split the file",
                        content.length, WriteSandboxFileTool.MAX_WRITE_SANDBOX_BYTES));
            }
            if (ShellExecTool.isBinaryShellOutput(updated)) {
                return failure("replacement would introduce binary content, which is not accepted");
            }

            try {
                editor.writeSessionWorkspaceFile(sessionId, clean, content);
            } catch (Exception e) {
                return failure("failed to write " + clean + ": " + e.getMessage());
            }

            long[] stats = SandboxDiffs.sandboxEditDiffStats(original, edits);
            int added = (int) stats[0];
            int removed = (int) stats[1];

            String hint = PythonSyntax.pythonScriptSyntaxHint(clean, updated, ToolDefinitions.TOOL_EDIT_SANDBOX_FILE);
            if (!hint.isEmpty()) {
                Map<String, Object> data = baseData(sessionId, clean, rootDir, content.length, replacements);
                data.put("syntax_error", true);
                SandboxDiffs.attachSandboxDiffStats(data, added, removed);
                ToolResult r = new ToolResult();
                r.setSuccess(false);
                r.setError(hint);
                r.setOutput("=== Edited sandbox file with syntax problems: " + clean + " ===\n\n" + hint + "\n");
                r.setData(data);
                return r;
            }

            String diffStat = SandboxDiffs.formatSandboxDiffStat(added, removed);
            if (diffStat.isEmpty()) {
                diffStat = "replacements=" + replacements;
            }
            String output = "=== Edited sandbox file: " + clean + " ===\n\n" + diffStat
                    + "\nreplacements=" + replacements + "\nbytes=" + content.length + "\n";
            Map<String, Object> data = baseData(sessionId, clean, rootDir, content.length, replacements);
            SandboxDiffs.attachSandboxDiffStats(data, added, removed);
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput(output);
            r.setOutputFiles(OutputLinks.sandboxOutputLinks(clean));
            r.setData(data);
            return r;
        } finally {
            release.run();
        }
    }

    private static Map<String, Object> baseData(String sessionId, String clean, String rootDir,
            int size, int replacements) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("display_type", ToolDefinitions.TOOL_EDIT_SANDBOX_FILE);
        data.put("session_id", sessionId);
        data.put("path", clean);
        data.put("root", rootDir);
        data.put("name", SandboxPaths.base(clean));
        data.put("size", size);
        data.put("replacements", replacements);
        return data;
    }

    /**
     * 容忍模型实际会发出的数组形状（对照 sandboxEditList.UnmarshalJSON）：
     * 数组本体、单个对象、或序列化成 JSON 字符串的数组。
     */
    static List<SandboxEdits.SandboxEdit> parseEdits(JsonNode node) {
        List<SandboxEdits.SandboxEdit> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        JsonNode array = node;
        if (node.isTextual()) {
            // 字符串形态：按 JSON 再解析一层
            try {
                array = new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.asText());
            } catch (Exception e) {
                throw new SandboxEdits.EditException("edits must be an array of {old_string, new_string} objects");
            }
        }
        if (array == null) {
            throw new SandboxEdits.EditException("edits must be an array of {old_string, new_string} objects");
        }
        if (array.isArray()) {
            for (JsonNode item : array) {
                out.add(parseSingle(item));
            }
            return out;
        }
        if (array.isObject()) {
            out.add(parseSingle(array));
            return out;
        }
        throw new SandboxEdits.EditException("edits must be an array of {old_string, new_string} objects");
    }

    private static SandboxEdits.SandboxEdit parseSingle(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new SandboxEdits.EditException("edits must be an array of {old_string, new_string} objects");
        }
        return new SandboxEdits.SandboxEdit(
                item.path("old_string").asText(""),
                item.path("new_string").asText(""),
                item.path("replace_all").asBoolean(false));
    }

    private static ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }
}
