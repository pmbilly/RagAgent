package com.ragagent.wiki.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.wiki.domain.WikiConstants;
import com.ragagent.wiki.domain.WikiException;
import com.ragagent.wiki.domain.WikiGraph;
import com.ragagent.wiki.domain.WikiPage;

/**
 * 图谱子集计算（对照 Go internal/application/service/wiki_page.go
 * {@code computeGraphSubset} L592-741 与 {@code bfsEgoSlugs} L748-810）。
 *
 * <p>Go 把它抽成包级纯函数是为了让测试不必搭整套仓储 mock；Java 侧同样抽成
 * <b>无 I/O 的静态方法</b>，便于逐分支对照
 * {@code wiki_page_test.go:410-608} 的密集覆盖。</p>
 *
 * <p>两种模式（Go L549-569）：</p>
 * <ul>
 *   <li>{@link WikiConstants#GRAPH_MODE_OVERVIEW}（默认）：按 link_count（入+出）
 *       取 top-{@code Limit}，外加所有两端都存活节点之间的边。这是前端首次打开
 *       图谱时取的切片——4 万页的 wiki 否则会吐约 30MB JSON 并让浏览器渲染
 *       10 万个 SVG 元素时崩掉。</li>
 *   <li>{@link WikiConstants#GRAPH_MODE_EGO}：以 {@code Center} 为中心、最多
 *       {@code Depth} 跳的<b>无向</b> BFS 邻域，节点总数上限 {@code Limit}。</li>
 * </ul>
 *
 * <p>{@code Types} 是可选 page_type 白名单，同时作用于候选节点集与（ego 模式的）
 * 前沿展开；留空表示不过滤。</p>
 *
 * <p>{@code Limit <= 0} 完全关闭上限，仅供内部调用方（如 lint）走全图使用；
 * HTTP handler 永远会在调用前把 Limit 夹到安全区间。</p>
 */
public final class WikiGraphCalculator {

    private WikiGraphCalculator() {}

    /**
     * 对照 Go {@code computeGraphSubset}（L592-741）。
     *
     * @throws WikiException {@code "wiki graph request is required"} /
     *                       {@code "ego graph requires a center slug"} /
     *                       {@code "ego center slug \"x\" not found"}
     */
    public static WikiGraph.Data compute(List<WikiPage> pages, WikiGraph.Request req) {
        if (req == null) {
            throw new WikiException("wiki graph request is required");
        }
        List<WikiPage> all = pages == null ? List.of() : pages;

        String mode = req.mode();
        if (mode == null || mode.isEmpty()) {
            mode = WikiConstants.GRAPH_MODE_OVERVIEW;
        }

        // 预计算 link_count 与类型白名单。保留完整页面列表，这样 ego 模式仍能
        // 穿过类型不在白名单里的邻居（Go L598-607）。
        Set<String> typeAllow = new HashSet<>();
        if (req.types() != null) {
            for (String t : req.types()) {
                if (t != null && !t.isEmpty()) {
                    typeAllow.add(t);
                }
            }
        }
        boolean hasTypeFilter = !typeAllow.isEmpty();

        Set<String> familiarSet = new HashSet<>();
        if (req.familiarKnowledgeIds() != null) {
            for (String id : req.familiarKnowledgeIds()) {
                if (id == null) {
                    continue;
                }
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    familiarSet.add(trimmed);
                }
            }
        }

        // ⚠️ 与 Go 的差异：Go 用 map 迭代 + 排序保证确定性；Java 这里用
        // LinkedHashMap 进一步保住「同一 slug 后出现者覆盖前者」的 insert 语义
        // （Go 的 map 在建表阶段就是后者覆盖，两者一致）。
        Map<String, WikiPage> pageBySlug = new LinkedHashMap<>(all.size());
        Map<String, Integer> linkCount = new LinkedHashMap<>(all.size());
        for (WikiPage p : all) {
            pageBySlug.put(p.getSlug(), p);
            linkCount.put(p.getSlug(), p.getInLinks().size() + p.getOutLinks().size());
        }

        // 选出本次切片的节点 slug 集合（Go L623-663）
        Set<String> selected;
        if (WikiConstants.GRAPH_MODE_EGO.equals(mode)) {
            if (req.center() == null || req.center().isEmpty()) {
                throw new WikiException("ego graph requires a center slug");
            }
            if (!pageBySlug.containsKey(req.center())) {
                throw new WikiException("ego center slug \"" + req.center() + "\" not found");
            }
            int depth = req.depth();
            if (depth < 1) {
                depth = 1;
            }
            selected = bfsEgoSlugs(pageBySlug, req.center(), depth, typeAllow, req.limit());
        } else {
            // overview：先过类型白名单，再按 link_count 降序，最后截断
            List<WikiPage> candidates = new ArrayList<>(all.size());
            for (WikiPage p : all) {
                if (hasTypeFilter && !typeAllow.contains(p.getPageType())) {
                    continue;
                }
                candidates.add(p);
            }
            // 稳定的 tiebreaker（link_count 相同时按 slug 升序）让 API 在多次调用间确定
            candidates.sort(Comparator
                    .comparingInt((WikiPage p) -> linkCount.get(p.getSlug())).reversed()
                    .thenComparing(WikiPage::getSlug));
            if (req.limit() > 0 && candidates.size() > req.limit()) {
                candidates = candidates.subList(0, req.limit());
            }
            selected = new LinkedHashSet<>(candidates.size());
            for (WikiPage p : candidates) {
                selected.add(p.getSlug());
            }
        }

