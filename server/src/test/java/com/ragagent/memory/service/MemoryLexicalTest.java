package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryLexical} 与 {@link MemoryConsolidationService} 的聚类谓词的对等测试
 * （对照 Go internal/application/service/memory/lexical.go 与 consolidate.go 的
 * {@code jaccard}/{@code tokenSet}/{@code jaccardSets}/{@code clusterBy}）。
 *
 * <p><b>期望值全是 Go 实录</b>：把 lexical.go 的函数与 consolidate.go 的四个纯函数
 * 连同 {@code internal/types} 一起，在 Go 仓库的副本里跑
 * {@code go test -run TestTruthLexical} / {@code TestTruthConsolidate} 打印出来的
 * （见任务报告里的复现方式）。这抓的是只看代码看不出的东西——尤其
 * {@code scoreItems} 的归一化分母与 {@code 0.01*importance} 的叠加次序。</p>
 */
class MemoryLexicalTest {

    private static final OffsetDateTime T(String date) {
        return OffsetDateTime.parse(date + "T00:00:00Z");
    }

    private static MemoryItem item(String id, String kind, String topic, String content,
                                   int importance, String validFrom) {
        MemoryItem it = new MemoryItem();
        it.setId(id);
        it.setKind(kind);
        it.setTopic(topic);
        it.setContent(content);
        it.setImportance(importance);
        it.setValidFrom(T(validFrom));
        return it;
    }

