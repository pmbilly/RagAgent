package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiPage;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/application/service/wiki_page_test.go L332-607
 * （{@code makeGraphFixture} + 7 个 {@code TestComputeGraphSubset_*}）。
 *
 * <p>这是本任务书点名的<b>保真优先项 #1</b>，逐分支对照。</p>
 */
class WikiGraphCalculatorTest {

    /**
     * 对照 Go {@code makeGraphFixture}：一张小的合成 wiki。
     *
     * <pre>
     * 有向边：
     *   hub -> a, hub -> b, hub -> c, hub -> d
     *   a   -> hub
     *   b   -> hub
     *   c   -> d
     *   x   -> y   （与 hub 断开的孤立二元簇）
     * </pre>
     *
     * <p>页面类型是刻意选的，好让类型过滤测试能排除指定节点。</p>
     */
    static List<WikiPage> makeGraphFixture() {
        List<WikiPage> pages = new ArrayList<>();
        pages.add(page("hub", "Hub", WikiConstants.PAGE_TYPE_SUMMARY,
                List.of("a", "b", "c", "d"), List.of("a", "b")));
        pages.add(page("a", "A", WikiConstants.PAGE_TYPE_ENTITY,
                List.of("hub"), List.of("hub")));
        pages.add(page("b", "B", WikiConstants.PAGE_TYPE_ENTITY,
                List.of("hub"), List.of("hub")));
        pages.add(page("c", "C", WikiConstants.PAGE_TYPE_CONCEPT,
                List.of("d"), List.of("hub")));
        pages.add(page("d", "D", WikiConstants.PAGE_TYPE_CONCEPT,
                List.of(), List.of("hub", "c")));
        pages.add(page("x", "X", WikiConstants.PAGE_TYPE_ENTITY,
                List.of("y"), List.of()));
        pages.add(page("y", "Y", WikiConstants.PAGE_TYPE_ENTITY,
                List.of(), List.of("x")));
        return pages;
    }

    private static WikiPage page(String slug, String title, String pageType,
                                List<String> outLinks, List<String> inLinks) {
        WikiPage p = new WikiPage();
        p.setSlug(slug);
        p.setTitle(title);
        p.setPageType(pageType);
        p.setOutLinks(new ArrayList<>(outLinks));
        p.setInLinks(new ArrayList<>(inLinks));
        return p;
    }

    private static Set<String> nodeSlugs(WikiGraph.Data data) {
        Set<String> out = new HashSet<>();
        for (WikiGraph.Node n : data.getNodes()) {
            out.add(n.getSlug());
        }
        return out;
    }

    /** overview 用便利构造 */
    private static WikiGraph.Request overview(int limit, List<String> types,
                                              List<String> familiar) {
        return new WikiGraph.Request("kb", WikiConstants.GRAPH_MODE_OVERVIEW, "",
                0, types, limit, familiar);
    }

    private static WikiGraph.Request ego(String center, int depth, int limit) {
        return new WikiGraph.Request("kb", WikiConstants.GRAPH_MODE_EGO, center,
                depth, List.of(), limit, List.of());
    }

    // ──────────────────────────── 测试 ────────────────────────────

    /**
     * 对照 Go {@code TestComputeGraphSubset_OverviewTruncatesByLinkCount}：
     * overview 模式先返回最连通的节点，并在 Meta 里诚实地报告截断。
     * 4 万页规模下正是这条路径<b>绝不能</b>返回全图——上限是响应体积与
     * 前端渲染可承受的关键。
     */
    @Test
    void overviewTruncatesByLinkCount() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data got = WikiGraphCalculator.compute(pages, overview(3, List.of(), List.of()));

        assertThat(got.getNodes()).hasSize(3);
        Set<String> slugs = nodeSlugs(got);
        // hub 的 link_count 是 6（4 出 + 2 入），必须活过 top-3 截断
        assertThat(slugs).contains("hub");
        assertThat(got.getMeta().isTruncated()).isTrue();
        assertThat(got.getMeta().getTotal()).isEqualTo(pages.size());
        assertThat(got.getMeta().getReturned()).isEqualTo(got.getNodes().size());

