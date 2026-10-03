package com.ragagent.session.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 产物版本澄清纯函数。
 * 匹配规则、中英文标签、markdown 转义、seen/content 去重逐条钉住。
 */
class ArtifactVersionsTest {

    private static MessageArtifact artifact(String url, String sourcePath, String fileName) {
        MessageArtifact a = new MessageArtifact();
        a.setUrl(url);
        a.setSourcePath(sourcePath);
        a.setFileName(fileName);
        return a;
    }

    @Test
    void noClarificationWhenCurrentEmptyOrUrlsDirectlyReferenced() {
        // current 为空 → 原样
        assertThat(ArtifactVersions.clarifyArtifactVersions(
                "answer", List.of(), List.of(
                        artifact("resource://aaaaaaaaaaaaaaaaaaaaaa", "out/a.csv", "a.csv")),
                "zh-CN")).isEqualTo("answer");
        // 正文已直接包含 next URL → 跳过（不重复澄清）
        String url = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        String content = "see " + url;
        assertThat(ArtifactVersions.clarifyArtifactVersions(
                content,
                List.of(artifact(url, "out/b.csv", "b.csv")),
                List.of(artifact("resource://cccccccccccccccccccccc", "out/b.csv", "b-old.csv")),
                "zh-CN")).isEqualTo(content);
    }

    @Test
    void clarifiesOldNewPairWithChineseLabels() {
        String oldUrl = "resource://AAAAAAAAAAAAAAAAAAAAAA";
        String newUrl = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        String out = ArtifactVersions.clarifyArtifactVersions(
                "answer body",
                List.of(artifact(newUrl, "out/report.csv", "report.csv")),
                List.of(artifact(oldUrl, "out/report.csv", "report-v1.csv")),
                "zh-CN");
        assertThat(out).isEqualTo("answer body\n\n正文引用的历史版本: ![report.csv](" + oldUrl
                + ")\n\n本轮生成的文件: ![report.csv](" + newUrl + ")");
    }

    @Test
    void englishLabelsWhenLanguageNotZh() {
        String oldUrl = "resource://AAAAAAAAAAAAAAAAAAAAAA";
        String newUrl = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        String out = ArtifactVersions.clarifyArtifactVersions(
                "answer",
                List.of(artifact(newUrl, "out/report.csv", "report.csv")),
                List.of(artifact(oldUrl, "out/report.csv", "report.csv")),
                "en-US");
        assertThat(out).contains("Referenced previous version").contains("File generated this turn");
    }

    @Test
    void skipsWhenSourcePathDiffersOrEmptyOrSameUrlOrIllegalHandle() {
        String oldUrl = "resource://AAAAAAAAAAAAAAAAAAAAAA";
        String newUrl = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        // sourcePath 不同 → 跳过
        assertThat(ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact(newUrl, "out/other.csv", "x.csv")),
                List.of(artifact(oldUrl, "out/report.csv", "r.csv")), "zh-CN")).isEqualTo("c");
        // 旧产物 sourcePath 为空 → 跳过
        assertThat(ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact(newUrl, "out/report.csv", "x.csv")),
                List.of(artifact(oldUrl, "", "r.csv")), "zh-CN")).isEqualTo("c");
        // 新旧 URL 相同 → 跳过
        assertThat(ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact(oldUrl, "out/report.csv", "x.csv")),
                List.of(artifact(oldUrl, "out/report.csv", "r.csv")), "zh-CN")).isEqualTo("c");
        // 新 URL 非法（handle 长度不对）→ 跳过
        assertThat(ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact("resource://short", "out/report.csv", "x.csv")),
                List.of(artifact(oldUrl, "out/report.csv", "r.csv")), "zh-CN")).isEqualTo("c");
    }

    @Test
    void markdownImageTextIsEscaped() {
        String oldUrl = "resource://AAAAAAAAAAAAAAAAAAAAAA";
        String newUrl = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        String out = ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact(newUrl, "out/f.csv", "a(b)[c].csv")),
                List.of(artifact(oldUrl, "out/f.csv", "old.csv")),
                "zh-CN");
        assertThat(out).contains("a\\(b\\)\\[c\\].csv");
    }

    /** 同一 URL 只澄清一次；content 增长后 contains 也会拦截后续重复。 */
    @Test
    void deduplicatesPerUrl() {
        String oldUrl = "resource://AAAAAAAAAAAAAAAAAAAAAA";
        String newUrl = "resource://bbbbbbbbbbbbbbbbbbbbbb";
        String out = ArtifactVersions.clarifyArtifactVersions("c",
                List.of(artifact(newUrl, "out/f.csv", "f.csv"),
                        artifact(newUrl, "out/f.csv", "f.csv")),
                List.of(artifact(oldUrl, "out/f.csv", "f.csv")),
                "zh-CN");
        assertThat(out.split("正文引用的历史版本", -1).length).isEqualTo(2); // 1 次澄清 = 2 段
    }
}
