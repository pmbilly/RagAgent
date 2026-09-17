package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.wiki.service.WikiCrossLinker.LinkRef;

/**
 * {@link WikiLinkify} 的对等测试（对照 Go
 * internal/application/service/wiki_linkify_test.go，285 行，<b>全部用例逐条翻译</b>）。
 *
 * <p>Go 的表驱动子测试用 JUnit 5 的 {@link Nested} / {@link Test} 展开，
 * 保持用例名与断言内容可逐条对照。</p>
 */
class WikiLinkifyTest {

    private final WikiLinkify linkify = new WikiLinkify();

    private static LinkRef ref(String slug, String matchText) {
        return new LinkRef(slug, matchText);
    }

    // ── 基础 CJK 加链 ──

    @Test
    @DisplayName("TestLinkifyContent_BasicCJK：CJK 无词边界概念，直接加链")
    void basicCjk() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("我住在北京市", refs, "");
        assertThat(r.changed()).isTrue();
        assertThat(r.content()).isEqualTo("我住在[[beijing|北京]]市");
    }

    // ── 长名字赢过子串 ──

    @Test
    @DisplayName("TestLinkifyContent_LongerNameWinsOverSubstring：长度降序排序生效")
    void longerNameWinsOverSubstring() {
        List<LinkRef> refs = List.of(
                ref("beijing", "北京"),
                ref("bupt", "北京邮电大学"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("我就读于北京邮电大学", refs, "");
        assertThat(r.changed()).isTrue();
        assertThat(r.content()).contains("[[bupt|北京邮电大学]]");
        assertThat(r.content()).doesNotContain("[[beijing|");
    }

    // ── ASCII 词边界 ──

    @Test
    @DisplayName("TestLinkifyContent_ASCIIWordBoundary：词内不匹配，独立词匹配")
    void asciiWordBoundary() {
        List<LinkRef> refs = List.of(ref("ai", "AI"));

        // 不应匹配 "TRAINING" 或 "PAINT" 里的 "AI"
        WikiCrossLinker.LinkifyResult r1 = linkify.linkify("TRAINING and PAINT are words.", refs, "");
        assertThat(r1.changed()).isFalse();

        // 应当匹配独立的 "AI"
        WikiCrossLinker.LinkifyResult r2 = linkify.linkify("AI is cool.", refs, "");
        assertThat(r2.changed()).isTrue();
        assertThat(r2.content()).startsWith("[[ai|AI]] ");
    }

    // ── 幂等 / 既有链接跳过 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsExistingWikiLink：已有 [[slug|...]] 不重复包裹")
    void skipsExistingWikiLink() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("我住在[[beijing|北京]]市", refs, "");
        assertThat(r.changed()).isFalse();
    }

    // ── 围栏代码块 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsInsideFencedCode：正文出现处加链，代码块内不加")
    void skipsInsideFencedCode() {
        List<LinkRef> refs = List.of(ref("go", "Go"));
        String in = "Prose mentions Go.\n\n```go\nfunc Go() {}\n```\nAfter code.";
        WikiCrossLinker.LinkifyResult r = linkify.linkify(in, refs, "");
        assertThat(r.content()).contains("Prose mentions [[go|Go]].");
        assertThat(r.content()).doesNotContain("func [[go|Go]]");
    }

    // ── 行内代码 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsInsideInlineCode：行内代码内不加链")
    void skipsInsideInlineCode() {
        List<LinkRef> refs = List.of(ref("foo", "Foo"));

        // 唯一出现处在 `Foo` 内 —— 必须不加链
        WikiCrossLinker.LinkifyResult r1 = linkify.linkify("Use the `Foo` type.", refs, "");
        assertThat(r1.changed()).isFalse();

        // 正文在前、行内代码在后 —— 应链正文那个
        WikiCrossLinker.LinkifyResult r2 = linkify.linkify("Foo is a type. Use `Foo` here.", refs, "");
        assertThat(r2.changed()).isTrue();
        assertThat(r2.content()).startsWith("[[foo|Foo]] is");
        assertThat(r2.content()).doesNotContain("`[[foo|Foo]]`");
    }

    // ── Markdown 链接 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsInsideMarkdownLink：链接文本内不加链")
    void skipsInsideMarkdownLink() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        WikiCrossLinker.LinkifyResult r =
                linkify.linkify("参见 [北京](https://example.com/beijing)", refs, "");
        assertThat(r.changed()).isFalse();
    }

    // ── 只包裹第一处 ──

    @Test
    @DisplayName("TestLinkifyContent_OnlyFirstOccurrenceWrapped：每个 ref 至多一处")
    void onlyFirstOccurrenceWrapped() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("北京很大。北京有很多人。北京是首都。", refs, "");
        assertThat(r.changed()).isTrue();
        assertThat(countOf(r.content(), "[[beijing|北京]]")).isEqualTo(1);
        // 后续出现保持裸文本：共 3 处"北京"（含链接里的那一个）
        assertThat(countOf(r.content(), "北京")).isEqualTo(3);
    }

    // ── 自 slug 跳过 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsSelfSlug：渲染自身页面时不自链")
    void skipsSelfSlug() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("北京是首都", refs, "beijing");
        assertThat(r.changed()).isFalse();
    }

    // ── slug 已被链接则整条跳过 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsWhenSlugAlreadyUsed：slug 已出现则换别名也不再链")
    void skipsWhenSlugAlreadyUsed() {
        List<LinkRef> refs = List.of(
                ref("beijing", "北京"),
                ref("beijing", "京师"));
        WikiCrossLinker.LinkifyResult r =
                linkify.linkify("我在[[beijing|京师]]出差，后来去了北京。", refs, "");
        assertThat(r.changed()).isFalse();
    }

    // ── 多个互不相干的 ref ──

    @Test
    @DisplayName("TestLinkifyContent_MultipleRefsDisjoint：多个 ref 各自链一处")
    void multipleRefsDisjoint() {
        List<LinkRef> refs = List.of(
                ref("beijing", "北京"),
                ref("shanghai", "上海"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("我今天去北京，明天去上海。", refs, "");
        assertThat(r.changed()).isTrue();
        assertThat(r.content()).contains("[[beijing|北京]]");
        assertThat(r.content()).contains("[[shanghai|上海]]");
    }

    // ── 空 / 无匹配 ──

    @Test
    @DisplayName("TestLinkifyContent_EmptyOrNoMatch：空内容 / 空 refs / 无匹配都不改")
    void emptyOrNoMatch() {
        assertThat(linkify.linkify("", List.of(ref("x", "y")), "").changed()).isFalse();
        assertThat(linkify.linkify("hello", null, "").changed()).isFalse();
        assertThat(linkify.linkify("hello world", List.of(ref("x", "zzz")), "").changed()).isFalse();
    }

    // ── findFirstSafeMatch 的边界 ──

    @Test
    @DisplayName("TestFindFirstSafeMatch_BoundaryCases：第一处不安全时跳到第二处")
    void findFirstSafeMatchBoundaryCases() {
        String s = "See `AI` in docs. AI rocks.";
        WikiLinkify.ForbiddenResult forb = WikiLinkify.computeForbiddenSpans(s);
        int idx = WikiLinkify.findFirstSafeMatch(s, "AI", forb.spans());
        assertThat(idx).isEqualTo(s.indexOf("AI rocks"));
    }

    // ── 围栏代码的禁区 ──

    @Test
    @DisplayName("TestComputeForbiddenSpans_FencedCode：围栏内容被禁，前后正文不被禁")
    void computeForbiddenSpansFencedCode() {
        String s = "before\n```\nhidden\n```\nafter";
        WikiLinkify.ForbiddenResult r = WikiLinkify.computeForbiddenSpans(s);
        assertThat(r.spans()).isNotEmpty();

        int h = s.indexOf("hidden");
        assertThat(WikiLinkify.spanContains(r.spans(), h, h + "hidden".length())).isTrue();

        int b = s.indexOf("before");
        assertThat(WikiLinkify.spanContains(r.spans(), b, b + "before".length())).isFalse();

        int a = s.indexOf("after");
        assertThat(WikiLinkify.spanContains(r.spans(), a, a + "after".length())).isFalse();
    }

    // ── 收集已用 slug ──

    @Test
    @DisplayName("TestComputeForbiddenSpans_CollectsUsedSlugs：[[slug]] 与 [[slug|x]] 都记入 used")
    void computeForbiddenSpansCollectsUsedSlugs() {
        String s = "Refer to [[beijing|北京]] and [[shanghai]] and [[nope|x]].";
        WikiLinkify.ForbiddenResult r = WikiLinkify.computeForbiddenSpans(s);
        assertThat(r.used()).contains("beijing", "shanghai", "nope");
    }

    // ── 引用式链接 ──

    @Test
    @DisplayName("TestLinkifyContent_SkipsInsideReferenceStyleLink：[text][label] 与定义行都被禁")
    void skipsInsideReferenceStyleLink() {
        List<LinkRef> refs = List.of(ref("beijing", "北京"));
        String in = "See [北京][capital] for details.\n\n[capital]: https://example.com/beijing";
        WikiCrossLinker.LinkifyResult r = linkify.linkify(in, refs, "");
        assertThat(r.changed()).isFalse();
    }

    @Test
    @DisplayName("TestLinkifyContent_SkipsReferenceDefinitionLine：定义行里的 URL 词不加链")
    void skipsReferenceDefinitionLine() {
        List<LinkRef> refs = List.of(ref("example", "example"));
        WikiCrossLinker.LinkifyResult r = linkify.linkify("[cap]: https://example.com/x", refs, "");
        assertThat(r.changed()).isFalse();
    }

    // ── 幂等 ──

    @Test
    @DisplayName("TestLinkifyContent_RepeatedLinkifyIsIdempotent：第二次运行是 no-op")
    void repeatedLinkifyIsIdempotent() {
        List<LinkRef> refs = List.of(
                ref("beijing", "北京"),
                ref("shanghai", "上海"));
        String in = "今天去北京，明天去上海。";
        WikiCrossLinker.LinkifyResult once = linkify.linkify(in, refs, "");
        WikiCrossLinker.LinkifyResult twice = linkify.linkify(once.content(), refs, "");
        assertThat(twice.changed()).isFalse();
        assertThat(twice.content()).isEqualTo(once.content());
    }

    // ── Java 侧补充：WikiCrossLinker 端口的默认实现与 collectRefs ──

    @Nested
    @DisplayName("端口契约（Java 侧补充，无 Go 对应用例）")
    class PortContract {

        @Test
        @DisplayName("输入 refs 列表不被修改（对照 Go 注释的『input ref slice is not mutated』）")
        void doesNotMutateInputRefs() {
            List<LinkRef> refs = new java.util.ArrayList<>(List.of(
                    ref("beijing", "北京"), ref("shanghai", "上海")));
            List<LinkRef> snapshot = List.copyOf(refs);
            linkify.linkify("今天去北京，明天去上海。", refs, "");
            assertThat(refs).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("Noop 实现什么都不做——wiki_linkify 未接线时的历史行为")
        void noopIsInert() {
            WikiCrossLinker noop = new WikiCrossLinker.Noop();
            WikiCrossLinker.LinkifyResult r =
                    noop.linkify("北京", List.of(ref("beijing", "北京")), "");
            assertThat(r.changed()).isFalse();
            assertThat(r.content()).isEqualTo("北京");
        }

        @Test
        @DisplayName("collectRefs 摊平 title + aliases 并跳过 index 页")
        void collectRefsFlattensTitlesAndAliases() {
            com.ragagent.wiki.domain.WikiPage p = new com.ragagent.wiki.domain.WikiPage();
            p.setSlug("entity/beijing");
            p.setTitle("北京");
            p.setPageType(com.ragagent.wiki.domain.WikiConstants.PAGE_TYPE_ENTITY);
            p.setAliases(List.of("京师", ""));

            com.ragagent.wiki.domain.WikiPage idx = new com.ragagent.wiki.domain.WikiPage();
            idx.setSlug("index");
            idx.setTitle("Index");
            idx.setPageType(com.ragagent.wiki.domain.WikiConstants.PAGE_TYPE_INDEX);

            List<LinkRef> refs = WikiCrossLinker.collectRefs(List.of(p, idx));
            assertThat(refs).containsExactly(
                    new LinkRef("entity/beijing", "北京"),
                    new LinkRef("entity/beijing", "京师"));
        }
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
