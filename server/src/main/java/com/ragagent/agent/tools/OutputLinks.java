package com.ragagent.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * sandbox 输出文件的链接与快照（对照 Go {@code output_links.go}，纯函数部分逐字移植）。
 *
 * <p>shell 命令有没有产出文件，不靠解析任意命令或 stdout，而是对输出目录做
 * <b>元数据快照对比</b>。检查尽力而为：绝不 provision sandbox、绝不下载文件。</p>
 *
 * <p>链接形态（Go 实录）：{@code sandbox:<basename>}，名字里的 ASCII Markdown/URL
 * 分隔符按 path 段规则转义（空格 → %20、{@code [1](final)} → {@code %5B1%5D%28final%29}），
 * 非 ASCII 保持可读（{@code 比赛信息.pptx} 原样）；总字节预算 8KB。</p>
 *
 * <p>{@code sandboxOutputSnapshot} 消费 {@link SessionFileLister}——4.5c 的 shell_exec
 * 执行器实现这个切片后接入。</p>
 */
public final class OutputLinks {

    /** 输出目录（对照 skills.ArtifactOutputDir 的缺省值）。 */
    public static final String DEFAULT_ARTIFACT_OUTPUT_DIR = "/workspace/output";
    /** 链接列表的字节预算（对照 8*1024）。 */
    private static final int LINK_BUDGET_BYTES = 8 * 1024;

    private OutputLinks() {
    }

    /**
     * 输出目录解析（对照 skills.ArtifactOutputDir：env WEKNORA_SKILL_OUTPUT_DIR →
     * sandbox.ValidatedSessionOutputDir 校验（clean 后必须在 /workspace 下）→ 缺省 /workspace/output）。
     */
    public static String artifactOutputDir() {
        String v = System.getenv("WEKNORA_SKILL_OUTPUT_DIR");
        if (v != null && !v.strip().isEmpty()) {
            String clean = GoPath.clean(v.strip());
            if (clean.equals("/workspace") || clean.startsWith("/workspace/")) {
                return clean;
            }
        }
        return DEFAULT_ARTIFACT_OUTPUT_DIR;
    }

    /** sandbox 目录项的最小切片（对照 sandbox.RemoteDirEntry 的被用字段）。 */
    public record DirEntry(String path, boolean file, long size, Instant modTime) {
    }

    /** 会话文件列举切片（对照 executor 的 ListSessionFiles 能力，4.5c 接入）。 */
    public interface SessionFileLister {
        List<DirEntry> listSessionFiles(String sessionId, String dir) throws Exception;
    }

    /** 快照差异 → 链接（对照 changedOutputLinks：size 或 modTime 变化即视为变化，路径排序）。 */
    public static List<String> changedOutputLinks(Map<String, DirEntry> before, Map<String, DirEntry> after) {
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, DirEntry> e : after.entrySet()) {
            DirEntry old = before.get(e.getKey());
            if (old == null || old.size() != e.getValue().size()
                    || !old.modTime().equals(e.getValue().modTime())) {
                paths.add(e.getKey());
            }
        }
        paths.sort(String::compareTo);
        return sandboxOutputLinks(paths.toArray(new String[0]));
    }

    /** 路径 → sandbox: 链接（对照 sandboxOutputLinks；只认输出目录内的文件，预算内截断）。 */
    public static List<String> sandboxOutputLinks(String... paths) {
        List<String> links = new ArrayList<>();
        int bytes = 0;
        String outputPrefix = GoPath.clean(artifactOutputDir()) + "/";
        for (String rawPath : paths) {
            String filePath = GoPath.clean(rawPath);
            if (!filePath.startsWith(outputPrefix)) {
                continue;
            }
            String name = filePath.substring(filePath.lastIndexOf('/') + 1);
            // Unicode 保持可读；只转义 ASCII 的 Markdown/URL 分隔符
            StringBuilder escaped = new StringBuilder(name.length());
            name.codePoints().forEach(cp -> {
                if (cp >= 128) {
                    escaped.appendCodePoint(cp);
                } else {
                    escaped.append(goPathEscape((char) cp));
                }
            });
            String link = "sandbox:" + escaped;
            if (bytes + link.length() > LINK_BUDGET_BYTES) {
                break;
            }
            links.add(link);
            bytes += link.length();
        }
        return links;
    }

    /**
     * Go url.PathEscape 的单字符行为（encodePathSegment 模式）：保留
     * 字母数字与 {@code - _ . ~ $ & + , : ; = @ ?}，其余 ASCII 输出 %XX（大写十六进制）。
     * 实录：空格 → %20、[ → %5B、] → %5D、( → %28、) → %29。
     */
    static String goPathEscape(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return String.valueOf(c);
        }
        return switch (c) {
            case '-', '_', '.', '~', '$', '&', '+', ',', ':', ';', '=', '@', '?' -> String.valueOf(c);
            default -> String.format("%%%02X", (int) c);
        };
    }
}
