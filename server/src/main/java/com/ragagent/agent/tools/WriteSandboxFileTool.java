package com.ragagent.agent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * write_sandbox_file：不经过 shell heredoc 直接写会话 sandbox 文本文件
 * （对照 Go {@code sandbox_write.go}，逐字移植）。
 *
 * <p>路径护栏：写在 /workspace 下、绝不在 /workspace/input（暂存附件是用户的）。
 * /workspace/output 会被收集供下载，描述文案把成品留给它、草稿放到别处——这里只强制
 * /workspace/input 一半，其余是引导（"完成"是工具判断不了的）。</p>
 */
public class WriteSandboxFileTool extends BaseTool {

    /** 单次写入的绝对上限（对照 maxWriteSandboxBytes）：资源守卫，不是模型能发出的量。 */
    static final int MAX_WRITE_SANDBOX_BYTES = 256 * 1024;
    /** 一串 append 能积累出的文件上限（对照 maxSandboxFileBytes）。 */
    static final int MAX_SANDBOX_FILE_BYTES = 8 * 1024 * 1024;
    /** 每完成 token 折算的字节数（对照 bytesPerCompletionToken；刻意悲观，宁低勿高）。 */
    private static final int BYTES_PER_COMPLETION_TOKEN = 2;
    /** 调用开销预留（工具名/路径/JSON 脚手架/前导散文；对照 completionTokensReservedForCall）。 */
    private static final int COMPLETION_TOKENS_RESERVED_FOR_CALL = 512;

    static final String WRITE_MODE_OVERWRITE = "overwrite";
    static final String WRITE_MODE_APPEND = "append";

    private static final String DESCRIPTION_TEMPLATE =
            "Create, overwrite, or append a text file under /workspace, excluding /workspace/input.\n"
                    + "/workspace/output is collected for download, so it holds finished deliverables only; put drafts, scratch and\n"
                    + "intermediate files in any other directory under /workspace. Send both path and content (path first).\n"
                    + "Use edit_sandbox_file for small changes to an existing file. File content does not pass through shell quoting.\n"
                    + "Large files: first call uses mode=overwrite (default), subsequent calls use mode=append with only the next chunk. "
                    + "Keep calls in order and inspect the reported running byte count. A refused/truncated call wrote nothing; retry that chunk with complete JSON, never duplicate successful chunks.\n"
                    + "%s\n"
                    + "Binary content is not accepted. The result reports the absolute path and total size without echoing content.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Absolute or /workspace-relative sandbox path to write. Must sit under /workspace and must not sit under /workspace/input. Use /workspace/output for finished deliverables only; intermediate files belong elsewhere under /workspace.\"},"
                    + "\"content\":{\"type\":\"string\",\"description\":\"Text to write. In overwrite mode this is the full file; in append mode it is only the next chunk. Keep near the per-call size stated in the tool description so the response is not cut off. Do not send binary bytes.\"},"
                    + "\"mode\":{\"type\":\"string\",\"description\":\"How to apply content: 'overwrite' (default) replaces the file, 'append' adds to the end of an existing file. Use append to build a large file across several calls.\"}},"
                    + "\"required\":[\"path\",\"content\"],\"additionalProperties\":false}";

    private final SandboxFileSink sink;

    /**
     * completionTokens 是该轮的完成 token 预算——真正限制模型单次能发多少的是它。
     * 只影响描述里的尺寸引导，不强制（原因见 Go Execute 注释）。未知传 0。
     */
    public WriteSandboxFileTool(SandboxFileSink sink, int completionTokens) {
        super(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, buildDescription(completionTokens), SCHEMA_JSON);
        this.sink = sink;
    }

    static String buildDescription(int completionTokens) {
        return String.format(DESCRIPTION_TEMPLATE, writeSizeGuidance(writeBudgetBytes(completionTokens)));
    }

    /**
     * 本轮完成 token 预算下单次调用能带的最大 content 字节数（对照 writeBudgetBytes）。
     * 非正预算 = 调用方不知道，此时只适用硬上限。
     */
    static int writeBudgetBytes(int completionTokens) {
        if (completionTokens <= 0) {
            return MAX_WRITE_SANDBOX_BYTES;
        }
        int usable = completionTokens - COMPLETION_TOKENS_RESERVED_FOR_CALL;
        if (usable < 1) {
            usable = 1;
        }
        return Math.min(usable * BYTES_PER_COMPLETION_TOKEN, MAX_WRITE_SANDBOX_BYTES);
    }

