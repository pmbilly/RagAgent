package com.ragagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.TestSchema;
import com.ragagent.common.context.TenantContext;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.mapper.KnowledgeMapper;

/**
 * 写链改道（W5γ4.6 follow-up）的 H2 钉子：move 的 reuse_vectors 模式把 embeddings 行
 * 的 knowledge_base_id 原地改写并清 tag_id（move.go 的 UPDATE 语义）；KB clone 经
 * CopyIndices 复制向量行（三态 SourceID 改写：本块 → 目标 chunkID、生成问题保留 qid）。
 * 未绑定 KB 走 postgres 语义直连（不经引擎注册表）——两种绑定状态下行为一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeVectorRoutingTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KnowledgeService knowledgeService;
    @Autowired
    private KnowledgeBaseMapper kbMapper;
    @Autowired
    private KnowledgeMapper knowledgeMapper;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        TenantContext.set(TENANT, TenantContext.webUserPrincipal("user-1"), "owner", false, "user-1", false);
    }

    private KnowledgeBase kb(String id, String name) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setName(name);
        k.setTenantId(TENANT);
        k.setType("document");
        kbMapper.insert(k);
        return k;
    }

    private String knowledge(String id, String kbId, String embeddingModelId) {
        com.ragagent.knowledge.domain.Knowledge k = new com.ragagent.knowledge.domain.Knowledge();
        k.setId(id);
        k.setTenantId(TENANT);
        k.setKnowledgeBaseId(kbId);
        k.setType("file");
        k.setTitle("doc " + id);
        k.setEmbeddingModelId(embeddingModelId);
        knowledgeMapper.insert(k);
        return id;
    }

    private void insertEmbedding(String sourceId, String chunkId, String knowledgeId, String kbId,
                                 String tagId) {
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "?, 0, ?, ?, ?, ?, 'x', TRUE, 2, '[1,2]')", sourceId, chunkId, knowledgeId,
                kbId, tagId);
    }

    @Test
    void moveWithReuseVectorsRelocatesEmbeddingRows() {
        String srcKb = UUID.randomUUID().toString();
        String dstKb = UUID.randomUUID().toString();
        kb(srcKb, "src");
        kb(dstKb, "dst");
        // Go validateMoveItem：knowledge 的嵌入模型必须与源 KB 一致
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'emb-1' WHERE id = ?", srcKb);
        String kId = UUID.randomUUID().toString();
        knowledge(kId, srcKb, "emb-1");
        insertEmbedding(kId, "c1", kId, srcKb, "old-tag");
        insertEmbedding(kId + "-q1", "c1", kId, srcKb, "");

        knowledgeService.startKnowledgeMove(TENANT, "mv-1", List.of(kId), srcKb, dstKb,
                "reuse_vectors");
        awaitProgress("mv-1", () -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Integer.class, dstKb);
            return count != null && count == 2;
        });

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT knowledge_base_id, tag_id FROM embeddings WHERE knowledge_id = ? "
                        + "ORDER BY source_id", kId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("KNOWLEDGE_BASE_ID")).isEqualTo(dstKb).as("move.go：knowledge_base_id 改写");
            assertThat(r.get("TAG_ID")).isEqualTo("").as("move.go：tag_id 清空");
        });
        Integer leftovers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Integer.class, srcKb);
        assertThat(leftovers).isEqualTo(0);
    }

    @Test
    void cloneCopiesEmbeddingRowsWithRewrittenIds() {
        String srcKb = UUID.randomUUID().toString();
        String dstKb = UUID.randomUUID().toString();
        KnowledgeBase source = kb(srcKb, "src");
        KnowledgeBase dst = kb(dstKb, "dst");
        dst.setEmbeddingModelId("emb-1");
        jdbc.update("UPDATE knowledge_bases SET embedding_model_id = 'emb-1' WHERE id = ?", dstKb);
        String kId = UUID.randomUUID().toString();
        knowledge(kId, srcKb, "emb-1");
        // clone preflight：源知识必须 completed（Go knowledgeClonePlan）
        jdbc.update("UPDATE knowledges SET parse_status = 'completed' WHERE id = ?", kId);
        jdbc.update("INSERT INTO chunks (id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_index, is_enabled, chunk_type, start_at, end_at, "
                + "created_at, updated_at) "
                + "VALUES ('c1', ?, ?, ?, 'body', 0, TRUE, 'text', 0, 0, CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP)", TENANT, kId, srcKb);
        // 常规 chunk 的 source_id == chunk_id（Go CopyIndices 三态改写的"本块"分支）
        insertEmbedding("c1", "c1", kId, srcKb, "");

        knowledgeService.startKBClone(TENANT, "clone-1", srcKb, dstKb, false, "user-1");
        awaitProgress("clone-1", () -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Integer.class, dstKb);
            return count != null && count == 1;
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_id, chunk_id, knowledge_id, knowledge_base_id, tag_id "
                        + "FROM embeddings WHERE knowledge_base_id = ?", dstKb);
        // 目标行：knowledge_id 改写为克隆出的新知识 id；source_id/chunk_id 改写为目标
        // chunkID；tag_id 不复制（照 GORM 列集）；is_enabled 省略（H2 列无默认值为 NULL）
        List<String> dstKnowledgeIds = jdbc.queryForList(
                "SELECT id FROM knowledges WHERE knowledge_base_id = ?", String.class, dstKb);
        System.out.printf("[diag] embeddings row=%s dstKnowledgeIds=%s sourceKbId=%s dstKbId=%s%n",
                row, dstKnowledgeIds, srcKb, dstKb);
        assertThat(dstKnowledgeIds).as("dst 知识行数").hasSize(1);
        assertThat(row.get("KNOWLEDGE_ID")).isEqualTo(dstKnowledgeIds.get(0));
        assertThat(row.get("SOURCE_ID")).isEqualTo(row.get("CHUNK_ID")).as("本块 → 目标 chunkID");
        assertThat(row.get("KNOWLEDGE_BASE_ID")).isEqualTo(dstKb);
        assertThat(row.get("TAG_ID")).isEqualTo("");
        // 源行不动
        Integer srcRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Integer.class, srcKb);
        assertThat(srcRows).isEqualTo(1);
        assertThat(source.getId()).isEqualTo(srcKb);
    }

    private void awaitProgress(String taskId, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        Object move = knowledgeService.getKnowledgeMoveProgress(taskId);
        Object clone = knowledgeService.getKBCloneProgress(taskId);
        throw new IllegalStateException("async vector routing did not settle in time; move=" + move
                + " clone=" + clone);
    }
}