    /** 与 Go 实录里那个语料逐字对应（含一个 nil 条目）。 */
    private static List<MemoryItem> corpus() {
        List<MemoryItem> items = new ArrayList<>();
        items.add(item("i1", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL", 3, "2026-03-01"));
        items.add(item("i2", MemoryKinds.KIND_TASK, "在做的重构", "重构支付流程，计划本周完成", 5, "2026-03-09"));
        items.add(item("i3", MemoryKinds.KIND_INTEREST, "医疗影像后端", "医疗影像后端", 3, "2026-03-02"));
        items.add(item("i4", MemoryKinds.KIND_PREFERENCE, "回答风格", "回答直接给结论，不要铺垫", 5, "2026-03-02"));
        items.add(null);
        items.add(item("i5", MemoryKinds.KIND_FACT, "", "PostgreSQL 17", 2, "2026-03-03"));
        return items;
    }

    private static List<String> ids(List<MemoryItem> items) {
        List<String> out = new ArrayList<>();
        for (MemoryItem it : items) {
            out.add(it == null ? "<nil>" : it.getId());
        }
        return out;
    }

    @Nested
    @DisplayName("tokenize / bigrams")
    class Tokenize {

        @Test
        void splitsCjkPerIdeographAndLatinOnNonAlphanumeric() {
            // Go 实录：["生","产","库","用","的","是","mysql"]
            assertThat(MemoryLexical.tokenize("生产库用的是 MySQL"))
                    .containsExactly("生", "产", "库", "用", "的", "是", "mysql");
            // Go 实录：["postgresql","连","接","池"]
            assertThat(MemoryLexical.tokenize("PostgreSQL 连接池"))
                    .containsExactly("postgresql", "连", "接", "池");
            // 大小写统一到同一批 token（Go 实录：["ab12","ab12","中","文"]）
            assertThat(MemoryLexical.tokenize("AB12 ab12 中文"))
                    .containsExactly("ab12", "ab12", "中", "文");
            assertThat(MemoryLexical.tokenize("")).isEmpty();
        }

        @Test
        void bigramsOnlyPairAdjacentSingleHan() {
            // Go 实录：["生产","产库","库用","用的","的是"]
            assertThat(MemoryLexical.bigrams(MemoryLexical.tokenize("生产库用的是 MySQL")))
                    .containsExactly("生产", "产库", "库用", "用的", "的是");
            // Go 实录：["连接","接池"]——不跨 "PostgreSQL" 与汉字
            assertThat(MemoryLexical.bigrams(MemoryLexical.tokenize("PostgreSQL 连接池")))
                    .containsExactly("连接", "接池");
            assertThat(MemoryLexical.bigrams(MemoryLexical.tokenize("AB12 ab12 中文")))
                    .containsExactly("中文");
            assertThat(MemoryLexical.bigrams(MemoryLexical.tokenize(""))).isEmpty();
        }
    }

    @Nested
    @DisplayName("scoreItems / lexicalRanking / takeWithinBudget")
    class Scoring {

        @Test
        void scoresMatchTheGoRecording() {
            List<MemoryLexical.ScoredItem> scored =
                    MemoryLexical.scoreItems("生产库 MySQL 迁移", corpus());
            // Go 实录：[{"id":"i1","score":0.6966666666666667}]
            // 分母是 6 个 unigram + 2*3 个 bigram = 12；命中 4 个 unigram + 2*2 个 bigram。
            assertThat(scored).hasSize(1);
            assertThat(scored.get(0).item().getId()).isEqualTo("i1");
            assertThat(scored.get(0).score()).isEqualTo(0.6966666666666667);
        }

        @Test
        void rankingReturnsIndexesInTheCandidateSlice() {
            // Go 实录：[0]
            assertThat(MemoryLexical.lexicalRanking("生产库 MySQL 迁移", corpus()))
                    .containsExactly(0);
            // Go 实录：[]
            assertThat(MemoryLexical.lexicalRanking("", corpus())).isEmpty();
        }

        @Test
        void takeWithinBudgetStopsAtCountAndSkipsOverBudgetItems() {
            List<Integer> ranked = MemoryLexical.lexicalRanking("生产库 MySQL 迁移", corpus());
            // Go 实录：["i1"]
            assertThat(ids(MemoryLexical.takeWithinBudget(ranked, corpus(), 2, 600)))
                    .containsExactly("i1");
            assertThat(ids(MemoryLexical.takeWithinBudget(ranked, corpus(), 10, 20)))
                    .containsExactly("i1");
            assertThat(ids(MemoryLexical.selectRecallItems("生产库 MySQL 迁移", corpus(), 3, 600)))
                    .containsExactly("i1");
            // maxItems=0 时什么都不选（预算是独立的一道门）
            assertThat(MemoryLexical.takeWithinBudget(ranked, corpus(), 0, 600)).isEmpty();
        }
    }

    @Nested
    @DisplayName("jaccard / clusterBy")
    class Clustering {

        @Test
        void jaccardMatchesTheGoRecording() {
            List<String> a = MemoryLexical.tokenize("生产库用的是 MySQL");
            List<String> b = MemoryLexical.tokenize("生产库的 MySQL");
            // Go 实录：0.7142857142857143
            assertThat(MemoryLexical.jaccard(a, b)).isEqualTo(0.7142857142857143);
            // Go 实录：0（空集合不算"完全重合"）
            assertThat(MemoryLexical.jaccardSets(MemoryLexical.tokenSet(List.of()),
                    MemoryLexical.tokenSet(List.of()))).isEqualTo(0);
            // Go 实录：1
            assertThat(MemoryLexical.jaccard(a, a)).isEqualTo(1.0);
        }

        @Test
        void clusterSimilarGroupsSameKindAndNearIdenticalWording() {
            List<MemoryItem> items = new ArrayList<>();
            items.add(item("a", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL", 3, "2026-03-01"));
            items.add(item("b", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的 MySQL", 3, "2026-03-01"));
            items.add(item("c", MemoryKinds.KIND_TASK, "重构", "重构支付流程", 3, "2026-03-01"));
            items.add(null);
            items.add(item("d", MemoryKinds.KIND_TASK, "重构", "重构支付流程", 3, "2026-03-01"));

            // Go 实录：[["a","b"],["c","d"]]
            List<List<String>> groups = new ArrayList<>();
            for (List<MemoryItem> g : MemoryConsolidationService.clusterSimilar(items)) {
                groups.add(ids(g));
            }
            assertThat(groups).containsExactly(List.of("a", "b"), List.of("c", "d"));
        }

        @Test
        void clusterByNeverComparesAcrossKindsAndSkipsAlreadyTakenItems() {
            List<MemoryItem> items = new ArrayList<>();
            items.add(item("a", MemoryKinds.KIND_FACT, "t", "c", 3, "2026-03-01"));
            items.add(item("b", MemoryKinds.KIND_FACT, "t", "c", 3, "2026-03-01"));
            items.add(item("c", MemoryKinds.KIND_TASK, "t", "c", 3, "2026-03-01"));
            items.add(null);
            items.add(item("d", MemoryKinds.KIND_TASK, "t", "c", 3, "2026-03-01"));

            // Go 实录：[["a","b"],["c","d"]]
            List<List<String>> groups = new ArrayList<>();
            for (List<MemoryItem> g : MemoryConsolidationService.clusterBy(items, (x, y) -> true)) {
                groups.add(ids(g));
            }
            assertThat(groups).containsExactly(List.of("a", "b"), List.of("c", "d"));
        }

        @Test
        void clusterByDropsGroupsOfOne() {
            List<MemoryItem> items = List.of(
                    item("a", MemoryKinds.KIND_FACT, "t", "c", 3, "2026-03-01"),
                    item("b", MemoryKinds.KIND_TASK, "t", "c", 3, "2026-03-01"));
            assertThat(MemoryConsolidationService.clusterBy(items, (x, y) -> true)).isEmpty();
        }
    }

    @Nested
    @DisplayName("splitResidentInterests")
    class SplitResident {

        @Test
        void separatesInterestsFromTheRest() {
            // Go 实录：others ["i1","i2","i4","i5"] / interests ["i3"]
            MemoryRecallSelector.Split split =
                    MemoryRecallSelector.splitResidentInterests(corpus());
            assertThat(ids(split.others())).containsExactly("i1", "i2", "i4", "i5");
            assertThat(ids(split.interests())).containsExactly("i3");
        }

        @Test
        void toleratesNullInput() {
            MemoryRecallSelector.Split split = MemoryRecallSelector.splitResidentInterests(null);
            assertThat(split.others()).isEmpty();
            assertThat(split.interests()).isEmpty();
        }
    }

    /** 提醒：{@code OffsetDateTime} 的时区在字面量里必须显式，否则测试会随机器时区漂。 */
    @Test
    void validFromComparisonUsesWallClockInstant() {
        MemoryItem a = item("a", MemoryKinds.KIND_FACT, "t", "同一个内容", 3, "2026-03-09");
        MemoryItem b = item("b", MemoryKinds.KIND_FACT, "t", "同一个内容", 3, "2026-03-01");
        // 同一分数（同内容、同重要度）时新者在前——Go 的 `ValidFrom.After` 破平局。
        List<MemoryLexical.ScoredItem> scored = MemoryLexical.scoreItems("同一个内容", List.of(a, b));
        assertThat(scored).hasSize(2);
        assertThat(scored.get(0).item().getId()).isEqualTo("a");
        assertThat(scored.get(0).item().getValidFrom().toInstant())
                .isAfter(scored.get(1).item().getValidFrom().toInstant());
        assertThat(ZoneOffset.UTC).isNotNull();
    }
}
