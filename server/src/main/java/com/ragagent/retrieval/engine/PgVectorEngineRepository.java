package com.ragagent.retrieval.engine;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import com.ragagent.common.pipeline.SearchParams;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.IndexWithScore;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * postgres 引擎仓库的引擎口适配器——让 postgres 以引擎身份进入注册表
 * （env-store，{@code RETRIEVE_DRIVER} 含 {@code postgres} 时由
 * {@code RetrievalEngineWiringConfig} 注册），读路径委托既有
 * {@link PgVectorRetrieveRepository}（SQL 由契约测试锁定），写路径委托
 * {@link VectorStoreService}（ON CONFLICT DO NOTHING / MERGE 双方言件）——
 * 行为与既有直连路径逐字节一致，不复制 SQL。
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li><b>Save 的冲突语义</b>：统一走 {@code saveIndexRows}（PG ON CONFLICT DO NOTHING /
 *       H2 MERGE，与 BatchSave 一致）。生产链路对 postgres 只走 BatchIndex（KV 服务分批），
 *       单条 Save 无调用方，冲突不可达。</li>
 *   <li>{@code toDBVectorEmbedding} 的 {@code CleanInvalidUtf8} 在 Java 侧是
 *       no-op（String 恒为合法 UTF-16）。</li>
 *   <li><b>CopyIndices 不复制 is_enabled</b>：目标行 INSERT 显式省略该列 →
 *       DB 默认 true 生效。chunk/knowledge 映射缺失时跳过该行（记 WARN 继续）；
 *       SourceID 三态改写（本块 / 生成问题 {@code <chunk>-<qid>} / 兜底新 UUID）。</li>
 *   <li>{@code additionalParams} 的 {@code embedding}（按 SourceID）与
 *       {@code chunk_enabled}（按 ChunkID）两键决定取值。</li>
 *   <li>检索命中按 {@code fromDBVectorEmbeddingWithScore} 的字段集映射
 *       （IsEnabled 不拷，保持缺省 false）。</li>
 * </ul>
 */
