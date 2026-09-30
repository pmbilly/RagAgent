package com.ragagent.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.agent.domain.AgentStep;
import com.ragagent.agent.domain.ToolCall;
import com.ragagent.common.llm.ToolResult;

/**
 * 工具结果的客户端/存储投影。
 *
 * <p>纯函数，无状态。</p>
 */
public final class ToolResultPersist {

    private ToolResultPersist() {}

    /** SSE 回放 / DB 存储前丢弃的大块 Data 键。 */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS = Map.of(
            "knowledge_chunks_list", List.of("chunks"),
            "grep_results", List.of("chunk_results"));

    /**
     * 按工具丢二进制/重复大块。stdout/stderr 保留
     * （另行压缩），历史回放仍能渲染终端卡片。
     */
    private static final Map<String, List<String>> PERSIST_STRIP_FIELDS_BY_TOOL = Map.of(
            ToolDefinitions.TOOL_READ_FILE, List.of("content", "content_base64", "instructions"),
            ToolDefinitions.TOOL_SHELL_EXEC, List.of("content", "content_base64"),
            ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE, List.of("content", "content_base64"),
            ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, List.of("content", "content_base64"),
            ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, List.of("content", "content_base64"));

    /**
     * live SSE 的轻量丢弃表。UI 需要 stdout/stderr
     * 渲染终端卡片（工具侧已截断）；持久化仍用 persist 表。
     */
    private static final Map<String, List<String>> CLIENT_STRIP_FIELDS_BY_TOOL = PERSIST_STRIP_FIELDS_BY_TOOL;

    private static final int HISTORICAL_SANDBOX_OUTPUT_CHARS = 4 * 1024;

    /** data 带 display_type 时视为"模型面输出已另存"，原始 output 不再下发。 */
    public static boolean shouldOmitRawToolOutput(String toolName, Map<String, Object> data) {
        if (data == null) {
            return false;
        }
        Object displayType = data.get("display_type");
        return displayType instanceof String s && !s.isEmpty();
    }

    /** DB / SSE 回放安全副本。 */
    public static Map<String, Object> sanitizeToolDataForPersist(String toolName, Map<String, Object> data) {
        return sanitizeToolData(data, PERSIST_STRIP_FIELDS_BY_TOOL.get(toolName));
    }

    /** live SSE 客户端视图。 */
    static Map<String, Object> sanitizeToolDataForClient(String toolName, Map<String, Object> data) {
        List<String> omit = CLIENT_STRIP_FIELDS_BY_TOOL.get(toolName);
        if (omit == null) {
            omit = PERSIST_STRIP_FIELDS_BY_TOOL.get(toolName);
        }
        return sanitizeToolData(data, omit);
    }

    private static Map<String, Object> sanitizeToolData(Map<String, Object> data, List<String> extraOmit) {
        if (data == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(data);
        String displayType = stringField(data, "display_type");
        List<String> strip = PERSIST_STRIP_FIELDS.get(displayType);
        if (strip != null) {
            for (String key : strip) {
                out.remove(key);
            }
        }
        if (extraOmit != null) {
            for (String key : extraOmit) {
                out.remove(key);
            }
        }
        return out;
    }

    /** 为 UI 组 stream / 持久化 metadata。 */
    public static Map<String, Object> sanitizeToolResultForClient(String toolName, ToolResult result) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (result == null) {
            return meta;
        }
        if (result.getData() != null) {
            meta.putAll(sanitizeToolDataForClient(toolName, result.getData()));
        }
        if (!shouldOmitRawToolOutput("", result.getData()) && !result.getOutput().isEmpty()) {
            meta.put("output", result.getOutput());
        }
        return meta;
    }

    /** 工具结果的短 SSE Content 字段。 */
    public static String streamContentForToolResult(String toolName, boolean success, String errMsg,
            Map<String, Object> data) {
        if (!success) {
            return errMsg == null ? "" : errMsg;
        }
        if (isSandboxContentTool(toolName)) {
            return compactShellExecHeadline(data);
        }
        if (shouldOmitRawToolOutput(toolName, data)) {
            return compactToolSummary(success, errMsg, data);
        }
        return "";
    }

