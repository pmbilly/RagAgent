package com.ragagent.retrieval.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.artifact.ArtifactReferenceRewriter.Artifact;

/**
 * 产物引用改写的字节契约：期望值录自 Go `go test -overlay` 探针
 * （internal/handler/session/artifact_reference.go 的 rewriteArtifactReferences，
 * 2026-09-22），fixture 在 {@code contracts/w5g3c-artifacts.tsv}（换行转义）。
 * 输入序列与 Go 探针一致（键序即用例序）。
 */
class ArtifactReferenceRewriterTest {

    private static final List<Artifact> ARTS = List.of(
            new Artifact("市场画像评分.html", "resource://aaaaaaaaaaaaaaaaaaaaaaaa"),
            new Artifact("chart data (v2).png", "sandbox:chart data (v2).png"),
            new Artifact("dup.txt", "resource://bbbbbbbbbbbbbbbbbbbbbbbb"));

    private record Case(String key, String input, String expected) {}

    private static final List<Case> CASES = List.of(
            caseOf("img_bare", "![评分](市场画像评分.html)\n\n正文"),
            caseOf("img_sandbox_prefix", "![评分](sandbox:市场画像评分.html)"),
            caseOf("img_sandbox_slash", "![评分](sandbox://市场画像评分.html)"),
            caseOf("img_output_path", "![评分](./output/市场画像评分.html)"),
            caseOf("img_ws_path", "![评分](/workspace/output/市场画像评分.html)"),
            caseOf("img_percent_encoded",
                    "![评分](%E5%B8%82%E5%9C%BA%E7%94%BB%E5%83%8F%E8%AF%84%E5%88%86.html)"),
            caseOf("link_bare_untouched", "[文档](README.md)"),
            caseOf("link_sandbox_prefix", "[报表](sandbox:市场画像评分.html)"),
            caseOf("http_untouched", "[site](https://example.com/a.html)"),
            caseOf("resource_untouched", "![x](resource://cccccccccccccccccccccccc)"),
            caseOf("unknown_name_untouched", "![x](不存在.html)"),
            caseOf("space_name", "![c](chart data (v2).png)"),
            caseOf("dup_keeps_first", "![d](dup.txt)"),
            caseOf("code_fence_untouched", "```\n![x](市场画像评分.html)\n```"),
            caseOf("inline_code_untouched", "看 `![x](市场画像评分.html)` 这个"),
            caseOf("mixed_doc", "# 报告\n\n![评分](市场画像评分.html) 与 [docs](README.md)\n\n```go\n// ![x](市场画像评分.html)\n```\n完"),
            caseOf("title_suffix", "![评分](市场画像评分.html \"标题\")"),
            caseOf("angle_bracket", "![评分](<市场画像评分.html>)"),
            caseOf("empty_content", ""),
            caseOf("no_artifacts", "![评分](市场画像评分.html)"));

    private static Case caseOf(String key, String input) {
        return new Case(key, input, unescape(fixtures().get(key)));
    }

    private static Map<String, String> fixtures() {
        try (var in = ArtifactReferenceRewriterTest.class
                .getResourceAsStream("/contracts/w5g3c-artifacts.tsv")) {
            Map<String, String> map = new HashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .split("\n", -1)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    map.put(line.substring(0, tab), unescape(line.substring(tab + 1)));
                }
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rewriteMatchesGoRecordings() {
        for (Case c : CASES) {
            List<Artifact> arts = c.key().equals("no_artifacts")
                    ? List.of()
                    : ARTS;
            String actual = ArtifactReferenceRewriter.rewriteArtifactReferences(
                    c.input(), arts, null);
            assertEquals(c.expected(), actual, "mismatch: " + c.key());
        }
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
