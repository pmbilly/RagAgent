package com.ragagent.agent.tools;

import java.util.Arrays;

/**
 * read_file 的 workspace 读取适配器（对照 Go {@code workspace_reader.go} 的
 * workspaceFileReader / renderFilePage / paginateSandboxFile / oversizedSandboxFileResult，
 * 逐字移植）。它是 read_file 的私有 source 适配器，不是单独注册的工具；
 * 持有 workspace 缓存与路径守卫。
 */
final class WorkspaceFileReader {

    /** 一次调用能摆到模型面前的文件量；这是上下文界不是 I/O 界（对照 defaultReadSandboxMaxBytes）。 */
    static final long DEFAULT_READ_SANDBOX_MAX_BYTES = 64 * 1024;
    static final long MAX_READ_SANDBOX_MAX_BYTES = 64 * 1024;
    /** 一页的行数上限（对照 defaultReadSandboxMaxLines）。 */
    static final int DEFAULT_READ_SANDBOX_MAX_LINES = 2000;
    /** 对照 readSandboxPageOverhead：页头、围栏与续读提示的余量。 */
    static final int READ_SANDBOX_PAGE_OVERHEAD = 512;
    /**
     * 能为了翻页而下载的文件上限（对照 maxReadSandboxDownloadBytes = maxSandboxFileBytes）：
     * 与 write_sandbox_file 追加能建出的文件一致——能写不能读就是死路。
     */
    static final long MAX_READ_SANDBOX_DOWNLOAD_BYTES = WriteSandboxFileTool.MAX_SANDBOX_FILE_BYTES;

    private final SandboxFileSource source;
    private ReadCacheEntry cached;

    WorkspaceFileReader(SandboxFileSource source) {
        this.source = source;
    }

    private static final class ReadCacheEntry {
        final String sessionId;
        final String path;
        final long size;
        final java.time.Instant modTime;
        final long epoch;
        final byte[] data;

        ReadCacheEntry(String sessionId, String path, long size, java.time.Instant modTime, long epoch, byte[] data) {
            this.sessionId = sessionId;
            this.path = path;
            this.size = size;
            this.modTime = modTime;
            this.epoch = epoch;
            this.data = data;
        }
    }

    /**
     * 读文件字节，无变化迹象时复用上一次下载（对照 readWithCache）。沙箱后端没有 range read，
     * 不缓存则每页都全量重拉；单槽位把持有量限制在一个文件——工具实例只活一轮 agent。
     */
    byte[] readWithCache(String sessionId, String filePath, RemoteStatEntry stat) throws Exception {
        long epoch = FileMutationQueue.sandboxMutationEpoch();

        ReadCacheEntry hit;
        synchronized (this) {
            hit = cached;
        }

        if (hit != null && hit.sessionId.equals(sessionId) && hit.path.equals(filePath)
                && hit.size == stat.size() && hit.modTime.equals(stat.modTime()) && hit.epoch == epoch) {
            return hit.data;
        }

        byte[] data = source.readSessionFile(sessionId, filePath);

        synchronized (this) {
            cached = new ReadCacheEntry(sessionId, filePath, stat.size(), stat.modTime(), epoch, data);
        }

        return data;
    }

    /** workspace 分支主体（对照 workspaceFileReader.read）。 */
    com.ragagent.agent.domain.ToolResult read(ToolRequest request, ReadFileTool.ReadFileInput input) {
        if (source == null) {
            return failure("sandbox file inspection is not available in this deployment");
        }

        String trimmed = input.path() == null ? "" : input.path().strip();
        if (trimmed.isEmpty()) {
            return failure("path is required; use a known file path under /workspace");
        }

        String sessionId = request.sessionId();
        if (sessionId.isEmpty()) {
            return failure("no session ID in context; workspace file reads must run inside an agent turn");
        }

        // 路径必须落在某个可读根下——与 list_sandbox_files 一致，模型看到一致的可达面。
        String clean = SandboxPaths.resolveWorkspacePath(trimmed);
        String[] rootHit = ListSandboxFilesTool.matchingInspectableRoot(clean);
        if (rootHit[1] == null) {
            return failure(ListSandboxFilesTool.inspectablePathError(input.path()));
        }
        String rootDir = rootHit[0];

        RemoteStatEntry stat;
        try {
            stat = source.statSessionFile(sessionId, clean);
        } catch (Exception e) {
            return failure("failed to inspect " + clean + " before reading: " + e.getMessage());
        }
        if (stat == null) {
            return failure("file not found: " + clean);
        }
        if (stat.isDir()) {
            return failure("path is a directory, not a file: " + clean);
        }
        // 目录守卫是字符串前缀测试，分辨不了指向别处的 symlink；后端 stat 最后一段不跟随
        // 链接，所以这里拒绝命名链接的路径。路径中段的链接由内核解析、这里抓不到——
        // 但那只是产物目录约定可绕，不是特权边界（读以 sandbox 账号跑）。
        if (!stat.isFile()) {
            return failure("path is not a regular file: " + clean + "; only files under "
                    + ListSandboxFilesTool.inspectableRootsDescription() + " can be read");
        }
        if (stat.size() > MAX_READ_SANDBOX_DOWNLOAD_BYTES) {
            return oversizedSandboxFileResult(sessionId, clean, rootDir, stat.size());
        }

        byte[] data;
        try {
            data = readWithCache(sessionId, clean, stat);
        } catch (Exception e) {
            return failure("failed to read " + clean + ": " + e.getMessage());
        }

        return renderFilePage(request, input, data, sessionId, clean, rootDir);
    }

