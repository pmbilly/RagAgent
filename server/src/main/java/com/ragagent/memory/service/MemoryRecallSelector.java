package com.ragagent.memory.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ragagent.common.settings.MemoryConfig;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryVectorHit;
import org.springframework.stereotype.Component;

/**
 * 一轮召回的排序与选取。
 *
 * <h2>两个排序，刻意不对称</h2>
 * <p>字面那个给传进来的候选池打分；语义那个是对这个主体**每一个**向量做的查找，
 * 所以它能把池子里从来没有的记忆拉进来。这个不对称是刻意的：精确措辞在进程内匹配很便宜，
 * 而"这个人的哪几条记忆与这个问题有关"正是向量库存在的理由，不能拿去问一个任意的子集。</p>
 *
 * <h2>注入与上报刻意不是同一个集合</h2>
 * <p>{@link #selectResidentInterests} 返回两个列表：{@code selected} 全部会被注入（到上限为止），
 * 而只有 {@code relevant}（当前问题**真的**匹配上的那些）会上报给聊天界面。
 * 一个仅仅因为"还有位置"而搭车的兴趣是**背景**，把它列成"为这个回答召回的记忆"
 * 会把与问题无关的记忆每轮都堆到时间线上。</p>
 */
@Component
public class MemoryRecallSelector {

    private final MemoryVectorService vectorService;

    public MemoryRecallSelector(MemoryVectorService vectorService) {
        this.vectorService = vectorService;
    }

    // ── 候选池上限 ─────────────────────────────────────────────────────────

    /**
     * 字面排序扫多少条已存记忆。
     *
     * <p>它就是工作区的容量上限，也就是一个主体最多能持有的活跃记忆数——
     * 于是这个池子不再是压在容量上限下面的第二道隐形上限。它以前是固定的 400，
     * 而容量上限最高到 2000，结果第 400 条之后的记忆**无论问什么都召不回**。</p>
     *
     * <p>字面那一侧之所以有上限，只是因为它要在<b>进程内</b>打分。语义匹配不受它约束：
     * 它直接查存储，所以一个很大的主体由索引来回答，而不是由"这里放得下多少"。</p>
     */
    public static int lexicalPoolSize(MemoryConfig cfg) {
        return cfg.effectiveMaxItems();
    }

    // ── 追踪 ───────────────────────────────────────────────────────────────

    /**
     * 一轮的情境条目是怎么排出来的。
     *
     * <p>可变类而不是 record：排序过程一路填字段，最后整体读出。</p>
     */
    public static final class RankingTrace {
        public int lexicalHits;
        public int vectorHits;
        /**
         * 字面池子里没有的语义命中数。
         * 候选选取发生在"看问题"之前时，这些**全部**是够不到的。
         */
        public int vectorOutsidePool;
        public String vectorSkipReason = "";
        public int fusedCandidates;
        public int matched;
        public String mode = "";
    }

    /**
     * 一次排序请求。
     *
     * <p>用结构体而不是参数表，是因为两个调用方差的远不止预算：按需查找可以返回任何种类、
     * 也没有要排除的东西；而一轮召回限定在情境种类上，并且不能重复打印常驻块已经印过的。
     * 这两件事现在都必须一直传到向量阶段——它不再从一个预过滤的候选列表里继承它们了。</p>
     */
    public record Selection(String query, List<MemoryItem> candidates, List<String> kinds,
                            Set<String> excludeIds, int maxItems, int runeBudget) {
    }

    /** {@link #selectRecallWithTrace} 的结果。 */
    public record Outcome(List<MemoryItem> matched, RankingTrace trace) {
    }

    /**
     * 向存储要多少条语义命中，相对于能返回多少条。
     *
     * <p>比输出宽，是因为融合需要有东西可融：只有向量一侧喜欢的条目必须能跟字面排序竞争，
     * 而 rune 预算也可能跳过好几条长条目才找到放得下的。四倍输出加一个下限，
     * 既留了余量，又不会把一次五条的召回变成一次上百行的读取。</p>
     */
    public static int vectorFanout(int maxItems) {
        int limit = maxItems * 4;
        return Math.max(limit, 20);
    }

    /** {@link #mergeVectorHits} 的结果。 */
    public record MergedHits(List<Integer> ranking, List<MemoryItem> pool, int added) {
    }

    /**
     * 把语义命中变成候选池上的一次排序，
     * 并把池子**撑大**到装下它原先没有的那些匹配。
     *
     * <p>池子必须能长大，否则整个"查存储"的意义就丢了：融合要合成的两个排序必须
     * 指向同一个切片，所以只有向量一侧找到的匹配需要在里面有个位置。</p>
     *
     * <p>返回的第三个值只是为了让 trace 能显示"有多少条是池子外的"。</p>
     */
    public static MergedHits mergeVectorHits(List<MemoryItem> candidates, List<MemoryVectorHit> hits,
                                            Set<String> excludeIds) {
        if (hits == null || hits.isEmpty()) {
            return new MergedHits(null, candidates, 0);
        }
        Map<String, Integer> indexById = new HashMap<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            MemoryItem item = candidates.get(i);
            if (item != null && !item.getId().isEmpty()) {
                indexById.put(item.getId(), i);
            }
        }
        // 复制之后再 append：调用方的切片可能与它被过滤出来的那个列表共享底层数组，
        // 往那儿长会改写没人让我们碰的行。
        List<MemoryItem> pool = candidates;
        boolean copied = false;
        int added = 0;