    /** 剥掉 LLM 专用负载后落库。 */
    public static List<AgentStep> sanitizeAgentStepsForStorage(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        List<AgentStep> out = new java.util.ArrayList<>(steps.size());
        for (AgentStep step : steps) {
            AgentStep copy = new AgentStep();
            copy.setIteration(step.getIteration());
            copy.setThought(step.getThought());
            copy.setUserMessagesBefore(step.getUserMessagesBefore() == null ? null
                    : new java.util.ArrayList<>(step.getUserMessagesBefore()));
            copy.setIntermediateAnswer(step.isIntermediateAnswer());
            copy.setReasoningContent(step.getReasoningContent());
            copy.setTimestamp(step.getTimestamp());
            if (step.getToolCalls() == null || step.getToolCalls().isEmpty()) {
                copy.setToolCalls(step.getToolCalls());
                out.add(copy);
                continue;
            }
            List<ToolCall> toolCalls = new java.util.ArrayList<>(step.getToolCalls().size());
            for (ToolCall tc : step.getToolCalls()) {
                ToolCall copyTc = new ToolCall();
                copyTc.setId(tc.getId());
                copyTc.setName(tc.getName());
                copyTc.setArgs(tc.getArgs());
                copyTc.setReflection(tc.getReflection());
                copyTc.setDuration(tc.getDuration());
                copyTc.setTarget(tc.getTarget());
                copyTc.setProviderMetadata(tc.getProviderMetadata());
                if (tc.getResult() != null) {
                    ToolResult result = shallowCopy(tc.getResult());
                    if (isSandboxContentTool(tc.getName())) {
                        // display_type is for the live card; history still needs the
                        // command, exit, and a head+tail of the streams.
                        result.setOutput(compactHistoricalSandboxOutput(result.getOutput()));
                    } else if (shouldOmitRawToolOutput(tc.getName(), result.getData())) {
                        result.setOutput(compactToolSummary(result.isSuccess(), result.getError(), result.getData()));
                    }
                    result.setData(sanitizeToolDataForPersist(tc.getName(), result.getData()));
                    compactSandboxStreamFields(result.getData());
                    copyTc.setResult(result);
                }
                toolCalls.add(copyTc);
            }
            copy.setToolCalls(toolCalls);
            out.add(copy);
        }
        return out;
    }

    private static ToolResult shallowCopy(ToolResult src) {
        ToolResult r = new ToolResult();
        r.setSuccess(src.isSuccess());
        r.setOutput(src.getOutput());
        r.setError(src.getError());
        r.setData(src.getData());
        r.setImages(src.getImages() == null ? null : new java.util.ArrayList<>(src.getImages()));
        r.setOutputFiles(src.getOutputFiles() == null ? null : new java.util.ArrayList<>(src.getOutputFiles()));
        return r;
    }

    /** 历史回放时重建短工具消息。 */
    public static String compactToolOutputForHistory(String toolName, ToolResult result) {
        if (result == null) {
            return "";
        }
        if (isSandboxContentTool(toolName)) {
            String rebuilt = compactSandboxHistory(result);
            if (!rebuilt.isEmpty()) {
                return result.isSuccess() ? rebuilt : failedToolVisibleContent(rebuilt, result.getError());
            }
        }
        if (!result.isSuccess()) {
            return failedToolVisibleContent(result.getOutput(), result.getError());
        }
        if (!result.getOutput().isEmpty() && !shouldOmitRawToolOutput(toolName, result.getData())) {
            return result.getOutput();
        }
        return compactToolSummary(result.isSuccess(), result.getError(), result.getData());
    }

    private static boolean isSandboxContentTool(String toolName) {
        return ToolDefinitions.TOOL_SHELL_EXEC.equals(toolName)
                || ToolDefinitions.TOOL_READ_FILE.equals(toolName)
                || ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE.equals(toolName)
                || ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT.equals(toolName);
    }

    /**
     * 工具失败时保住 Output 里的 stdout/stderr
     * （Error 常常只是一行退出码 + 重试提示；流才是模型改参数的依据）。
     */
    private static String failedToolVisibleContent(String output, String errMsg) {
        String out = output == null ? "" : output.trim();
        String err = errMsg == null ? "" : errMsg.trim();
        if (out.isEmpty() && err.isEmpty()) {
            return "Error: tool call failed";
        }
        if (out.isEmpty()) {
            return "Error: " + err;
        }
        if (err.isEmpty() || out.contains(err)) {
            return out;
        }
        return out + "\n\nError: " + err;
    }