    /**
     * 单次调用该带多少 content 及原因（对照 writeSizeGuidance）。数字是"一条响应能装下什么"
     * 的预报，不是工具强制的规则；越过后响应会从字符串中间断掉、调用被拒——这就是
     * 要遵守它的真理由。模型只被告知数字会当成拍脑袋；被告知自己的响应长度才是约束，
     * 它会拆文件而不是重试。
     */
    static String writeSizeGuidance(int maxBytes) {
        if (maxBytes >= MAX_WRITE_SANDBOX_BYTES) {
            return String.format(
                    "Keep `content` under about %d bytes per call. A larger file must be built "
                            + "with `mode: \"append\"`. The whole file is capped at %d bytes.",
                    maxBytes, MAX_SANDBOX_FILE_BYTES);
        }
        return String.format(
                "Keep `content` under about %d bytes per call, because that is what fits in one "
                        + "response at this agent's token budget. Go much past it and the response is cut "
                        + "off mid-string, which makes the call unusable and it will be refused. Split "
                        + "anything longer across several calls with `mode: \"append\"`. The whole file is "
                        + "capped at %d bytes.",
                maxBytes, MAX_SANDBOX_FILE_BYTES);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");
        String requestedMode = args.path("mode").asText("");

        if (sink == null) {
            return failure("sandbox file writing is not available in this deployment");
        }

        String trimmed = path.strip();
        if (trimmed.isEmpty()) {
            return failure("path is required; write under /workspace/output for artifacts or /workspace for scratch scripts");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return failure("no session ID in context; write_sandbox_file must run inside an agent turn");
        }

        String clean = SandboxPaths.resolveWorkspacePath(trimmed);
        String[] rootHit = matchingWritableRoot(clean);
        if (rootHit[1] == null) {
            return failure(workspaceWriteScopeError(path));
        }
        String rootDir = rootHit[0];

        // 这里只强制文件大小上限，刻意不强制描述里宣传的单次预算：内容到了这里说明已完整到达
        // （截断的响应在 act 层被拒、JSON 修复层也拒），按错误预报拒完整 payload 只会白扔已完成的工作。
        byte[] chunk = content.getBytes(StandardCharsets.UTF_8);
        if (chunk.length > MAX_SANDBOX_FILE_BYTES) {
            return failure(String.format(
                    "content is %d bytes, past the %d-byte file limit. Write the first part now "
                            + "and send the rest with mode=%s",
                    chunk.length, MAX_SANDBOX_FILE_BYTES, quoteGo(WRITE_MODE_APPEND)));
        }
        if (ShellExecTool.isBinaryShellOutput(new String(chunk, StandardCharsets.UTF_8))) {
            return failure("binary content is not accepted; write a text script and have it produce binary files under /workspace/output");
        }

        String modeErr = normalizeWriteModeMessage(requestedMode);
        if (!modeErr.isEmpty()) {
            return failure(modeErr);
        }
        String mode = normalizeWriteMode(requestedMode);

        // 读与写之间持有：append 读基线时兄弟调用正在写，会把不存在的字节追加进去。
        Runnable release = FileMutationQueue.lockSandboxFile(sessionId, clean);
        byte[] contentBytes;
        try {
            if (mode.equals(WRITE_MODE_APPEND)) {
                byte[] existing;
                try {
                    existing = readForAppend(sessionId, clean);
                } catch (AppendRefused e) {
                    return failure(e.getMessage());
                }
                if (existing.length + chunk.length > MAX_SANDBOX_FILE_BYTES) {
                    return failure(String.format(
                            "appending %d bytes would take %s past the %d-byte file limit (currently %d)",
                            chunk.length, clean, MAX_SANDBOX_FILE_BYTES, existing.length));
                }
                contentBytes = new byte[existing.length + chunk.length];
                System.arraycopy(existing, 0, contentBytes, 0, existing.length);
                System.arraycopy(chunk, 0, contentBytes, existing.length, chunk.length);
            } else {
                contentBytes = chunk;
            }

            try {
                sink.writeSessionWorkspaceFile(sessionId, clean, contentBytes);
            } catch (Exception e) {
                return failure("failed to write " + clean + ": " + e.getMessage());
            }
        } finally {
            release.run();
        }

        // 检查整个文件而非本次块：块边界可能落在源码中间，只有拼好的结果能判断。
        int added = SandboxDiffs.countContentLines(content);

        String hint = PythonSyntax.pythonScriptSyntaxHint(clean, new String(contentBytes, StandardCharsets.UTF_8),
                ToolDefinitions.TOOL_EDIT_SANDBOX_FILE);
        if (!hint.isEmpty()) {
            Map<String, Object> data = baseData(sessionId, clean, rootDir, contentBytes.length, mode);
            data.put("syntax_error", true);
            SandboxDiffs.attachSandboxDiffStats(data, added, 0);
            ToolResult r = new ToolResult();
            r.setSuccess(false);
            r.setError(hint);
            r.setOutput("=== Wrote sandbox file with syntax problems: " + clean + " ===\n\n" + hint + "\n");
            r.setData(data);
            return r;
        }

        String sizeLine = "bytes=" + contentBytes.length;
        if (mode.equals(WRITE_MODE_APPEND)) {
            sizeLine = "appended=" + chunk.length + ", total_bytes=" + contentBytes.length;
        }
        String stat = SandboxDiffs.formatSandboxDiffStat(added, 0);
        if (!stat.isEmpty()) {
            sizeLine = stat + ", " + sizeLine;
        }
        String output = "=== Wrote sandbox file: " + clean + " ===\n\n" + sizeLine + "\n";
        Map<String, Object> data = baseData(sessionId, clean, rootDir, contentBytes.length, mode);
        data.put("appended", chunk.length);
        SandboxDiffs.attachSandboxDiffStats(data, added, 0);
        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(output);
        r.setOutputFiles(OutputLinks.sandboxOutputLinks(clean));
        r.setData(data);
        return r;
    }

