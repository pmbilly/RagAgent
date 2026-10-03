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

/**
 * move 的 reparse 模式的 H2 钉子。覆盖 {@link com.ragagent.knowledge.service.KnowledgeMoveService}
 * 的 {@code moveKnowledgeReparse} + {@code enqueueMovedKnowledge}：
 *
 * <ol>
 *   <li>源侧资源清理——向量行、chunks 行、源图谱命名空间；</li>
 *   <li>标签关联清空（标签是 KB 作用域的）；</li>
 *   <li>行改写到目标 KB 的待解析态（kb/嵌入模型/parse_status/error/enable/description/
 *       processed_at/storage_size）并按 delta 扣减租户存储用量；</li>
 *   <li>重新解析入队（目标 KB 的配置在重新解析时生效）。</li>
 * </ol>
 *
 * <p>断言只钉 worker 不会改写的列（kb_id / embedding_model_id / description / storage_size /
 * 资源删除），parse_status 只要求"已从 completed 复位"——入队后的解析是异步的，在 H2 里必然
 * 失败（无模型/无文件），失败路径只改 parse_status 与 error_message。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeMoveReparseTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KnowledgeService knowledgeService;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("INSERT INTO tenants (id, name, storage_used) VALUES (?, 't', 1000)", TENANT);
        TenantContext.set(TENANT, TenantContext.webUserPrincipal("user-1"), "owner", false,
                "user-1", false);
    }

    private String kb(String id, String name, String embeddingModelId) {
        jdbc.update("INSERT INTO knowledge_bases (id, tenant_id, name, type, description, "
                        + "embedding_model_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'document', '', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, TENANT, name, embeddingModelId);
        return id;
    }

    private String knowledge(String kbId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, "
                        + "parse_status, embedding_model_id, description, storage_size, "
                        + "enable_status, summary_status, folder_path, processed_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'file', 'doc', 'file', 'completed', 'emb-0', "
                        + "'old description', 1000, 'enabled', 'none', '', CURRENT_TIMESTAMP, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, TENANT, kbId);
        return id;
    }

    private void insertChunk(String id, String knowledgeId, String kbId) {
        jdbc.update("INSERT INTO chunks (id, tenant_id, knowledge_id, knowledge_base_id, content, "
                        + "chunk_index, is_enabled, chunk_type, start_at, end_at, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'body', 0, TRUE, 'text', 0, 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, TENANT, knowledgeId, kbId);
    }

    private void insertEmbedding(String sourceId, String chunkId, String knowledgeId, String kbId) {
        jdbc.update("INSERT INTO embeddings (created_at, updated_at, source_id, source_type, "
                        + "chunk_id, knowledge_id, knowledge_base_id, tag_id, content, is_enabled, "
                        + "dimension, embedding) VALUES (CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                        + "?, 0, ?, ?, ?, '', 'x', TRUE, 2, '[1,2]')",
                sourceId, chunkId, knowledgeId, kbId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void reparseMoveCleansSourceAndRewritesRowForTargetKb() {
        String srcKb = UUID.randomUUID().toString();
        String dstKb = UUID.randomUUID().toString();
        kb(srcKb, "src", "emb-1");
        kb(dstKb, "dst", "emb-1");
        String kId = knowledge(srcKb);
        String c1 = UUID.randomUUID().toString();
        String c2 = UUID.randomUUID().toString();
        insertChunk(c1, kId, srcKb);
        insertChunk(c2, kId, srcKb);
        insertEmbedding(c1, c1, kId, srcKb);
        insertEmbedding(kId + "-q1", c1, kId, srcKb);
        jdbc.update("INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) VALUES (?, ?)",
                kId, "tag-1");

        knowledgeService.startKnowledgeMove(TENANT, "mv-rep", List.of(kId), srcKb, dstKb, "reparse");
        awaitMoveSettled("mv-rep", kId, dstKb);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT knowledge_base_id, embedding_model_id, parse_status, description, "
                        + "storage_size, processed_at FROM knowledges WHERE id = ?", kId);
        // 1) 行落在目标 KB 的待解析态（Go moveKnowledgeReparse 的字段集）
        assertThat(row.get("KNOWLEDGE_BASE_ID")).isEqualTo(dstKb);
        assertThat(row.get("EMBEDDING_MODEL_ID")).isEqualTo("emb-1").as("嵌入模型随目标 KB");
        assertThat(row.get("DESCRIPTION")).isEqualTo("");
        assertThat(((Number) row.get("STORAGE_SIZE")).longValue()).isZero();
        assertThat(row.get("PROCESSED_AT")).isNull();
        assertThat(row.get("PARSE_STATUS")).isNotEqualTo("completed").as("重新解析已复位");
        // 2) 源侧资源清空（向量 + chunks + 标签关联）
        assertThat(count("SELECT COUNT(*) FROM embeddings WHERE knowledge_id = ?", kId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM chunks WHERE knowledge_id = ? AND deleted_at IS NULL",
                kId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM knowledge_tag_relations WHERE knowledge_id = ?", kId))
                .isZero();
        // 3) 租户存储用量按 delta 扣减（UpdateKnowledgeForTransfer 的 storage_used += after-before）
        assertThat(count("SELECT storage_used FROM tenants WHERE id = ?", TENANT)).isZero();
        // 4) 进度终态
        var progress = knowledgeService.getKnowledgeMoveProgress("mv-rep");
        assertThat(progress).isNotNull();
        assertThat(progress.status()).isEqualTo("completed");
    }

    @Test
    void reparseMoveKeepsZeroStorageUntouched() {
        String srcKb = UUID.randomUUID().toString();
        String dstKb = UUID.randomUUID().toString();
        kb(srcKb, "src", "emb-1");
        kb(dstKb, "dst", "emb-1");
        String kId = knowledge(srcKb);
        jdbc.update("UPDATE knowledges SET storage_size = 0 WHERE id = ?", kId);

        knowledgeService.startKnowledgeMove(TENANT, "mv-rep2", List.of(kId), srcKb, dstKb, "reparse");
        awaitMoveSettled("mv-rep2", kId, dstKb);

        assertThat(count("SELECT storage_used FROM tenants WHERE id = ?", TENANT))
                .isEqualTo(1000).as("storage_size=0 时不动租户用量");
        assertThat(jdbc.queryForObject("SELECT knowledge_base_id FROM knowledges WHERE id = ?",
                String.class, kId)).isEqualTo(dstKb);
    }

    /**
     * 等这条搬移落地：行已挂到目标 KB（kb_id 改写与 parse_status=pending 是同一条 UPDATE，
     * 故 kb_id 到目标即证明行改写已提交）。入队后的异步解析随后自行推进 parse_status。
     */
    private void awaitMoveSettled(String taskId, String knowledgeId, String targetKbId) {
        long deadline = System.currentTimeMillis() + 10_000;
        String currentKb = null;
        while (System.currentTimeMillis() < deadline) {
            currentKb = jdbc.queryForObject(
                    "SELECT knowledge_base_id FROM knowledges WHERE id = ?", String.class,
                    knowledgeId);
            if (targetKbId.equals(currentKb)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("move did not settle in time; kb_id=" + currentKb
                + " move=" + knowledgeService.getKnowledgeMoveProgress(taskId));
    }
}
