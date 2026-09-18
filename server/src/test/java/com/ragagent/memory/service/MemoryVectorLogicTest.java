package com.ragagent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryKinds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryVectorService} 的纯函数部分（对照 Go vector.go 的
 * {@code fuseRankings} / {@code vectorFanout} / {@code embeddableText}）。
 *
 * <p>期望值全部是 Go 实录（{@code go test -run TestTruthVector}）。</p>
 */
class MemoryVectorLogicTest {

    private static MemoryItem item(String id, String kind, String topic, String content, int importance) {
        MemoryItem it = new MemoryItem();
        it.setId(id);
        it.setKind(kind);
        it.setTopic(topic);
        it.setContent(content);
        it.setImportance(importance);
        it.setValidFrom(OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        return it;
    }

    @Test
    @DisplayName("fuseRankings：先按分数降序，同分保持首次出现的次序")
    void fuseRankings() {
        // Go 实录：[0,2,4,1,3]
        assertThat(MemoryVectorService.fuseRankings(List.of(2, 0, 1), List.of(4, 0, 3)))
                .containsExactly(0, 2, 4, 1, 3);
        // 单侧时退化成原顺序（Go 实录：[2,0,1]）
        assertThat(MemoryVectorService.fuseRankings(List.of(2, 0, 1), null))
                .containsExactly(2, 0, 1);
        assertThat(MemoryVectorService.fuseRankings(null, List.of(2, 0, 1)))
                .containsExactly(2, 0, 1);
        // 两边都第一名：分数相加仍然第一（Go 实录：[0]）
        assertThat(MemoryVectorService.fuseRankings(List.of(0), List.of(0)))
                .containsExactly(0);
        assertThat(MemoryVectorService.fuseRankings(null, null)).isEmpty();
    }

    @Test
    @DisplayName("vectorFanout：四倍输出、下限 20")
    void vectorFanout() {
        // Go 实录：1→20、3→20、5→20、20→80
        assertThat(MemoryRecallSelector.vectorFanout(1)).isEqualTo(20);
        assertThat(MemoryRecallSelector.vectorFanout(3)).isEqualTo(20);
        assertThat(MemoryRecallSelector.vectorFanout(5)).isEqualTo(20);
        assertThat(MemoryRecallSelector.vectorFanout(20)).isEqualTo(80);
    }

    @Test
    @DisplayName("embeddableText：主题+内容拼接、兴趣不自我重复、别名去重去空")
    void embeddableText() {
        MemoryItem fact = item("i1", MemoryKinds.KIND_FACT, "在用的数据库", "生产库用的是 MySQL", 3);
        // Go 实录："在用的数据库：生产库用的是 MySQL"
        assertThat(MemoryVectorService.embeddableText(fact, null))
                .isEqualTo("在用的数据库：生产库用的是 MySQL");

        // 兴趣的主题与内容相同 → 不拼出 "X：X"；别名去重（含与主题同名的那个）并丢掉空白项
        // Go 实录："医疗影像后端；医疗影像；医学影像"
        MemoryItem interest = item("i3", MemoryKinds.KIND_INTEREST, "医疗影像后端", "医疗影像后端", 3);
        assertThat(MemoryVectorService.embeddableText(interest,
                List.of("医疗影像", "医疗影像后端", "  ", "医学影像")))
                .isEqualTo("医疗影像后端；医疗影像；医学影像");

        // 没有主题时只用内容（Go 实录："PostgreSQL 17"）
        MemoryItem noTopic = item("i5", MemoryKinds.KIND_FACT, "", "PostgreSQL 17", 2);
        assertThat(MemoryVectorService.embeddableText(noTopic, null)).isEqualTo("PostgreSQL 17");

        assertThat(MemoryVectorService.embeddableText(null, null)).isEmpty();
    }

    @Test
    @DisplayName("embedder：三层前置短路（未启用 / 未配模型 / 没配就关）")
    void embedderShortCircuits() {
        MemoryVectorService svc = new MemoryVectorService(
                org.mockito.Mockito.mock(com.ragagent.memory.mapper.MemoryRepository.class),
                org.mockito.Mockito.mock(MemoryModelResolver.class));
        assertThat(svc.embedder(null)).isNull();

        com.ragagent.memory.domain.MemoryConfig off = new com.ragagent.memory.domain.MemoryConfig();
        assertThat(svc.embedder(off)).isNull();

        com.ragagent.memory.domain.MemoryConfig onButBlank = new com.ragagent.memory.domain.MemoryConfig();
        onButBlank.setEnabled(true);
        onButBlank.setEmbeddingModelId("");
        onButBlank.setVectorRecall(true);
        assertThat(svc.embedder(onButBlank)).isNull();

        com.ragagent.memory.domain.MemoryConfig on = new com.ragagent.memory.domain.MemoryConfig();
        on.setEnabled(true);
        on.setEmbeddingModelId("emb-1");
        on.setVectorRecall(true);
        assertThat(svc.embedder(on)).isEqualTo("emb-1");

        // vector_recall 显式 false → 关（null 才是"有模型就开"）
        com.ragagent.memory.domain.MemoryConfig explicitlyOff = new com.ragagent.memory.domain.MemoryConfig();
        explicitlyOff.setEnabled(true);
        explicitlyOff.setEmbeddingModelId("emb-1");
        explicitlyOff.setVectorRecall(false);
        assertThat(svc.embedder(explicitlyOff)).isNull();
    }
}
