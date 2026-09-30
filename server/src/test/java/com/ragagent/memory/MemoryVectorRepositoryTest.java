package com.ragagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.ragagent.TestSchema;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryItemEmbedding;
import com.ragagent.common.settings.MemoryKinds;
import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemoryVectorHit;
import com.ragagent.memory.domain.MemoryVectorQuery;
import com.ragagent.memory.domain.MemoryVectors;
import com.ragagent.memory.mapper.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量存取与语义召回（对照 Go internal/application/repository/memory_vector.go）。
 *
 * <p><b>测试恒走内存兜底路径</b>：H2 上没有 pgvector（{@code memory_item_embeddings.embedding}
 * 列不存在），{@code vectorColumnReady()} 为 false——与 Go 在 SQLite 上的情形一致。
 * SQL 排名那条路（{@code <=>} + {@code halfvec}）在测试里**不可达**，已在
 * {@code MemoryItemEmbeddingMapper.rankInDatabase} 的注释里标明。</p>
 *
 * <p>要钉住的核心语义是：<b>候选集是这个主体拥有的全部向量，不是"先按重要度挑一批"</b>。
 * 那正是这一版修掉的 bug——重要度与相关性无关，窗口之外一条匹配的记忆根本够不到。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoryVectorRepositoryTest {

    private static final long TENANT = 10002L;
    private static final String MODEL = "embed-1";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemoryRepository repo;

    private MemoryScope scope;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        for (String table : List.of("memory_item_embeddings", "memory_extraction_sessions",
                "memory_items", "memory_tombstones", "memory_topic_stats", "memory_doc_affinity",
                "memory_subjects")) {
            jdbc.execute("DELETE FROM " + table);
        }
        scope = new MemoryScope(TENANT, "web_user:u1");
        repo.ensureSubject(scope);
    }

    private MemoryItem newItem(String content, String kind, int importance) {
        MemoryItem item = new MemoryItem();
        item.setTenantId(TENANT);
        item.setSubjectId(scope.subjectId());
        item.setKind(kind);
        item.setContent(content);
        item.setTopic("t");
        item.setNormalizedKey("k-" + content);
        item.setImportance(importance);
        item.setValidFrom(OffsetDateTime.now());
        repo.createItem(item);
        return item;
    }

    private MemoryItemEmbedding embedding(MemoryItem item, float[] vector) {
        MemoryItemEmbedding e = new MemoryItemEmbedding();
        e.setItemId(item.getId());
        e.setModelId(MODEL);
        e.setDims(vector.length);
        e.setVector(MemoryVectors.encodeEmbedding(vector));
        return e;
    }

    // ── 写 ─────────────────────────────────────────────────────────────────

    @Test
    void upsertItemEmbeddingWritesTheBlobAndReadsItBack() {
        MemoryItem item = newItem("记忆一", MemoryKinds.KIND_FACT, 3);
        float[] vector = {1.0f, 0.0f, 0.0f};
        repo.upsertItemEmbedding(scope, embedding(item, vector));

        Map<String, float[]> vectors = repo.itemEmbeddings(scope, List.of(item.getId()), MODEL);
        assertThat(vectors).containsOnlyKeys(item.getId());
        assertThat(vectors.get(item.getId())).containsExactly(vector);
    }

    @Test
    void upsertItemEmbeddingShortCircuitsOnBadInput() {
        MemoryItem item = newItem("x", MemoryKinds.KIND_FACT, 3);

        repo.upsertItemEmbedding(scope, null);
        MemoryItemEmbedding noVector = embedding(item, new float[0]);
        repo.upsertItemEmbedding(scope, noVector);
        MemoryItemEmbedding noItem = embedding(item, new float[]{1.0f});
        noItem.setItemId("");
        repo.upsertItemEmbedding(scope, noItem);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).isZero();
    }

    /** 第二次 upsert 是**覆盖**（PG 的 {@code ON CONFLICT DO UPDATE}；H2 侧用先删后插等价）。 */
    @Test
    void upsertItemEmbeddingOverwritesThePreviousVector() {
        MemoryItem item = newItem("记忆一", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f, 0.0f}));
        repo.upsertItemEmbedding(scope, embedding(item, new float[]{0.0f, 1.0f}));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).isEqualTo(1);
        assertThat(repo.itemEmbeddings(scope, List.of(item.getId()), MODEL).get(item.getId()))
                .containsExactly(0.0f, 1.0f);
    }

    /**
     * 输入快照守卫：条目内容或主题在 embedding 期间被改过时**放弃写入**
     * ——免得一个慢的 embedding 调用覆盖掉更新的编辑。
     */
    @Test
    void upsertItemEmbeddingAbandonsWhenTheItemChangedSinceTheSnapshot() {
        MemoryItem item = newItem("旧内容", MemoryKinds.KIND_FACT, 3);

        MemoryItemEmbedding stale = embedding(item, new float[]{1.0f, 0.0f});
        stale.setSourceContent("旧内容");
        stale.setSourceTopic("t");
        // 快照对得上 → 写入
        repo.upsertItemEmbedding(scope, stale);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).isEqualTo(1);

        // 条目内容被改掉了（`updateItemContent` 顺带把旧向量删了——这是另一条语义）
        repo.updateItemContent(scope, item.getId(), "新内容", "k2", 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).isZero();

        // 这时一个"还是按旧内容算出来的"向量到了，快照对不上 → 整条丢弃
        MemoryItemEmbedding mismatch = embedding(item, new float[]{0.0f, 1.0f});
        mismatch.setSourceContent("旧内容");
        mismatch.setSourceTopic("t");
        repo.upsertItemEmbedding(scope, mismatch);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).as("内容对不上就不该写入").isZero();
    }

    /** 快照与当前条目完全一致时照常写入。 */
    @Test
    void upsertItemEmbeddingWritesWhenTheSnapshotStillMatches() {
        MemoryItem item = newItem("内容", MemoryKinds.KIND_FACT, 3);
        MemoryItemEmbedding e = embedding(item, new float[]{1.0f});
        e.setSourceContent("内容");
        e.setSourceTopic("t");
        repo.upsertItemEmbedding(scope, e);

        assertThat(repo.itemEmbeddings(scope, List.of(item.getId()), MODEL)).isNotEmpty();
    }

    @Test
    void deleteItemEmbeddingDropsTheVector() {
        MemoryItem item = newItem("x", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f}));

        repo.deleteItemEmbedding(scope, item.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_item_embeddings",
                Integer.class)).isZero();

        repo.deleteItemEmbedding(scope, "");
        repo.deleteItemEmbedding(scope, null);
    }

    /** 只返回**同一个模型**产出的向量：不同模型的向量不可比。 */
    @Test
    void itemEmbeddingsFiltersByModelAndShortCircuits() {
        MemoryItem item = newItem("x", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f}));

        assertThat(repo.itemEmbeddings(scope, List.of(item.getId()), "another-model")).isEmpty();
        assertThat(repo.itemEmbeddings(scope, List.of(), MODEL)).isNull();
        assertThat(repo.itemEmbeddings(scope, null, MODEL)).isNull();
        assertThat(repo.itemEmbeddings(scope, List.of(item.getId()), "")).isNull();
    }

    @Test
    void itemsMissingEmbeddingsReturnsTheBacklogOnly() {
        MemoryItem withVector = newItem("有向量", MemoryKinds.KIND_FACT, 3);
        MemoryItem without = newItem("没向量", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(withVector, new float[]{1.0f}));

        assertThat(repo.itemsMissingEmbeddings(scope, MODEL, 0))
                .extracting(MemoryItem::getId).containsExactly(without.getId());

        // 别的模型的向量不算数——补扫要按当前模型重建
        assertThat(repo.itemsMissingEmbeddings(scope, "other-model", 0))
                .extracting(MemoryItem::getId)
                .containsExactlyInAnyOrder(withVector.getId(), without.getId());
    }

    // ── 语义召回 ───────────────────────────────────────────────────────────

    @Test
    void searchItemsByVectorRanksByCosineAndHonoursMinScore() {
        MemoryItem near = newItem("接近的", MemoryKinds.KIND_FACT, 1);
        MemoryItem far = newItem("远的", MemoryKinds.KIND_FACT, 5);
        repo.upsertItemEmbedding(scope, embedding(near, new float[]{1.0f, 0.0f}));
        repo.upsertItemEmbedding(scope, embedding(far, new float[]{0.0f, 1.0f}));

        MemoryVectorQuery query = new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f},
                List.of(), 0.5, 10);
        List<MemoryVectorHit> hits = repo.searchItemsByVector(scope, query);

        assertThat(hits).extracting(h -> h.item().getId()).containsExactly(near.getId());
        assertThat(hits.get(0).score()).isEqualTo(1.0);
    }

    /**
     * <b>本模块最关键的一条</b>：候选集是主体的全部向量，与重要度无关。
     *
     * <p>旧实现按重要度列出一批条目再给它们打分，于是重要度低但匹配的记忆永远够不到。</p>
     */
    @Test
    void searchItemsByVectorIgnoresImportanceEntirely() {
        MemoryItem unimportantButMatching = newItem("重要度最低但最匹配", MemoryKinds.KIND_FACT, 1);
        for (int i = 0; i < 20; i++) {
            MemoryItem important = newItem("重要度高但无关" + i, MemoryKinds.KIND_FACT, 5);
            repo.upsertItemEmbedding(scope, embedding(important, new float[]{0.0f, 1.0f}));
        }
        repo.upsertItemEmbedding(scope, embedding(unimportantButMatching, new float[]{1.0f, 0.0f}));

        List<MemoryVectorHit> hits = repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.5, 5));

        assertThat(hits).extracting(h -> h.item().getId())
                .containsExactly(unimportantButMatching.getId());
    }

    @Test
    void searchItemsByVectorHonoursKindsAndLimit() {
        for (int i = 0; i < 3; i++) {
            MemoryItem item = newItem("事实" + i, MemoryKinds.KIND_FACT, 3);
            repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f, 0.0f}));
        }
        MemoryItem profile = newItem("画像", MemoryKinds.KIND_PROFILE, 3);
        repo.upsertItemEmbedding(scope, embedding(profile, new float[]{1.0f, 0.0f}));

        List<MemoryVectorHit> onlyFacts = repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f},
                        List.of(MemoryKinds.KIND_FACT), 0.5, 2));
        assertThat(onlyFacts).hasSize(2);
        assertThat(onlyFacts).allSatisfy(h ->
                assertThat(h.item().getKind()).isEqualTo(MemoryKinds.KIND_FACT));

        List<MemoryVectorHit> allKinds = repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.5, 10));
        assertThat(allKinds).hasSize(4);
    }

    /** 不同 {@code model_id}、不同 {@code dims} 的向量都不参与打分。 */
    @Test
    void searchItemsByVectorSkipsOtherModelsAndDims() {
        MemoryItem otherModel = newItem("别的模型", MemoryKinds.KIND_FACT, 3);
        MemoryItemEmbedding e = embedding(otherModel, new float[]{1.0f, 0.0f});
        e.setModelId("other-model");
        repo.upsertItemEmbedding(scope, e);

        MemoryItem otherDims = newItem("别的维度", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(otherDims, new float[]{1.0f, 0.0f, 0.0f}));

        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.0, 10)))
                .isNull();
    }

    /** 已过期（{@code expires_at} 已过）的条目不该被召回。 */
    @Test
    void searchItemsByVectorSkipsExpiredItems() {
        MemoryItem expired = newItem("过期的", MemoryKinds.KIND_FACT, 3);
        // createItem 会写全部列，所以到期时间要在插入之后单独改（Go 侧同样由上层写）
        jdbc.update("UPDATE memory_items SET expires_at = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1), expired.getId());
        repo.upsertItemEmbedding(scope, embedding(expired, new float[]{1.0f, 0.0f}));

        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.0, 10)))
                .isNull();
    }

    @Test
    void searchItemsByVectorShortCircuitsOnUnusableInput() {
        assertThat(repo.searchItemsByVector(new MemoryScope(0, ""),
                new MemoryVectorQuery(MODEL, new float[]{1.0f}, List.of(), 0.0, 10))).isNull();
        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery("", new float[]{1.0f}, List.of(), 0.0, 10))).isNull();
        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[0], List.of(), 0.0, 10))).isNull();
    }

    /** 分数低于 {@code minScore} 的一条都不留 → 回 {@code null}（不是空列表）。 */
    @Test
    void searchItemsByVectorReturnsNullWhenNothingClearsTheFloor() {
        MemoryItem orthogonal = newItem("正交", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(orthogonal, new float[]{0.0f, 1.0f}));

        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.9, 10)))
                .isNull();
    }

    /** {@code limit <= 0} → 20（Go 的 {@code query.Limit} 默认值）。 */
    @Test
    void searchItemsByVectorUsesTheDefaultLimit() {
        for (int i = 0; i < 25; i++) {
            MemoryItem item = newItem("条目" + i, MemoryKinds.KIND_FACT, 3);
            repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f, 0.0f}));
        }
        assertThat(repo.searchItemsByVector(scope,
                new MemoryVectorQuery(MODEL, new float[]{1.0f, 0.0f}, List.of(), 0.0, 0)))
                .hasSize(20);
    }

    // ── pgvector 列 ────────────────────────────────────────────────────────

    /**
     * H2 上没有安装 pgvector（{@code embedding} 列不存在），
     * 所以 {@code SyncVectorColumn} 是**空操作**——与 Go 在 SQLite / 无 pgvector 的
     * PostgreSQL 上的行为一致：功能不丢，只是每个向量都要过一遍网络。
     */
    @Test
    void syncVectorColumnIsANoOpWithoutThePgvectorColumn() {
        MemoryItem item = newItem("x", MemoryKinds.KIND_FACT, 3);
        repo.upsertItemEmbedding(scope, embedding(item, new float[]{1.0f}));

        assertThat(repo.syncVectorColumn(scope, 0)).isZero();
        assertThat(repo.syncVectorColumn(new MemoryScope(0, ""), 100)).isZero();
    }
}
