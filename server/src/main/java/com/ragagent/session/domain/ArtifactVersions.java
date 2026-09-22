package com.ragagent.session.domain;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import com.ragagent.storage.fileserve.StoragePaths;

/**
 * 产物版本澄清（对照 Go {@code types/artifact_versions.go} 全文）。
 *
 * <p>不静默重定向旧 handle：用户可能在对比版本。当答案引用的是重新生成前的旧版本
 * 产物时，附加显式的「历史版本 / 本轮生成」对照；原文与其余链接保持原样。</p>
 *
 * <h2>匹配规则（逐条对照 Go）</h2>
 * <ul>
 *   <li>旧产物无 sourcePath，或两者 sourcePath 不同 → 跳过（不是同一文件的版本对）；</li>
 *   <li>新旧 URL 相同 → 跳过（同一文件，无需澄清）；</li>
 *   <li>同一 URL 已澄清过，或正文已直接包含该 URL → 跳过；</li>
 *   <li>新 URL 不是合法的 {@code resource://<22 位 handle>} → 跳过。</li>
 * </ul>
 */
public final class ArtifactVersions {

    private ArtifactVersions() {
    }

    public static String clarifyArtifactVersions(String content, List<MessageArtifact> current,
            List<MessageArtifact> previous, String language) {
        String oldLabel = "Referenced previous version";
        String newLabel = "File generated this turn";
        if (language != null && language.startsWith("zh")) {
            oldLabel = "正文引用的历史版本";
            newLabel = "本轮生成的文件";
        }
        Set<String> seen = new HashSet<>();
        for (MessageArtifact old : previous) {
            if (old == null) {
                continue;
            }
            for (MessageArtifact next : current) {
                if (next == null) {
                    continue;
                }
                String oldSourcePath = orEmpty(old.getSourcePath());
                String nextSourcePath = orEmpty(next.getSourcePath());
                String nextUrl = orEmpty(next.getUrl());
                String oldUrl = orEmpty(old.getUrl());
                if (oldSourcePath.isEmpty() || !oldSourcePath.equals(nextSourcePath)
                        || oldUrl.equals(nextUrl) || seen.contains(nextUrl)
                        || content.contains(nextUrl)) {
                    continue;
                }
                if (StoragePaths.parseResourcePath(nextUrl) == null) {
                    continue;
                }
                seen.add(nextUrl);
                String name = escapeMarkdownImageText(orEmpty(next.getFileName()));
                content += "\n\n" + oldLabel + ": ![" + name + "](" + oldUrl + ")\n\n"
                        + newLabel + ": ![" + name + "](" + nextUrl + ")";
            }
        }
        return content;
    }

    /**
     * 对照 Go {@code strings.NewReplacer("\\", "\\\\", "[", "\\[", "]", "\\]",
     * "(", "\\(", ")", "\\)", "\n", " ", "\r", " ")}：单遍替换语义——Java 链式
     * replace 的后续模式均不会匹配前序替换引入的反斜杠/空格，逐输入等价。
     */
    private static String escapeMarkdownImageText(String name) {
        return name.replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
