package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import com.ragagent.memory.domain.MemoryVectorHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryRecallSelector} 的纯函数部分（对照 Go recall_trace.go 的
 * {@code mergeVectorHits} / {@code selectResidentInterests}）。
 *
 * <p>期望值全部是 Go 实录（{@code go test -run TestTruthMergeHits} /
 * {@code TestTruthResidentInterests}）。</p>
 */
class MemoryRecallSelectorTest {

    private static MemoryItem item(String id, String kind, String topic, String content) {
        MemoryItem it = new MemoryItem();
        it.setId(id);
        it.setKind(kind);
        it.setTopic(topic);
        it.setContent(content);
        it.setValidFrom(OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        return it;
    }

    private static List<String> ids(List<MemoryItem> items) {
        List<String> out = new ArrayList<>();
        for (MemoryItem it : items) {
            out.add(it == null ? "<nil>" : it.getId());
        }
        return out;
    }

    private static MemoryItem i1() {
        return item("i1", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL");
    }

    private static MemoryItem i2() {
        return item("i2", MemoryKinds.KIND_TASK, "在做的重构", "重构支付流程，计划本周完成");
    }

    private static MemoryItem i3() {
        return item("i3", MemoryKinds.KIND_INTEREST, "医疗影像后端", "医疗影像后端");
    }

    private static MemoryItem i5() {
        return item("i5", MemoryKinds.KIND_FACT, "", "PostgreSQL 17");
    }

    @Nested
    @DisplayName("mergeVectorHits")
    class MergeHits {

        @Test
        void growsThePoolWithMatchesItDidNotContain() {
            List<MemoryItem> pool = new ArrayList<>(List.of(i1(), i2(), i3()));
            List<MemoryVectorHit> hits = List.of(
                    new MemoryVectorHit(i2(), 0.9),
                    new MemoryVectorHit(i5(), 0.8), // 池子外
                    new MemoryVectorHit(i1(), 0.7),
                    new MemoryVectorHit(null, 0.6));

            MemoryRecallSelector.MergedHits merged =
                    MemoryRecallSelector.mergeVectorHits(pool, hits, null);

            // Go 实录：ranking [1,3,0]、pool ["i1","i2","i3","i5"]、added 1
            assertThat(merged.ranking()).containsExactly(1, 3, 0);
            assertThat(ids(merged.pool())).containsExactly("i1", "i2", "i3", "i5");
            assertThat(merged.added()).isEqualTo(1);
            // 池子外的匹配被**追加**到原池子的副本上，原列表不动
            assertThat(ids(pool)).containsExactly("i1", "i2", "i3");
        }

        @Test
        void excludedIdsAreDroppedBeforeTheyEnterTheRanking() {
            List<MemoryItem> pool = new ArrayList<>(List.of(i1(), i2(), i3()));
            List<MemoryVectorHit> hits = List.of(
                    new MemoryVectorHit(i2(), 0.9),
                    new MemoryVectorHit(i5(), 0.8),
                    new MemoryVectorHit(i1(), 0.7));

            MemoryRecallSelector.MergedHits merged =
                    MemoryRecallSelector.mergeVectorHits(pool, hits, Set.of("i1"));

            // Go 实录：ranking [1,3]、pool ["i1","i2","i3","i5"]、added 1
            assertThat(merged.ranking()).containsExactly(1, 3);
            assertThat(ids(merged.pool())).containsExactly("i1", "i2", "i3", "i5");
            assertThat(merged.added()).isEqualTo(1);
        }

        @Test
        void noHitsMeansNullRankingAndTheOriginalPool() {
            List<MemoryItem> pool = new ArrayList<>(List.of(i1(), i2(), i3()));
            MemoryRecallSelector.MergedHits merged =
                    MemoryRecallSelector.mergeVectorHits(pool, null, null);

            // Go 实录：ranking null、pool ["i1","i2","i3"]、added 0
            assertThat(merged.ranking()).isNull();
            assertThat(ids(merged.pool())).containsExactly("i1", "i2", "i3");
            assertThat(merged.added()).isZero();
        }
    }

    @Nested
    @DisplayName("selectResidentInterests")
    class ResidentInterests {

        @Test
        void reportsOnlyTheQuestionsOwnMatchesAndInjectsTheRestAsFiller() {
            // ⚠️ 不能用 List.of(i3(), null)：它拒绝 null 元素（Go 的切片可以有 nil 条目）。
            List<MemoryItem> interests = new ArrayList<>();
            interests.add(i3());
            interests.add(null);
            MemoryRecallSelector.Selected picked =
                    MemoryRecallSelector.selectResidentInterests("医疗影像的分割怎么调", interests, 5);

            // Go 实录：selected ["i3","<nil>"]、relevant ["i3"]
            // —— 上限还有位置，所以 nil 那条也被填进去了；但只有真的匹配上的进 relevant。
            assertThat(ids(picked.selected())).containsExactly("i3", "<nil>");
            assertThat(ids(picked.relevant())).containsExactly("i3");
        }

        @Test
        void fillsFromRepositoryOrderWhenNothingMatches() {
            // ⚠️ 不能用 List.of(i3(), null)：它拒绝 null 元素（Go 的切片可以有 nil 条目）。
            List<MemoryItem> interests = new ArrayList<>();
            interests.add(i3());
            interests.add(null);
            MemoryRecallSelector.Selected picked =
                    MemoryRecallSelector.selectResidentInterests("完全无关的问题", interests, 1);

            // Go 实录：selected ["i3"]、relevant []
            assertThat(ids(picked.selected())).containsExactly("i3");
            assertThat(picked.relevant()).isEmpty();
        }

        @Test
        void emptyInputsProduceEmptyOutputs() {
            // Go 实录：selected []、relevant []
            MemoryRecallSelector.Selected picked =
                    MemoryRecallSelector.selectResidentInterests("q", null, 5);
            assertThat(picked.selected()).isNull();
            assertThat(picked.relevant()).isNull();

            picked = MemoryRecallSelector.selectResidentInterests("q", List.of(i3()), 0);
            assertThat(picked.selected()).isNull();
        }
    }
}
