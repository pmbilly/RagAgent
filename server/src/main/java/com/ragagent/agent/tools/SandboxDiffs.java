package com.ragagent.agent.tools;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sandbox 文件变更的 +N/−M 统计、UI 进度 payload 与参数脱敏
 * （对照 Go {@code sandbox_diff.go}，逐字移植；另含 output_links.go 的
 * {@code sandboxOutputSnapshot}——消费 4.5a 预留的 {@link OutputLinks.SessionFileLister} 接缝）。
 */
public final class SandboxDiffs {

    /** 对照 sandboxFilePreviewMaxLines。 */
    static final int SANDBOX_FILE_PREVIEW_MAX_LINES = 10;

    private SandboxDiffs() {
    }

    /**
     * sandbox 文件变更的 +N/−M 计数单位：空串是 0，尾换行不多算一行
     * （对照 CountContentLines）。
     */
    public static int countContentLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 0;
        int idx = -1;
        while ((idx = s.indexOf('\n', idx + 1)) >= 0) {
            n++;
        }
        if (s.charAt(s.length() - 1) != '\n') {
            n++;
        }
        return n;
    }

    /** 对照 sandboxContentPreview：截前 10 行（尾空行不算）。 */
    static String sandboxContentPreview(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        List<String> lines = java.util.Arrays.asList(s.split("\n", -1));
        int end = lines.size();
        if (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        int stop = Math.min(end, SANDBOX_FILE_PREVIEW_MAX_LINES);
        return String.join("\n", lines.subList(0, stop));
    }

    /** 对照 isSandboxMutationTool。 */
    public static boolean isSandboxMutationTool(String name) {
        return ToolDefinitions.TOOL_WRITE_SANDBOX_FILE.equals(name)
                || ToolDefinitions.TOOL_EDIT_SANDBOX_FILE.equals(name);
    }

    /** 对照 attachSandboxDiffStats：data 为 null 时不动。 */
    public static void attachSandboxDiffStats(Map<String, Object> data, int added, int removed) {
        if (data == null) {
            return;
        }
        data.put("added_lines", added);
        data.put("removed_lines", removed);
    }

    /** 对照 sandboxEditDiffStats。 */
    public static long[] sandboxEditDiffStats(String content, List<SandboxEdits.SandboxEdit> edits) {
        int added = 0;
        int removed = 0;
        if (edits != null) {
            for (SandboxEdits.SandboxEdit e : edits) {
                java.util.List<Integer> found = SandboxEdits.indexAllNonOverlapping(content, e.oldString());
                int n = found.size();
                if (n == 0) {
                    continue;
                }
                if (!e.replaceAll()) {
                    n = 1;
                }
                removed += n * countContentLines(e.oldString());
                added += n * countContentLines(e.newString());
            }
        }
        return new long[] {added, removed};
    }

    /** 对照 editArgsLineStats（模型参数形状宽容解析）。 */
    static long[] editArgsLineStats(Map<String, Object> args) {
        int added = 0;
        int removed = 0;
        Object editsRaw = args == null ? null : args.get("edits");
        if (editsRaw instanceof List<?> edits) {
            for (Object raw : edits) {
                if (!(raw instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> mm = asStringKeyMap(m);
                String oldS = stringArg(mm, "old_string", "oldText");
                String newS = stringArg(mm, "new_string", "newText");
                removed += countContentLines(oldS);
                added += countContentLines(newS);
            }
        }
        if (added == 0 && removed == 0) {
            removed = countContentLines(stringArg(args, "old_string", "oldText"));
            added = countContentLines(stringArg(args, "new_string", "newText"));
        }
        return new long[] {added, removed};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> m) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() instanceof String s) {
                out.put(s, e.getValue());
            }
        }
        return out;
    }

    /** 对照 stringArg(m, keys...)：第一个命中且是 string 的键值。 */
    static String stringArg(Map<String, Object> m, String... keys) {
        if (m == null) {
            return "";
        }
        for (String key : keys) {
            if (m.get(key) instanceof String s) {
                return s;
            }
        }
        return "";
    }

    /**
     * write/edit 调用的 UI 进度 payload：path、运行的 +/− 行数与短预览。
     * 文件正文不上线（对照 SandboxFileCallProgress）。
     */
    public static Map<String, Object> sandboxFileCallProgress(String toolName, Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String path = stringArg(args, "path", "file_path");
        if (!path.isEmpty()) {
            out.put("path", path);
        }
        if (ToolDefinitions.TOOL_WRITE_SANDBOX_FILE.equals(toolName)) {
            String content = stringArg(args, "content");
            String mode = stringArg(args, "mode");
            if (!mode.isEmpty()) {
                out.put("mode", mode);
            }
            out.put("added_lines", countContentLines(content));
            out.put("removed_lines", 0);
            // Go len(string) 是字节数
            out.put("bytes", content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            String preview = sandboxContentPreview(content);
            if (!preview.isEmpty()) {
                out.put("preview", preview);
            }
        } else if (ToolDefinitions.TOOL_EDIT_SANDBOX_FILE.equals(toolName)) {
            long[] stats = editArgsLineStats(args);
            out.put("added_lines", (int) stats[0]);
            out.put("removed_lines", (int) stats[1]);
        }
        if (out.isEmpty()) {
            return null;
        }
        return out;
    }

    /**
     * 发给客户端的参数里剥掉 write/edit 文件正文（对照 SanitizeSandboxFileCallArgs）。
     * 活进度事件已经只带统计；这里兜住后面仍带完整 JSON 的 "tool hint" emit。
     */
    public static Map<String, Object> sanitizeSandboxFileCallArgs(String toolName, Map<String, Object> args) {
        if (!isSandboxMutationTool(toolName)) {
            return args;
        }
        if (args == null) {
            return null;
        }
        if (args.containsKey("content")) {
            return sandboxFileCallProgress(toolName, args);
        }
        if (args.containsKey("edits")) {
            return sandboxFileCallProgress(toolName, args);
        }
        return args;
    }

    /** 对照 formatSandboxDiffStat。 */
    public static String formatSandboxDiffStat(int added, int removed) {
        if (added > 0 && removed > 0) {
            return "+" + added + " -" + removed;
        }
        if (added > 0) {
            return "+" + added;
        }
        if (removed > 0) {
            return "-" + removed;
        }
        return "";
    }

    /**
     * 元数据快照检测输出文件（对照 output_links.go 的 sandboxOutputSnapshot）。
     * 检查尽力而为：绝不 provision sandbox、绝不下载文件。执行器没有列举能力时
     * 返回 ok=false（Go 的类型断言失败分支）。
     */
    public static java.util.Map<String, OutputLinks.DirEntry> sandboxOutputSnapshot(
            SandboxCommandExecutor executor, String sessionId) {
        if (!(executor instanceof OutputLinks.SessionFileLister lister)) {
            return null;
        }
        java.util.List<OutputLinks.DirEntry> entries;
        try {
            entries = lister.listSessionFiles(sessionId, OutputLinks.artifactOutputDir());
        } catch (Exception e) {
            return null;
        }
        java.util.Map<String, OutputLinks.DirEntry> files = new LinkedHashMap<>();
        if (entries != null) {
            for (OutputLinks.DirEntry entry : entries) {
                if (entry.file()) {
                    files.put(entry.path(), entry);
                }
            }
        }
        return files;
    }
}