        // 每条返回的边都必须连接两个存活节点
        for (WikiGraph.Edge e : got.getEdges()) {
            assertThat(slugs).contains(e.getSource());
            assertThat(slugs).contains(e.getTarget());
        }
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_MarksFamiliarSourcePages}：
     * source_refs 与用户熟悉文档集合相交的页面被点亮。
     */
    @Test
    void marksFamiliarSourcePages() {
        List<WikiPage> pages = makeGraphFixture();
        pages.get(0).setSourceRefs(new ArrayList<>(List.of("doc-1|排班手册")));
        pages.get(1).setSourceRefs(new ArrayList<>(List.of("doc-2")));

        WikiGraph.Data got = WikiGraphCalculator.compute(pages,
                overview(0, List.of(), List.of("doc-1")));

        Set<String> familiar = new HashSet<>();
        for (WikiGraph.Node n : got.getNodes()) {
            if (n.isFamiliar()) {
                familiar.add(n.getSlug());
            }
        }
        assertThat(familiar).contains("hub");
        assertThat(familiar).doesNotContain("a");
        assertThat(got.getMeta().getFamiliarCount()).isEqualTo(1);
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_OverviewUncapped}：
     * Limit&lt;=0 的逃生通道对内部调用方（wiki lint）依然有效。
     */
    @Test
    void overviewUncapped() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data got = WikiGraphCalculator.compute(pages, overview(0, List.of(), List.of()));

        assertThat(got.getNodes()).hasSize(pages.size());
        assertThat(got.getMeta().isTruncated()).isFalse();
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_OverviewTypeFilter}：
     * 类型过滤作用于候选集合（不是事后过滤），所以 total 反映的是
     * 前端看到的「过滤感知」分母。
     */
    @Test
    void overviewTypeFilter() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data got = WikiGraphCalculator.compute(pages,
                overview(100, List.of(WikiConstants.PAGE_TYPE_ENTITY), List.of()));

        Set<String> slugs = nodeSlugs(got);
        // 只有 entity 类型的页面：a, b, x, y
        assertThat(slugs).contains("a", "b", "x", "y");
        assertThat(slugs).doesNotContain("hub", "c", "d");
        assertThat(got.getMeta().getTotal()).isEqualTo(4);
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_EgoDepth1}：depth=1 只返回中心 + 直接邻居，
     * 不做任何传递跳。
     */
    @Test
    void egoDepth1() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data got = WikiGraphCalculator.compute(pages, ego("hub", 1, 100));

        Set<String> slugs = nodeSlugs(got);
        // hub 的直接邻居：出链 {a,b,c,d} ∪ 入链 {a,b} = {a,b,c,d}，加上 hub
        assertThat(slugs).contains("hub", "a", "b", "c", "d");
        // x / y 在断开簇里，绝不能漏进来
        assertThat(slugs).doesNotContain("x", "y");
        assertThat(got.getMeta().getMode()).isEqualTo(WikiConstants.GRAPH_MODE_EGO);
        assertThat(got.getMeta().getCenter()).isEqualTo("hub");
        assertThat(got.getMeta().getDepth()).isEqualTo(1);
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_EgoDepth2ExpandsFrontier}：
     * 从 "a" 出发 depth=2 会经 hub 到达 b/c/d。
     */
    @Test
    void egoDepth2ExpandsFrontier() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data depth1 = WikiGraphCalculator.compute(pages, ego("a", 1, 100));
        WikiGraph.Data depth2 = WikiGraphCalculator.compute(pages, ego("a", 2, 100));

        assertThat(depth2.getNodes().size()).isGreaterThan(depth1.getNodes().size());
        // depth=2 时从 "a" 出发：a -> hub -> {b, c, d}
        assertThat(nodeSlugs(depth2)).contains("a", "hub", "b", "c", "d");
    }

    /**
     * 对照 Go {@code TestComputeGraphSubset_EgoRejectsMissingCenter}：
     * 中心 slug 不存在时必须快速失败，而不是返回空图——空结果与
     * 「你的 wiki 里没有页面链到它」看起来一模一样，会掩盖真正的 bug。
     */
    @Test
    void egoRejectsMissingCenter() {
        List<WikiPage> pages = makeGraphFixture();
        assertThatThrownBy(() -> WikiGraphCalculator.compute(pages, ego("does-not-exist", 1, 100)))
                .isInstanceOf(WikiException.class)
                .hasMessage("ego center slug \"does-not-exist\" not found");
    }

    /** 对照 Go：{@code req == nil} → {@code "wiki graph request is required"} */
    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(() -> WikiGraphCalculator.compute(makeGraphFixture(), null))
                .isInstanceOf(WikiException.class)
                .hasMessage("wiki graph request is required");
    }

    /** 对照 Go：ego 模式缺 center → {@code "ego graph requires a center slug"} */
    @Test
    void egoRequiresCenter() {
        assertThatThrownBy(() -> WikiGraphCalculator.compute(makeGraphFixture(), ego("", 1, 100)))
                .isInstanceOf(WikiException.class)
                .hasMessage("ego graph requires a center slug");
    }

    /**
     * 节点顺序必须确定（link_count 降序、同分按 slug 升序）——Go 里上面的
     * map 迭代是随机的，靠这次排序扳回来。
     */
    @Test
    void nodeOrderIsDeterministic() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data first = WikiGraphCalculator.compute(pages, overview(0, List.of(), List.of()));
        List<String> order = new ArrayList<>();
        for (WikiGraph.Node n : first.getNodes()) {
            order.add(n.getSlug());
        }
        // link_count：hub 6（4 出 + 2 入）；a/b/c/d 各 2；x/y 各 1。
        // 同为 2 的四个按 slug 升序，故 a,b,c,d。
        assertThat(order.get(0)).isEqualTo("hub");
        assertThat(order).containsExactly("hub", "a", "b", "c", "d", "x", "y");

        // 重复调用结果一致
        for (int i = 0; i < 5; i++) {
            WikiGraph.Data again = WikiGraphCalculator.compute(pages,
                    overview(0, List.of(), List.of()));
            List<String> againOrder = new ArrayList<>();
            for (WikiGraph.Node n : again.getNodes()) {
                againOrder.add(n.getSlug());
            }
            assertThat(againOrder).isEqualTo(order);
        }
    }

    /** 边只保留两端都活下来的；截断后不应出现指向被裁节点的边 */
    @Test
    void edgesOnlyBetweenSurvivingNodes() {
        List<WikiPage> pages = makeGraphFixture();
        WikiGraph.Data got = WikiGraphCalculator.compute(pages, overview(2, List.of(), List.of()));
        Set<String> slugs = nodeSlugs(got);
        for (WikiGraph.Edge e : got.getEdges()) {
            assertThat(slugs).contains(e.getSource(), e.getTarget());
        }
    }
}