    /**
     * workspace 与 skill 资源共用的页渲染（对照 renderFilePage）。内容只在 Output 出现一次；
     * 分页服从 registry 的输出预算。
     */
    static com.ragagent.agent.domain.ToolResult renderFilePage(
            ToolRequest request, ReadFileTool.ReadFileInput input, byte[] data,
            String sessionId, String clean, String rootDir) {
        long maxBytes = input.maxBytes();
        if (maxBytes <= 0) {
            maxBytes = DEFAULT_READ_SANDBOX_MAX_BYTES;
        }
        maxBytes = Math.min(maxBytes, MAX_READ_SANDBOX_MAX_BYTES);
        long total = data.length;
        // Stat 与 Read 之间文件可能变长。
        if (total > MAX_READ_SANDBOX_DOWNLOAD_BYTES) {
            return oversizedSandboxFileResult(sessionId, clean, rootDir, total);
        }

        boolean binary = ShellExecTool.isBinaryShellOutput(data);

        java.util.Map<String, Object> resultData = new java.util.LinkedHashMap<>();
        resultData.put("session_id", sessionId);
        resultData.put("path", clean);
        resultData.put("root", rootDir);
        resultData.put("size", (int) total);
        resultData.put("binary", binary);

        StringBuilder b = new StringBuilder();
        b.append("=== File: ").append(clean).append(" ===\n\n");

        if (binary) {
            b.append("size=").append(total).append(" bytes, returned=0 bytes\n");
            if (clean.startsWith("skill://")) {
                b.append("binary skill resource — text suppressed. Use the skill's execution guidance to process bundled binary files; reading a resource does not create a downloadable artifact.\n");
            } else {
                b.append("binary file — content suppressed; use the artifact attachment to download it.\n");
            }
            resultData.put("returned_bytes", 0);
            resultData.put("truncated", false);
            return success(b.toString(), resultData);
        }

        // registry 会把超出 rune 上限的输出从中间删掉、保头保尾——而续读提示就在尾部，
        // 会被"幸存"并背书一个中间被挖走的页。按 registry 同一预算定页的大小，
        // 这里的截断就永远不会触发。两个预算单位不同：64 KiB CJK ≈ 22k runes 放得下，
        // 64 KiB ASCII 65k runes 放不下。
        int maxRunes = Math.max(request.outputBudget() - READ_SANDBOX_PAGE_OVERHEAD, 1);
        SandboxFilePage page = paginateSandboxFile(
                new String(data, java.nio.charset.StandardCharsets.UTF_8),
                input.offset(), input.limit(), maxBytes, maxRunes);

        // 一行比整个字节预算还宽就翻不过去了：每次重试都落在同一行。直接点名逃生通道。
        if (page.lineTooLarge && clean.startsWith("skill://")) {
            com.ragagent.agent.domain.ToolResult r = new com.ragagent.agent.domain.ToolResult();
            r.setSuccess(false);
            r.setError("Line " + page.startLine + " exceeds this read's output budget. Increase max_bytes up to 65536 if lower; otherwise use a smaller skill resource. A skill:// address is not a shell path.");
            r.setData(resultData);
            return r;
        }
        if (page.lineTooLarge) {
            b.append("size=").append(total).append(" bytes, returned=0 bytes\n\n")
                    .append("[Line ").append(page.startLine).append(" is ").append(page.lineBytes)
                    .append(" bytes, over the ").append(maxBytes).append(" byte budget for one call. ")
                    .append("Use shell_exec: sed -n '").append(page.startLine).append("p' ").append(clean)
                    .append(" | head -c ").append(maxBytes).append("]\n");
            resultData.put("returned_bytes", 0);
            resultData.put("truncated", true);
            resultData.put("total_lines", page.totalLines);
            return success(b.toString(), resultData);
        }

        b.append("size=").append(total).append(" bytes, returned=")
                .append(page.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(" bytes\n");
        b.append("\n```\n");
        b.append(page.text);
        if (!page.text.isEmpty() && !page.text.endsWith("\n")) {
            b.append("\n");
        }
        b.append("```\n");

        // 续读提示挂在结果上而不是系统提示上：在可行动的那一刻到达模型，且带精确 offset。
        if (page.nextOffset > 0) {
            b.append("\n[Showing lines ").append(page.startLine).append('-').append(page.endLine)
                    .append(" of ").append(page.totalLines).append(". Use offset=").append(page.nextOffset)
                    .append(" to continue.]\n");
        } else if (page.startLine > 1) {
            b.append("\n[Showing lines ").append(page.startLine).append('-').append(page.endLine)
                    .append(" of ").append(page.totalLines).append(" — end of file.]\n");
        }

        resultData.put("returned_bytes", page.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        resultData.put("truncated", page.nextOffset > 0);
        resultData.put("total_lines", page.totalLines);
        resultData.put("start_line", page.startLine);
        resultData.put("end_line", page.endLine);
        if (page.nextOffset > 0) {
            resultData.put("next_offset", page.nextOffset);
        }

        return success(b.toString(), resultData);
    }

    /** 一页的窗口与续读所需信息（对照 sandboxFilePage）。 */
    record SandboxFilePage(
            String text,
            int startLine,
            int endLine,
            int totalLines,
            int nextOffset,
            boolean lineTooLarge,
            int lineBytes) {
    }

    /**
     * 返回从 1-based offset 开始、受行数 / 字节预算 / rune 预算约束的窗口
     * （对照 paginateSandboxFile）。页总在行边界断开——0x0A 不会出现在多字节序列中间，
     * 所以无论字节预算落在哪里都不会把 rune 切一半。
     */
    static SandboxFilePage paginateSandboxFile(String content, int offset, int limit, long maxBytes, int maxRunes) {
        if (offset <= 0) {
            offset = 1;
        }
        if (limit <= 0) {
            limit = DEFAULT_READ_SANDBOX_MAX_LINES;
        }
        if (maxRunes <= 0) {
            maxRunes = (int) maxBytes;
        }

        java.util.List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(content.split("\n", -1)));
        // 尾换行终结最后一行，不开新空行。少了这个 N 行文件会报 N+1、末页永远空白。
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines = lines.subList(0, lines.size() - 1);
        }
        int total = lines.size();

        if (offset > total) {
            return new SandboxFilePage("", offset, offset - 1, total, 0, false, 0);
        }

        int start = offset - 1;
        StringBuilder b = new StringBuilder();
        long usedBytes = 0;
        int usedRunes = 0;
        int end = start;
        for (; end < total && end - start < limit; end++) {
            // Go len() 是字节数：预算按 UTF-8 字节计（切行保证 rune 安全，但预算不是 rune）
            long costBytes = lines.get(end).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            int costRunes = lines.get(end).codePointCount(0, lines.get(end).length()) + 1;
            if (usedBytes + costBytes > maxBytes || usedRunes + costRunes > maxRunes) {
                if (end == start) {
                    return new SandboxFilePage("", offset, 0, total, 0, true,
                            lines.get(end).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                }
                break;
            }
            b.append(lines.get(end));
            b.append('\n');
            usedBytes += costBytes;
            usedRunes += costRunes;
        }

        int nextOffset = end < total ? end + 1 : 0;
        return new SandboxFilePage(b.toString(), offset, end, total, nextOffset, false, 0);
    }

    /** 超限文件的统一结果（对照 oversizedSandboxFileResult：文件未被下载）。 */
    static com.ragagent.agent.domain.ToolResult oversizedSandboxFileResult(
            String sessionId, String filePath, String rootDir, long size) {
        String output = "=== Sandbox file too large to read: " + filePath + " ===\n\n"
                + "size=" + size + " bytes, limit=" + MAX_READ_SANDBOX_DOWNLOAD_BYTES + " bytes, returned=0 bytes\n\n"
                + "The file was not downloaded. Use shell_exec with sed -n, head, tail, grep, or awk "
                + "to inspect only the relevant text section. Binary files remain available through the artifact attachment.\n";
        com.ragagent.agent.domain.ToolResult r = new com.ragagent.agent.domain.ToolResult();
        r.setSuccess(false);
        r.setError(output);
        r.setOutput(output);
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("session_id", sessionId);
        data.put("path", filePath);
        data.put("root", rootDir);
        data.put("size", size);
        data.put("limit", MAX_READ_SANDBOX_DOWNLOAD_BYTES);
        data.put("returned_bytes", 0);
        data.put("truncated", true);
        data.put("read_refused", true);
        r.setData(data);
        return r;
    }

    private static com.ragagent.agent.domain.ToolResult failure(String error) {
        com.ragagent.agent.domain.ToolResult r = new com.ragagent.agent.domain.ToolResult();
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    private static com.ragagent.agent.domain.ToolResult success(String output, java.util.Map<String, Object> data) {
        com.ragagent.agent.domain.ToolResult r = new com.ragagent.agent.domain.ToolResult();
        r.setSuccess(true);
        r.setOutput(output);
        r.setData(data);
        return r;
    }
}