public class PgVectorEngineRepository
        implements RetrieveEngineRepository, RetrieveEngineRepository.KnowledgeIndexMover {

    private static final Logger log = LoggerFactory.getLogger(PgVectorEngineRepository.class);

    /** CopyIndices 的分页批大小。 */
    static final int COPY_BATCH_SIZE = 500;

    private final PgVectorRetrieveRepository readRepo;
    private final VectorStoreService writeSvc;
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public PgVectorEngineRepository(PgVectorRetrieveRepository readRepo, VectorStoreService writeSvc,
                                    DataSource dataSource) {
        this.readRepo = readRepo;
        this.writeSvc = writeSvc;
        this.jdbc = new JdbcTemplate(dataSource);
        boolean pg = false;
        try (var conn = dataSource.getConnection()) {
            pg = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("detect database product failed, assume non-postgres: {}", e.toString());
        }
        this.postgres = pg;
    }

    @Override
    public String engineType() {
        return EngineTypes.ENGINE_POSTGRES;
    }

    /** 支持 keywords + vector。 */
    @Override
    public List<String> support() {
        return List.of(EngineTypes.RETRIEVER_KEYWORDS, EngineTypes.RETRIEVER_VECTOR);
    }

    // ── 写入 ────────────────────────────────────────────────────────────────

    @Override
    public void save(IndexInfo indexInfo, Map<String, Object> params) throws Exception {
        List<IndexInfo> one = List.of(indexInfo);
        batchSave(one, params);
    }

    /** 逐行映射后批量落库（冲突不覆盖）。 */
    @Override
    public void batchSave(List<IndexInfo> indexInfoList, Map<String, Object> params) throws Exception {
        Map<String, float[]> embeddings = embeddingMap(params);
        Map<String, Boolean> chunkEnabled = chunkEnabledMap(params);
        List<VectorStoreService.IndexRow> rows = new ArrayList<>(indexInfoList.size());
        List<float[]> vectors = new ArrayList<>(indexInfoList.size());
        for (IndexInfo info : indexInfoList) {
            float[] vector = embeddings.get(info.sourceId);
            boolean enabled = info.isEnabled;
            if (chunkEnabled != null && chunkEnabled.containsKey(info.chunkId)) {
                enabled = chunkEnabled.get(info.chunkId);
            }
            rows.add(new VectorStoreService.IndexRow(info.sourceId, info.chunkId, info.knowledgeId,
                    info.knowledgeBaseId, info.content, enabled, info.tagId));
            vectors.add(vector == null ? new float[0] : vector);
        }
        writeSvc.saveIndexRows(rows, vectors);
    }

    /**
     * 存储估算：
     * content 字节 + 维度×2（halfvec）+ 200 元数据开销 + 2×向量字节（HNSW 开销）。
     * 维度按<b>SourceID</b> 查 embedding 表。
     */
    @Override
    public long estimateStorageSize(List<IndexInfo> indexInfoList, Map<String, Object> params) {
        Map<String, float[]> embeddings = embeddingMap(params);
        long total = 0;
        for (IndexInfo info : indexInfoList) {
            float[] vector = embeddings.get(info.sourceId);
            int dimension = vector == null ? 0 : vector.length;
            long contentBytes = info.content == null ? 0
                    : info.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long vectorBytes = dimension > 0 ? (long) dimension * 2 : 0;
            total += contentBytes + vectorBytes + 200 + vectorBytes * 2;
        }
        return total;
    }

    // ── 删除（直委托 VectorStoreService 的同语义方法） ─────────────────────

    @Override
    public void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception {
        writeSvc.deleteByChunkId(chunkIdList);
    }

    @Override
    public void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception {
        writeSvc.deleteBySourceId(sourceIdList);
    }

    @Override
    public void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension,
                                        String knowledgeType) throws Exception {
        writeSvc.deleteByKnowledgeId(knowledgeIdList);
    }

    // ── 复制 / 批量更新 ─────────────────────────────────────────────────────

    /** 批 500 分页深取 + 三态 SourceID 改写。 */
    @Override
    public void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                            Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                            int dimension, String knowledgeType) throws Exception {
        log.info("[Postgres] Copying indices, source knowledge base: {}, target knowledge base: {}, "
                + "mapping count: {}", sourceKnowledgeBaseId, targetKnowledgeBaseId,
                sourceToTargetChunkIdMap == null ? 0 : sourceToTargetChunkIdMap.size());
        if (sourceToTargetChunkIdMap == null || sourceToTargetChunkIdMap.isEmpty()) {
            log.warn("[Postgres] Mapping is empty, no need to copy");
            return;
        }
        int offset = 0;
        long totalCopied = 0;
        while (true) {
            List<CopyRow> source = jdbc.query(copySelectSql(), (rs, i) -> {
                CopyRow r = new CopyRow();
                r.content = rs.getString("content");
                r.sourceId = rs.getString("source_id");
                r.sourceType = rs.getInt("source_type");
                r.chunkId = rs.getString("chunk_id");
                r.knowledgeId = rs.getString("knowledge_id");
                r.dimension = rs.getInt("dimension");
                r.embedding = rs.getString("embedding");
                return r;
            }, sourceKnowledgeBaseId, COPY_BATCH_SIZE, offset);
            if (source.isEmpty()) {
                if (offset == 0) {
                    log.warn("[Postgres] No source index data found");
                }
                break;
            }
            List<CopyRow> targets = new ArrayList<>(source.size());
            for (CopyRow row : source) {
                String targetChunkId = sourceToTargetChunkIdMap.get(row.chunkId);
                if (targetChunkId == null) {
                    log.warn("[Postgres] Source chunk {} not found in target chunk mapping, skipping",
                            row.chunkId);
                    continue;
                }
                String targetKnowledgeId = sourceToTargetKbIdMap.get(row.knowledgeId);
                if (targetKnowledgeId == null) {
                    log.warn("[Postgres] Source knowledge {} not found in target knowledge mapping, skipping",
                            row.knowledgeId);
                    continue;
                }
                // SourceID 三态：本块 → 目标 chunkID；生成问题（<chunk>-<qid>）→
                // 目标 chunkID + 原 qid；其他 → 新 UUID
                String targetSourceId;
                if (row.sourceId.equals(row.chunkId)) {
                    targetSourceId = targetChunkId;
                } else if (row.sourceId.startsWith(row.chunkId + "-")) {
                    targetSourceId = targetChunkId + "-"
                            + row.sourceId.substring(row.chunkId.length() + 1);
                } else {
                    targetSourceId = UUID.randomUUID().toString();
                }
                row.targetSourceId = targetSourceId;
                row.targetChunkId = targetChunkId;
                row.targetKnowledgeId = targetKnowledgeId;
                targets.add(row);
            }
            if (!targets.isEmpty()) {
                totalCopied += insertCopyBatch(targets, targetKnowledgeBaseId);
            }
            offset += COPY_BATCH_SIZE;
            if (source.size() < COPY_BATCH_SIZE) {
                break;
            }
        }
        log.info("[Postgres] Index copying completed, total copied: {}", totalCopied);
    }

    /** INSERT 列集不含 is_enabled：落库走 DB 默认 true（见类注释）。 */
    private long insertCopyBatch(List<CopyRow> targets, String targetKnowledgeBaseId) {
        Timestamp now = Timestamp.from(Instant.now());
        String sql = postgres
                ? "INSERT INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, tag_id, content, dimension, embedding) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?::halfvec) "
                  + "ON CONFLICT (source_id, source_type) DO NOTHING"
                : "MERGE INTO embeddings (created_at, updated_at, source_id, source_type, chunk_id, "
                  + "knowledge_id, knowledge_base_id, tag_id, content, dimension, embedding) "
                  + "KEY(source_id, source_type) VALUES (?, ?, ?, ?, ?, ?, ?, '', ?, ?, ?)";
        int[][] counts = jdbc.batchUpdate(sql, targets, targets.size(), (ps, row) -> {
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, row.targetSourceId);
            ps.setInt(4, row.sourceType);
            ps.setString(5, row.targetChunkId);
            ps.setString(6, row.targetKnowledgeId);
            ps.setString(7, targetKnowledgeBaseId);
            ps.setString(8, row.content);
            ps.setInt(9, row.dimension);
            ps.setString(10, row.embedding);
        });
        long inserted = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                // ON CONFLICT DO NOTHING 时驱动可能回 Statement.SUCCESS_NO_INFO（-2）
                inserted += c > 0 ? c : 0;
            }
        }
        return inserted;
    }

    private String copySelectSql() {
        return "SELECT content, source_id, source_type, chunk_id, knowledge_id, dimension, "
                + (postgres ? "embedding::text AS embedding" : "embedding AS embedding")
                + " FROM embeddings WHERE knowledge_base_id = ? LIMIT ? OFFSET ?";
    }

    /** 按 ChunkID 批量更新 chunk 启用状态。 */
    @Override
    public void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception {
        writeSvc.batchUpdateChunkEnabledStatus(chunkStatusMap);
    }

    /** 按 ChunkID 批量更新 chunk 标签。 */
    @Override
    public void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception {
        writeSvc.batchUpdateChunkTagId(chunkTagMap);
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    /**
     * 按 retrieverType 分派到关键词/向量检索；
     * 未知类型报 {@code invalid retriever type}。
     */
    @Override
    public List<RetrieveResult> retrieve(RetrieveParams params) throws Exception {
        String type = params.retrieverType;
        if (EngineTypes.RETRIEVER_KEYWORDS.equals(type)) {
            PgVectorRetrieveRepository.RetrieveResult rr = readRepo.keywordsRetrieve(new SearchParams(),
                    params.knowledgeBaseIds, params.knowledgeIds, params.tagIds, params.topK,
                    params.query);
            return List.of(new RetrieveResult(mapHits(rr.results()),
                    EngineTypes.ENGINE_POSTGRES, type));
        }
        if (EngineTypes.RETRIEVER_VECTOR.equals(type)) {
            PgVectorRetrieveRepository.RetrieveResult rr = readRepo.vectorRetrieve(new SearchParams(),
                    params.embedding, params.knowledgeBaseIds, params.knowledgeIds, params.tagIds,
                    params.topK, params.threshold);
            return List.of(new RetrieveResult(mapHits(rr.results()),
                    EngineTypes.ENGINE_POSTGRES, type));
        }
        throw new IllegalStateException("invalid retriever type: " + type);
    }

    private static List<IndexWithScore> mapHits(List<PgVectorRetrieveRepository.IndexHit> hits) {
        List<IndexWithScore> out = new ArrayList<>(hits == null ? 0 : hits.size());
        if (hits == null) {
            return out;
        }
        for (PgVectorRetrieveRepository.IndexHit h : hits) {
            IndexWithScore s = new IndexWithScore();
            s.id = h.id;
            s.sourceId = h.sourceId;
            s.sourceType = h.sourceType;
            s.chunkId = h.chunkId;
            s.knowledgeId = h.knowledgeId;
            s.knowledgeBaseId = h.knowledgeBaseId;
            s.tagId = h.tagId;
            s.content = h.content;
            s.score = h.score;
            s.matchType = h.matchType;
            out.add(s);
        }
        return out;
    }

    // ── 迁移 ────────────────────────────────────────────────────────────────

    /**
     * 迁移：改写 knowledge_base_id 并清空 tag_id。
     * ES 侧忽略的 chunkIDs/dimension/knowledgeType 本实现同忽略。
     */
    @Override
    public void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                     List<String> chunkIds, int dimension, String knowledgeType)
            throws Exception {
        jdbc.update("UPDATE embeddings SET knowledge_base_id = ?, tag_id = '' "
                + "WHERE knowledge_base_id = ? AND knowledge_id = ?", targetKb, sourceKb, knowledgeId);
    }

    // ── additionalParams 取值（embedding 按 SourceID / chunk_enabled 按 ChunkID） ──

    @SuppressWarnings("unchecked")
    private static Map<String, float[]> embeddingMap(Map<String, Object> params) {
        if (params == null) {
            return Map.of();
        }
        Object raw = params.get("embedding");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, float[]>) m;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> chunkEnabledMap(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object raw = params.get("chunk_enabled");
        if (raw instanceof Map<?, ?> m) {
            return (Map<String, Boolean>) m;
        }
        return null;
    }

    /** CopyIndices 的一行（源行 + 改写后的目标三键）。 */
    private static final class CopyRow {
        String content;
        String sourceId;
        int sourceType;
        String chunkId;
        String knowledgeId;
        int dimension;
        String embedding;
        String targetSourceId;
        String targetChunkId;
        String targetKnowledgeId;
    }
}