    private static String compactHistoricalSandboxOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= HISTORICAL_SANDBOX_OUTPUT_CHARS) {
            return output;
        }
        String marker = "\n...[historical tool output compacted]...\n";
        int kept = HISTORICAL_SANDBOX_OUTPUT_CHARS - marker.length();
        int head = kept / 4;
        int tail = kept - head;
        return output.substring(0, head) + marker + output.substring(output.length() - tail);
    }

    private static void compactSandboxStreamFields(Map<String, Object> data) {
        if (data == null) {
            return;
        }
        for (String key : List.of("stdout", "stderr")) {
            Object raw = data.get(key);
            if (!(raw instanceof String s) || s.isEmpty()) {
                continue;
            }
            data.put(key, compactHistoricalSandboxOutput(s));
        }
    }

    private static String compactSandboxHistory(ToolResult result) {
        if (result == null) {
            return "";
        }
        if (!result.getOutput().isEmpty() && !isOmittedHistoryPlaceholder(result.getOutput())) {
            return compactHistoricalSandboxOutput(result.getOutput());
        }
        String rebuilt = rebuildShellExecHistory(result.getData());
        if (!rebuilt.isEmpty()) {
            return rebuilt;
        }
        return compactHistoricalSandboxOutput(result.getOutput());
    }

    private static boolean isOmittedHistoryPlaceholder(String output) {
        return output != null && output.contains("omitted from history");
    }

    private static String compactShellExecHeadline(Map<String, Object> data) {
        int exit = intField(data, "exit_code");
        String cmd = stringField(data, "command");
        if (cmd.isEmpty()) {
            return String.format("shell_exec exit=%d", exit);
        }
        final int maxCmd = 240;
        if (cmd.length() > maxCmd) {
            cmd = cmd.substring(0, maxCmd) + "...";
        }
        return String.format("shell_exec exit=%d command=%s", exit, cmd);
    }

    private static String rebuildShellExecHistory(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        String stdout = stringField(data, "stdout");
        String stderr = stringField(data, "stderr");
        if (stdout.isEmpty() && stderr.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(String.format("shell_exec exit=%d", intField(data, "exit_code")));
        String cmd = stringField(data, "command");
        if (!cmd.isEmpty()) {
            b.append(String.format(" command=%s", cmd));
        }
        String wd = stringField(data, "work_dir");
        if (!wd.isEmpty()) {
            b.append(String.format(" work_dir=%s", wd));
        }
        b.append('\n');
        if (!stdout.isEmpty()) {
            b.append("## Stdout\n```\n").append(stdout);
            if (!stdout.endsWith("\n")) {
                b.append('\n');
            }
            b.append("```\n");
        }
        if (!stderr.isEmpty()) {
            b.append("## Stderr\n```\n").append(stderr);
            if (!stderr.endsWith("\n")) {
                b.append('\n');
            }
            b.append("```\n");
        }
        return compactHistoricalSandboxOutput(b.toString());
    }

    private static String compactToolSummary(boolean success, String errMsg, Map<String, Object> data) {
        if (!success) {
            return errMsg == null || errMsg.isEmpty() ? "Error: tool call failed" : "Error: " + errMsg;
        }
        switch (stringField(data, "display_type")) {
            case "knowledge_chunks_list" -> {
                String title = stringField(data, "knowledge_title");
                if (title.isEmpty()) {
                    title = stringField(data, "knowledge_id");
                }
                int fetched = intField(data, "fetched_chunks");
                int total = intField(data, "total_chunks");
                String q = stringField(data, "faq_question");
                if (!q.isEmpty()) {
                    return String.format("Loaded FAQ entry: %s (content omitted from history)", q);
                }
                if (!title.isEmpty() && total > 0) {
                    return String.format("Listed %d/%d chunks from %s (content omitted from history)",
                            fetched, total, title);
                }
                if (!title.isEmpty()) {
                    return String.format("Listed chunks from %s (content omitted from history)", title);
                }
            }
            case "grep_results" -> {
                int chunks = intField(data, "total_matches");
                int docs = intField(data, "document_count");
                if (docs == 0) {
                    docs = intField(data, "result_count");
                }
                if (chunks > 0) {
                    return String.format(
                            "Keyword search found %d matching chunks across %d document(s) (details omitted from history)",
                            chunks, docs);
                }
            }
            case "search_results" -> {
                int count = intField(data, "result_count");
                if (count == 0) {
                    count = intField(data, "count");
                }
                if (count > 0) {
                    return String.format("Semantic search returned %d result(s) (details omitted from history)", count);
                }
            }
            case "shell_exec" -> {
                String rebuilt = rebuildShellExecHistory(data);
                if (!rebuilt.isEmpty()) {
                    return rebuilt;
                }
                return compactShellExecHeadline(data);
            }
            case "write_sandbox_file" -> {
                String path = stringField(data, "path");
                int size = intField(data, "size");
                if (!path.isEmpty()) {
                    return String.format("Wrote %s (%d bytes)", path, size);
                }
            }
            case "edit_sandbox_file" -> {
                String path = stringField(data, "path");
                int size = intField(data, "size");
                int n = intField(data, "replacements");
                if (!path.isEmpty()) {
                    return String.format("Edited %s (%d replacement(s), %d bytes)", path, n, size);
                }
            }
            case "attachment_parsing" -> {
                int parsed = intField(data, "parsed_count");
                int skipped = intField(data, "skipped_count");
                if (skipped > 0) {
                    return String.format("Parsed %d attachment(s), %d skipped (still processing)", parsed, skipped);
                }
                if (parsed > 0) {
                    return String.format("Parsed %d attachment(s)", parsed);
                }
            }
            default -> {}
        }
        String displayType = stringField(data, "display_type");
        if (!displayType.isEmpty()) {
            return String.format("Tool completed (%s; payload omitted from history)", displayType);
        }
        return "Tool completed (payload omitted from history)";
    }

    /** 取字符串字段（null → ""，值 trim）。 */
    private static String stringField(Map<String, Object> data, String key) {
        if (data == null) {
            return "";
        }
        Object v = data.get(key);
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    /** 取整数字段（数字族归一，其余 → 0）。 */
    private static int intField(Map<String, Object> data, String key) {
        if (data == null) {
            return 0;
        }
        Object v = data.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