        // 由选中集合构建节点（Go L665-683）
        List<WikiGraph.Node> nodes = new ArrayList<>(selected.size());
        for (String slug : selected) {
            WikiPage p = pageBySlug.get(slug);
            WikiGraph.Node n = new WikiGraph.Node();
            n.setSlug(p.getSlug());
            n.setTitle(p.getTitle());
            n.setPageType(p.getPageType());
            n.setLinkCount(linkCount.get(slug));
            n.setFamiliar(p.builtFrom(familiarSet));
            nodes.add(n);
        }
        // 确定性节点排序——Go 上面那次 map 迭代是随机的（Go L677-683）
        nodes.sort(Comparator
                .comparingInt(WikiGraph.Node::getLinkCount).reversed()
                .thenComparing(WikiGraph.Node::getSlug));

        // 构建边：只保留两端都活下来的（Go L685-700）。
        // 边序 = 页面列表序 × 出链序，两端语言一致。
        List<WikiGraph.Edge> edges = new ArrayList<>();
        for (WikiPage p : all) {
            if (!selected.contains(p.getSlug())) {
                continue;
            }
            for (String target : p.getOutLinks()) {
                if (!selected.contains(target)) {
                    continue;
                }
                edges.add(new WikiGraph.Edge(p.getSlug(), target));
            }
        }

        // total 是截断<b>之前</b>的候选节点数——即前端若请求整张图需要拉取的全量。
        // overview 尊重类型过滤；ego 用整个 KB 的页面数（用户看到的仍是
        // "X / Y" 里的完整分母，而不是过滤后的分母）。Go L702-715。
        int total = all.size();
        if (WikiConstants.GRAPH_MODE_OVERVIEW.equals(mode) && hasTypeFilter) {
            total = 0;
            for (WikiPage p : all) {
                if (typeAllow.contains(p.getPageType())) {
                    total++;
                }
            }
        }

        WikiGraph.Meta meta = new WikiGraph.Meta();
        meta.setMode(mode);
        meta.setTotal(total);
        meta.setReturned(nodes.size());
        meta.setTruncated(nodes.size() < total);
        int familiarCount = 0;
        for (WikiGraph.Node n : nodes) {
            if (n.isFamiliar()) {
                familiarCount++;
            }
        }
        meta.setFamiliarCount(familiarCount);
        if (WikiConstants.GRAPH_MODE_EGO.equals(mode)) {
            meta.setCenter(req.center());
            int depth = req.depth();
            if (depth < 1) {
                depth = 1;
            }
            meta.setDepth(depth);
        }

        WikiGraph.Data data = new WikiGraph.Data();
        data.setNodes(nodes);
        data.setEdges(edges);
        data.setMeta(meta);
        return data;
    }

    /**
     * 对照 Go {@code bfsEgoSlugs}（L748-810）：以 {@code center} 为起点、最多
     * {@code depth} 跳的<b>无向</b> BFS 邻域（同时走入链与出链）。
     *
     * <p>被类型过滤挡住的页面既不出现在结果里，<b>也不被穿过</b>——所以一个把
     * "index" 页藏起来的过滤器不会经由 index 泄漏整张 wiki。
     * 调用方保证 {@code center} 一定在 {@code pageBySlug} 里。</p>
     *
     * <p>类型过滤把中心页本身挡掉时，尊重过滤器返回空集（handler 会呈现 Returned=0）。</p>
     */
    static Set<String> bfsEgoSlugs(Map<String, WikiPage> pageBySlug,
                                   String center,
                                   int depth,
                                   Set<String> typeAllow,
                                   int limit) {
        boolean hasTypeFilter = !typeAllow.isEmpty();
        WikiPage centerPage = pageBySlug.get(center);
        if (centerPage == null) {
            return new LinkedHashSet<>();
        }
        if (hasTypeFilter && !typeAllow.contains(centerPage.getPageType())) {
            return new LinkedHashSet<>();
        }

        Set<String> visited = new LinkedHashSet<>();
        visited.add(center);
        List<String> frontier = new ArrayList<>();
        frontier.add(center);

        for (int hop = 0; hop < depth; hop++) {
            if (limit > 0 && visited.size() >= limit) {
                break;
            }
            List<String> next = new ArrayList<>(frontier.size());
            for (String slug : frontier) {
                WikiPage p = pageBySlug.get(slug);
                if (p == null) {
                    continue;
                }
                // 邻居 = 出链 ++ 入链（Go 的 append(OutLinks, InLinks...)）
                List<String> neighbors = new ArrayList<>(
                        p.getOutLinks().size() + p.getInLinks().size());
                neighbors.addAll(p.getOutLinks());
                neighbors.addAll(p.getInLinks());
                for (String nb : neighbors) {
                    if (visited.contains(nb)) {
                        continue;
                    }
                    WikiPage np = pageBySlug.get(nb);
                    if (np == null) {
                        continue;
                    }
                    if (hasTypeFilter && !typeAllow.contains(np.getPageType())) {
                        continue;
                    }
                    visited.add(nb);
                    next.add(nb);
                    if (limit > 0 && visited.size() >= limit) {
                        break;
                    }
                }
                if (limit > 0 && visited.size() >= limit) {
                    break;
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                break;
            }
        }

        return visited;
    }
}
