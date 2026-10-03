package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

/**
 * 检索引擎仓库端口——各向量店实现之；含"迁移知识索引"的子口。
 *
 * <p>各向量店实现之（现状：ES v8 / ES v7）；postgres 由既有 JDBC 件
 * （{@code PgVectorRetrieveRepository} 读 + {@code VectorStoreService} 写）承担，不经此口。</p>
 *
 * <p>失败以受检异常（{@code throws Exception}）表达。</p>
 */
public interface RetrieveEngineRepository {

    /** 引擎类型标识。 */
    String engineType();

    /** 支持的检索类型。 */
    List<String> support();

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 单条写入。 */
    void save(EngineTypes.IndexInfo indexInfo, Map<String, Object> params) throws Exception;

    /** 批量写入。 */
    void batchSave(List<EngineTypes.IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception;

    /** 存储体量估算。 */
    long estimateStorageSize(List<EngineTypes.IndexInfo> indexInfoList, Map<String, Object> params);

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 按 chunk ID 列表删除。 */
    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 按 source ID 列表删除。 */
    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 按知识 ID 列表删除。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension, String knowledgeType)
            throws Exception;

    // ── 复制与批量更新 ──────────────────────────────────────────────────────

    /** 跨知识库复制索引。 */
    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                     Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                     int dimension, String knowledgeType) throws Exception;

    /** 按 ChunkID 批量更新启用状态。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception;

    /** 按 ChunkID 批量更新标签。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception;

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 按参数检索。 */
    List<EngineTypes.RetrieveResult> retrieve(EngineTypes.RetrieveParams params) throws Exception;

    /**
     * 具备"迁移知识索引"能力的店再挂这个子口
     * （调用方用 {@code instanceof} 探测）。
     */
    interface KnowledgeIndexMover {

        /** 迁移某知识的全部索引（ES 实现忽略 chunkIDs/dimension/knowledgeType）。 */
        void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                  List<String> chunkIds, int dimension, String knowledgeType)
                throws Exception;
    }
}
