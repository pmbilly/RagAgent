package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link SlugFuzzy} 的纯函数测试（对照 Go
 * internal/application/service/slug_fuzzy.go 与 wiki_page.go 的
 * {@code rewriteDeadWikiLinks}）。
 *
 * <p>Go 侧没有 slug_fuzzy_test.go；这里的用例按 Go 注释里的三个杠杆
 * （display 反查 / 归一化相等 / bigram Jaccard）逐条设计。</p>
 */
class SlugFuzzyTest {

    // ──────────────────── normalizeSlugForCompare ────────────────────

    /** 折叠连字符、下划线与大小写；CJK 原样保留 */
    @Test
    void normalizeSlugForCompare() {
        assertThat(SlugFuzzy.normalizeSlugForCompare("shang-hai-tower"))
                .isEqualTo("shanghaitower");
        assertThat(SlugFuzzy.normalizeSlugForCompare("ShangHai_Tower"))
                .isEqualTo("shanghaitower");
        assertThat(SlugFuzzy.normalizeSlugForCompare("entity/上海中心"))
                .isEqualTo("entity/上海中心");
        assertThat(SlugFuzzy.normalizeSlugForCompare("---___")).isEmpty();
    }

    // ──────────────────── slugCharBigrams / jaccard ────────────────────

    /** 单字符退化成 1-gram（对照 Go {@code slugCharBigrams}） */
    @Test
    void slugCharBigramsDegradesToUnigram() {
        assertThat(SlugFuzzy.slugCharBigrams("a")).containsExactly("a");
        assertThat(SlugFuzzy.slugCharBigrams("ab")).containsExactly("ab");
        assertThat(SlugFuzzy.slugCharBigrams("abc")).containsExactly("ab", "bc");
        assertThat(SlugFuzzy.slugCharBigrams("")).isEmpty();
        assertThat(SlugFuzzy.slugCharBigrams(null)).isEmpty();
    }

    /** 按<b>码点</b>切分：增补平面字符不被拆成代理项 */
    @Test
    void slugCharBigramsSplitsByCodePoint() {
        // U+1F600 是一个码点，两个 char
        String emoji = "😀";
        assertThat(SlugFuzzy.slugCharBigrams(emoji)).containsExactly(emoji);
    }

    /** 对照 Go {@code searchutil.Jaccard}：两边都空返回 0（不是 1） */
    @Test
    void jaccard() {
        assertThat(SlugFuzzy.jaccard(Set.of(), Set.of())).isZero();
        assertThat(SlugFuzzy.jaccard(Set.of("a"), Set.of("a"))).isEqualTo(1.0);
        assertThat(SlugFuzzy.jaccard(Set.of("a"), Set.of("b"))).isZero();
        // {ab,bc} vs {ab,bc,cd} → 交 2 / 并 3
        assertThat(SlugFuzzy.jaccard(Set.of("ab", "bc"), Set.of("ab", "bc", "cd")))
                .isEqualTo(2.0 / 3.0);
        // 大小集合互换路径：结果与顺序无关
        assertThat(SlugFuzzy.jaccard(Set.of("ab"), Set.of("ab", "cd", "ef")))
                .isEqualTo(SlugFuzzy.jaccard(Set.of("ab", "cd", "ef"), Set.of("ab")));
    }

    // ──────────────────── resolveDeadSlug ────────────────────

    /** 本来就是活跃 slug → 原样返回（Go L100-103 的 no-op 成功） */
    @Test
    void resolveDeadSlugAcceptsLiveSlug() {
        assertThat(SlugFuzzy.resolveDeadSlug("entity/a", "", Set.of("entity/a"), Map.of()))
                .isEqualTo("entity/a");
    }

    /** 杠杆 1：display 文本反查（最常用的路径） */
    @Test
    void resolveDeadSlugViaDisplayText() {
        Map<String, String> titleToSlug = new LinkedHashMap<>();
        titleToSlug.put("上海中心大厦", "entity/shanghai-tower");
        assertThat(SlugFuzzy.resolveDeadSlug("entity/wrong-slug", "上海中心大厦",
                Set.of("entity/shanghai-tower"), titleToSlug))
                .isEqualTo("entity/shanghai-tower");
        // display 带多余空格也要能命中
        assertThat(SlugFuzzy.resolveDeadSlug("entity/wrong-slug", "  上海中心大厦  ",
                Set.of("entity/shanghai-tower"), titleToSlug))
                .isEqualTo("entity/shanghai-tower");
        // titleToSlug 指向的 slug 不活跃 → 不采用
        assertThat(SlugFuzzy.resolveDeadSlug("entity/wrong-slug", "上海中心大厦",
                Set.of("entity/other"), titleToSlug))
                .isNull();
    }

    /** 杠杆 2：连字符/大小写归一后相等（抓 LLM 插拼音分隔符的行为） */
    @Test
    void resolveDeadSlugViaNormalizedEquality() {
        assertThat(SlugFuzzy.resolveDeadSlug("entity/shang-hai-tower", "",
                Set.of("entity/shanghai-tower", "entity/unrelated"), Map.of()))
                .isEqualTo("entity/shanghai-tower");
    }