        List<Integer> ranking = new ArrayList<>(hits.size());
        for (MemoryVectorHit hit : hits) {
            if (hit.item() == null || hit.item().getId().isEmpty()) {
                continue;
            }
            if (excludeIds != null && excludeIds.contains(hit.item().getId())) {
                continue;
            }
            Integer index = indexById.get(hit.item().getId());
            if (index != null) {
                ranking.add(index);
                continue;
            }
            if (!copied) {
                pool = new ArrayList<>(candidates);
                copied = true;
            }
            pool.add(hit.item());
            indexById.put(hit.item().getId(), pool.size() - 1);
            ranking.add(pool.size() - 1);
            added++;
        }
        return new MergedHits(ranking, pool, added);
    }

    /**
     * 把记忆对着查询排序，返回符合预算的最好的那些。
     */
    public Outcome selectRecallWithTrace(MemoryScope scope, MemoryConfig cfg, Selection req) {
        RankingTrace trace = new RankingTrace();
        String query = req.query();
        List<MemoryItem> candidates = req.candidates();
        int maxItems = req.maxItems();
        int runeBudget = req.runeBudget();

        MemoryTrace.Span lexSpan = MemoryTrace.start("memory.recall.lexical", Map.of(
                "query", MemoryTrace.truncateRunes(query, recallQueryPreviewRunes()),
                "candidates", candidates.size()));
        List<Integer> lexical = MemoryLexical.lexicalRanking(query, candidates);
        trace.lexicalHits = lexical.size();
        lexSpan.finish(Map.of("hits", trace.lexicalHits), null, null);

        MemoryTrace.Span vecSpan = MemoryTrace.start("memory.recall.vector", Map.of(
                "query", MemoryTrace.truncateRunes(query, recallQueryPreviewRunes()),
                "candidates", candidates.size()));
        MemoryVectorService.VectorSearchOutcome search = vectorService.vectorSearch(
                scope, cfg, query, req.kinds(), vectorFanout(maxItems));
        MergedHits merged = mergeVectorHits(candidates, search.hits(), req.excludeIds());
        trace.vectorHits = merged.ranking() == null ? 0 : merged.ranking().size();
        trace.vectorOutsidePool = merged.added();
        trace.vectorSkipReason = search.skipReason();

        Map<String, Object> vecOut = new LinkedHashMap<>();
        vecOut.put("hits", trace.vectorHits);
        vecOut.put("outside_pool", merged.added());
        if (!trace.vectorSkipReason.isEmpty()) {
            vecOut.put("skip_reason", trace.vectorSkipReason);
        }
        vecSpan.finish(vecOut, null, null);

        if (merged.ranking() == null || merged.ranking().isEmpty()) {
            trace.mode = lexical.isEmpty() ? "no_matches" : "lexical_only";
            List<MemoryItem> matched =
                    MemoryLexical.takeWithinBudget(lexical, merged.pool(), maxItems, runeBudget);
            trace.matched = matched.size();
            return new Outcome(matched, trace);
        }

        List<Integer> fused = MemoryVectorService.fuseRankings(lexical, merged.ranking());
        trace.fusedCandidates = fused.size();
        trace.mode = "hybrid";
        List<MemoryItem> matched =
                MemoryLexical.takeWithinBudget(fused, merged.pool(), maxItems, runeBudget);
        trace.matched = matched.size();
        return new Outcome(matched, trace);
    }

    /** 查询预览的 rune 上限。 */
    static int recallQueryPreviewRunes() {
        return 500;
    }

    // ── 常驻兴趣的拆分与选取 ───────────────────────────────────────────────

    /** {@link #splitResidentInterests} 的结果。 */
    public record Split(List<MemoryItem> others, List<MemoryItem> interests) {
    }

    /**
     * 把兴趣从常驻集合里分出来，
     * 两者处置不同——其它是无条件注入的，兴趣有上限。
     */
    public static Split splitResidentInterests(List<MemoryItem> items) {
        List<MemoryItem> others = new ArrayList<>();
        List<MemoryItem> interests = new ArrayList<>();
        if (items != null) {
            for (MemoryItem item : items) {
                if (item == null) {
                    continue;
                }
                if (MemoryKinds.KIND_INTEREST.equals(item.getKind())) {
                    interests.add(item);
                    continue;
                }
                others.add(item);
            }
        }
        return new Split(others, interests);
    }

    /** {@link #selectResidentInterests} 的结果。 */
    public record Selected(List<MemoryItem> selected, List<MemoryItem> relevant) {
    }

    /**
     * 挑选哪些兴趣进常驻块。
     *
     * <p>匹配只走字面。语义那趟需要一次查询嵌入，而为了这么短的一个列表
     * （条目还是话题标签，相关的问题通常直接点名）付一次模型往返，不值得加到每一轮的前面。</p>
     */
    public static Selected selectResidentInterests(String query, List<MemoryItem> interests, int maxItems) {
        if (interests == null || interests.isEmpty() || maxItems <= 0) {
            return new Selected(null, null);
        }
        List<MemoryItem> selected = new ArrayList<>();
        List<MemoryItem> relevant = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (int index : MemoryLexical.lexicalRanking(query, interests)) {
            if (selected.size() >= maxItems) {
                break;
            }
            taken.add(index);
            selected.add(interests.get(index));
            relevant.add(interests.get(index));
        }
        // 剩下的位置按仓库顺序（重要度，然后新近）填满，这样一个什么都匹配不上的问题
        // 仍然能看到这个人反复回来的那些主题。
        for (int index = 0; index < interests.size(); index++) {
            if (selected.size() >= maxItems) {
                break;
            }
            if (taken.contains(index)) {
                continue;
            }
            selected.add(interests.get(index));
        }
        return new Selected(selected, relevant);
    }
}
