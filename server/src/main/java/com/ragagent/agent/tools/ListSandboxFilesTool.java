package com.ragagent.agent.tools;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * list_sandbox_files：会话可读目录的只读枚举（对照 Go {@code sandbox_ls.go}，逐字移植）。
 *
 * <p>会话作用域：sandbox 路径来自 {@link ToolExecContext#getSessionId()}，模型不能传任意
 * session id。路径护栏：path 必须落在 /workspace 下；缺省列产物输出目录。没有 sandbox 时
 * 返回空列表加提示（不是错误），模型可以先调 skill。</p>
 */
public class ListSandboxFilesTool extends BaseTool {

    /** 单次列出的条目上限（对照 defaultListSandboxMaxEntries / maxListSandboxMaxEntries）。 */
    static final int DEFAULT_LIST_SANDBOX_MAX_ENTRIES = 200;
    static final int MAX_LIST_SANDBOX_MAX_ENTRIES = 500;

    private static final String DESCRIPTION =
            "List files under /workspace when no shell executor is available. Omitted path lists the artifact output directory. "
                    + "Use known paths directly with read_file; list only to discover unknown files. Results are bounded by max_entries. "
                    + "An unprovisioned session returns an empty listing.";

    private static final String SCHEMA_JSON =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Optional absolute sandbox path to list, under /workspace. Defaults to the session's artifact output directory.\"},"
                    + "\"max_entries\":{\"type\":\"integer\",\"description\":\"Optional cap on the number of entries returned. Defaults to 200, hard-capped at 500. Use a smaller value when you only need to check whether a specific file exists.\"}},"
                    + "\"additionalProperties\":false}";

    private final SandboxFileSource source;

    /** 构造时 source 不得为 null：后端不支持目录枚举时应在装配层做特性开关（4.6）。 */
    public ListSandboxFilesTool(SandboxFileSource source) {
        super(ToolDefinitions.TOOL_LIST_SANDBOX_FILES, DESCRIPTION, SCHEMA_JSON);
        this.source = source;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String pathArg = args.path("path").asText("");
        int maxEntriesIn = args.path("max_entries").asInt(0);

        if (source == null) {
            return failure("sandbox file inspection is not available in this deployment");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return failure("no session ID in context; list_sandbox_files must run inside an agent turn");
        }

        // 缺省扫描产物输出目录（ArtifactCollector 排空的同一目录）；显式路径也允许 /workspace/input。
        String targetDir = pathArg.strip();
        if (targetDir.isEmpty()) {
            targetDir = OutputLinks.artifactOutputDir();
        } else {
            targetDir = SandboxPaths.resolveWorkspacePath(targetDir);
        }
        String[] rootHit = matchingInspectableRoot(targetDir);
        if (rootHit[1] == null) {
            return failure(inspectablePathError(pathArg));
        }
        String rootDir = rootHit[0];

        int maxEntries = maxEntriesIn;
        if (maxEntries <= 0) {
            maxEntries = DEFAULT_LIST_SANDBOX_MAX_ENTRIES;
        }
        if (maxEntries > MAX_LIST_SANDBOX_MAX_ENTRIES) {
            maxEntries = MAX_LIST_SANDBOX_MAX_ENTRIES;
        }

        List<RemoteDirEntry> entries;
        try {
            entries = source.listSessionFiles(sessionId, targetDir);
        } catch (Exception e) {
            return failure("failed to list " + targetDir + ": " + e.getMessage());
        }
        if (entries == null) {
            entries = new ArrayList<>();
        }

        // 按路径稳定排序，后端不保序时多次调用返回同一分页窗口。
        entries = new ArrayList<>(entries);
        entries.sort((a, b) -> a.path().compareTo(b.path()));

        boolean truncated = false;
        if (entries.size() > maxEntries) {
            entries = entries.subList(0, maxEntries);
            truncated = true;
        }

        // 给 LLM 人读输出；机器可读部分进 Data。
        StringBuilder b = new StringBuilder();
        b.append("=== Sandbox listing: ").append(targetDir).append(" ===\n\n");
        if (entries.isEmpty()) {
            b.append("No files found under this path. Either nothing has been written here yet, or the sandbox has been reaped.\n");
        } else {
            b.append("Found ").append(entries.size()).append(" file(s)");
            if (truncated) {
                b.append(" (truncated to ").append(maxEntries).append("; increase max_entries to see more)");
            }
            b.append(":\n\n");
            for (RemoteDirEntry e : entries) {
                b.append("- ").append(e.path()).append(" (size=").append(e.size())
                        .append(", modified=").append(formatSandboxModTime(e.modTime())).append(")\n");
            }
        }

        List<Map<String, Object>> items = new ArrayList<>(entries.size());
        for (RemoteDirEntry e : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", e.name());
            item.put("path", e.path());
            item.put("size", e.size());
            item.put("modified_at", formatSandboxModTime(e.modTime()));
            items.add(item);
        }

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(b.toString());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sessionId);
        data.put("path", targetDir);
        data.put("root", rootDir);
        data.put("entries", items);
        data.put("count", items.size());
        data.put("truncated", truncated);
        r.setData(data);
        return r;
    }

    /**
     * mod time 的 RFC3339 渲染（对照 formatSandboxModTime）。零时间渲染空串。
     */
    static String formatSandboxModTime(Instant t) {
        if (RemoteDirEntry.isZeroTime(t)) {
            return "";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(t);
    }

    /**
     * list_sandbox_files 与 read_file 的可读根 allowlist：会话 workspace，仅此而已
     * （对照 sandboxInspectableRoots）。最具体的排前面，路径命中时报出的根仍指向
     * 产物/附件树。
     */
    static String[] sandboxInspectableRoots() {
        return new String[] {
                OutputLinks.artifactOutputDir(),
                SandboxPaths.SESSION_INPUT_ROOT,
                SandboxPaths.SESSION_WORKSPACE_ROOT,
        };
    }

    static String inspectableRootsDescription() {
        return SandboxPaths.SESSION_WORKSPACE_ROOT;
    }

    /**
     * 拒绝路径的解释（对照 inspectablePathError）。skill 镜像路径是最常见误用：
     * 模型在 read_file 的环境段看到 /opt/weknora/tenant/skills/<name> 后拿它来 ls。
     */
    static String inspectablePathError(String requested) {
        String base = "this tool only lists/reads /workspace. path " + quoteGo(requested == null ? "" : requested)
                + " is outside that scope";
        String cleaned = GoPath.clean(requested == null ? "" : requested);
        SandboxPaths.ImageSkill hit = SandboxPaths.skillNameFromImagePath(cleaned);
        if (hit != null && hit.inImage() && !hit.name().isEmpty()) {
            return base + ". Use read_file(path=" + quoteGo("skill://" + hit.name() + "/SKILL.md")
                    + ") for package instructions and file discovery. Do not ls the whole installed dependency tree.";
        }
        return base + ". Use read_file with a listed skill:// resource for skill packages.";
    }

    static String relativeSkillFileFromImagePath(String clean, String skillName) {
        String dir = SandboxPaths.skillDirFor(skillName);
        if (dir == null || clean.equals(dir)) {
            return "";
        }
        String prefix = dir + "/";
        if (clean.startsWith(prefix)) {
            return clean.substring(prefix.length());
        }
        return "";
    }

    /**
     * 返回包含 clean 的 allowlist 根（对照 matchingInspectableRoot）。[0]=根，[1]=非 null
     * 表示命中（Go 的 (string, bool) 二元组）。
     */
    static String[] matchingInspectableRoot(String clean) {
        for (String root : sandboxInspectableRoots()) {
            if (SandboxPaths.isUnderRoot(clean, root)) {
                return new String[] {root, root};
            }
        }
        return new String[] {"", null};
    }

    /** Go %q 的普通串形态。 */
    static String quoteGo(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static ToolResult failure(String error) {
        ToolResult r = new ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }
}