    /** 杠杆 3：bigram Jaccard ≥ 0.8（抓错别字 / 多一位字符） */
    @Test
    void resolveDeadSlugViaBigram() {
        // 真实用例：UUID 摘要 slug 多插了一位十六进制
        String real = "summary/07a20bb1-a662-47cf-9929-06fb5d5b5b5e";
        String mangled = "summary/07a20bb1-a662-47cf-9929-06fb14d5b14b14e";
        assertThat(SlugFuzzy.resolveDeadSlug(mangled, "", Set.of(real), Map.of()))
                .isEqualTo(real);
    }

    /** 阈值以下（差异过大）→ 不解析，返回 null（宁可不链也不链错） */
    @Test
    void resolveDeadSlugRejectsWeakCandidates() {
        assertThat(SlugFuzzy.resolveDeadSlug("entity/user-profile", "",
                Set.of("entity/user-permissions"), Map.of()))
                .isNull();
        assertThat(SlugFuzzy.resolveDeadSlug("entity/some-brand-new-topic", "",
                Set.of("entity/mongodb"), Map.of()))
                .isNull();
    }

    /** 边界：空 slug / 归一化后为空 / 空候选池 */
    @Test
    void resolveDeadSlugEdgeCases() {
        assertThat(SlugFuzzy.resolveDeadSlug("", "", Set.of("a"), Map.of())).isNull();
        assertThat(SlugFuzzy.resolveDeadSlug(null, "", Set.of("a"), Map.of())).isNull();
        // 全是连字符 → 归一化后为空 → 直接放弃（注意此时该 slug 必须<b>不是</b>活跃的，
        // 否则 Go L100-103 的「本来就活跃」快路径会先返回它）
        assertThat(SlugFuzzy.resolveDeadSlug("---", "", Set.of("entity/a"), Map.of())).isNull();
        assertThat(SlugFuzzy.resolveDeadSlug("---", "", Set.of("---"), Map.of()))
                .isEqualTo("---");
        assertThat(SlugFuzzy.resolveDeadSlug("entity/a", "", Set.of(), Map.of())).isNull();
    }

    // ──────────────────── rewriteDeadWikiLinks ────────────────────

    /** 回调返回 null / 与归一化 slug 相同 → 原样保留 */
    @Test
    void rewriteLeavesUntouchedWhenResolverDeclines() {
        String content = "See [[entity/a]] and [[entity/b|B]].";
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(content, (norm, display) -> null);
        assertThat(r.changed()).isFalse();
        assertThat(r.content()).isEqualTo(content);

        SlugFuzzy.RewriteResult same = SlugFuzzy.rewriteDeadWikiLinks(content,
                (norm, display) -> norm);
        assertThat(same.changed()).isFalse();
        assertThat(same.content()).isEqualTo(content);
    }

    /** 重写保留 display 文本原样；无 display 时不凭空造竖线 */
    @Test
    void rewritePreservesDisplayText() {
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(
                "A [[entity/x|显示 名称]] B [[entity/y]] C",
                (norm, display) -> norm.equals("entity/x") ? "entity/real-x"
                        : "entity/real-y");
        assertThat(r.changed()).isTrue();
        assertThat(r.content()).isEqualTo("A [[entity/real-x|显示 名称]] B [[entity/real-y]] C");
    }

    /** 归一化后为空的链接（如 {@code [[  ]]}）不参与重写 */
    @Test
    void rewriteSkipsBlankSlugs() {
        String content = "[[  ]] and [[entity/a]]";
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(content,
                (norm, display) -> "entity/target");
        assertThat(r.content()).isEqualTo("[[  ]] and [[entity/target]]");
        assertThat(r.changed()).isTrue();
    }

    /** 只有部分链接被重写时，未被重写的前缀必须逐字节保留 */
    @Test
    void rewriteKeepsUnchangedPrefixVerbatim() {
        String content = "前缀 [[entity/keep]] 中缀 [[entity/change]] 后缀";
        SlugFuzzy.RewriteResult r = SlugFuzzy.rewriteDeadWikiLinks(content,
                (norm, display) -> norm.equals("entity/change") ? "entity/fixed" : null);
        assertThat(r.content()).isEqualTo("前缀 [[entity/keep]] 中缀 [[entity/fixed]] 后缀");
    }

    /** 无链接 / 空内容 → 原样返回 */
    @Test
    void rewriteNoLinks() {
        assertThat(SlugFuzzy.rewriteDeadWikiLinks("plain text", (n, d) -> "x").changed())
                .isFalse();
        assertThat(SlugFuzzy.rewriteDeadWikiLinks("", (n, d) -> "x").content()).isEmpty();
    }

    /** 归一化后的 slug 传给回调（大小写 / 空格已折叠） */
    @Test
    void rewritePassesNormalizedSlugToResolver() {
        List<String> seen = new java.util.ArrayList<>();
        SlugFuzzy.rewriteDeadWikiLinks("[[Entity/Acme Corp|Acme]]", (norm, display) -> {
            seen.add(norm + "|" + display);
            return null;
        });
        assertThat(seen).containsExactly("entity/acme-corp|Acme");
    }
}