    private static Map<String, Object> baseData(String sessionId, String clean, String rootDir,
            int size, String mode) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("display_type", ToolDefinitions.TOOL_WRITE_SANDBOX_FILE);
        data.put("session_id", sessionId);
        data.put("path", clean);
        data.put("root", rootDir);
        data.put("name", SandboxPaths.base(clean));
        data.put("size", size);
        data.put("mode", mode);
        return data;
    }

    /** append 必须追加到的已有字节；文件缺失被拒而非悄悄创建。 */
    private byte[] readForAppend(String sessionId, String filePath) throws AppendRefused {
        RemoteStatEntry stat;
        try {
            stat = sink.statSessionFile(sessionId, filePath);
        } catch (Exception e) {
            throw new AppendRefused(String.format(
                    "cannot append to %s: it does not exist yet (%s). Write the first chunk with mode=%s, then append the rest",
                    filePath, String.valueOf(e.getMessage()), quoteGo(WRITE_MODE_OVERWRITE)));
        }
        if (stat == null) {
            throw new AppendRefused(String.format(
                    "cannot append to %s: it does not exist yet (%s). Write the first chunk with mode=%s, then append the rest",
                    filePath, "<nil>", quoteGo(WRITE_MODE_OVERWRITE)));
        }
        if (!stat.isFile()) {
            throw new AppendRefused("cannot append to " + filePath + ": it is not a regular file");
        }
        byte[] existing;
        try {
            existing = sink.readSessionFile(sessionId, filePath);
        } catch (Exception e) {
            throw new AppendRefused("cannot append to " + filePath
                    + ": reading the current contents failed: " + e.getMessage());
        }
        return existing;
    }

    private static final class AppendRefused extends RuntimeException {
        AppendRefused(String message) {
            super(message);
        }
    }

    /** 归一后的写模式（对照 normalizeWriteMode 的返回值侧）。 */
    static String normalizeWriteMode(String requested) {
        String lower = requested == null ? "" : requested.strip().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || lower.equals(WRITE_MODE_OVERWRITE)) {
            return WRITE_MODE_OVERWRITE;
        }
        if (lower.equals(WRITE_MODE_APPEND)) {
            return WRITE_MODE_APPEND;
        }
        return "";
    }

    /** 未知模式的模型可读消息（对照 normalizeWriteMode 的消息侧）。 */
    static String normalizeWriteModeMessage(String requested) {
        String lower = requested == null ? "" : requested.strip().toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || lower.equals(WRITE_MODE_OVERWRITE) || lower.equals(WRITE_MODE_APPEND)) {
            return "";
        }
        return String.format("unknown mode %s; use %s (default) or %s",
                quoteGo(requested), quoteGo(WRITE_MODE_OVERWRITE), quoteGo(WRITE_MODE_APPEND));
    }

    /**
     * 拒绝写/编辑路径的解释（对照 workspaceWriteScopeError）。这是工具作用域约定
     * （附件不进这些工具；脚本放 /workspace 下），不是特权检查。
     */
    static String workspaceWriteScopeError(String requested) {
        return String.format(
                "this tool only writes files under %s (not under %s, and not the directory roots themselves). path %s is outside that scope; use shell_exec for other locations",
                SandboxPaths.SESSION_WORKSPACE_ROOT, SandboxPaths.SESSION_INPUT_ROOT, quoteGo(requested));
    }

    /**
     * 包含 clean 的可写根（对照 matchingWritableRoot）：/workspace 之外、/workspace 本身、
     * 只读附件树之下都不行。
     */
    static String[] matchingWritableRoot(String clean) {
        if (!SandboxPaths.isUnderRoot(clean, SandboxPaths.SESSION_WORKSPACE_ROOT)
                || clean.equals(SandboxPaths.SESSION_WORKSPACE_ROOT)
                || clean.equals(SandboxPaths.SESSION_OUTPUT_ROOT)
                || SandboxPaths.isUnderRoot(clean, SandboxPaths.SESSION_INPUT_ROOT)) {
            return new String[] {"", null};
        }
        if (SandboxPaths.isUnderRoot(clean, SandboxPaths.SESSION_OUTPUT_ROOT)) {
            return new String[] {SandboxPaths.SESSION_OUTPUT_ROOT, SandboxPaths.SESSION_OUTPUT_ROOT};
        }
        return new String[] {SandboxPaths.SESSION_WORKSPACE_ROOT, SandboxPaths.SESSION_WORKSPACE_ROOT};
    }

    static String quoteGo(String s) {
        return ListSandboxFilesTool.quoteGo(s);
    }

    private static ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }
}
