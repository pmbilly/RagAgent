package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

/**
 * 检索引擎仓库端口——对照 Go {@code interfaces.RetrieveEngineRepository}
 * （types/interfaces/retriever.go L23+）与 {@code interfaces.KnowledgeIndexMover}。
 *
 * <p>各向量店实现之（本仓现状：ES v8 / ES v7）；postgres 由既有 JDBC 件
 * （{@code PgVectorRetrieveRepository} 读 + {@code VectorStoreService} 写）承担，不经此口。</p>
 *
 * <p>与 Go 的差异（备案）：Go 用 {@code ctx} + {@code error}；Java 无 ctx，
 * 失败以受检异常（{@code throws Exception}）表达，与 Go 的 error 语义对齐。</p>
 */
public interface RetrieveEngineRepository {

    /** 对照 {@code EngineType()}。 */
    String engineType();

    /** 对照 {@code Support()}。 */
    List<String> support();

    // ── 写入 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Save}。 */
    void save(EngineTypes.IndexInfo indexInfo, Map<String, Object> params) throws Exception;

    /** 对照 {@code BatchSave}。 */
    void batchSave(List<EngineTypes.IndexInfo> indexInfoList, Map<String, Object> params)
            throws Exception;

    /** 对照 {@code EstimateStorageSize}。 */
    long estimateStorageSize(List<EngineTypes.IndexInfo> indexInfoList, Map<String, Object> params);

    // ── 删除 ────────────────────────────────────────────────────────────────

    /** 对照 {@code DeleteByChunkIDList}。 */
    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 对照 {@code DeleteBySourceIDList}。 */
    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 对照 {@code DeleteByKnowledgeIDList}。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension, String knowledgeType)
            throws Exception;

    // ── 复制与批量更新 ──────────────────────────────────────────────────────

    /** 对照 {@code CopyIndices}。 */
    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                     Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                     int dimension, String knowledgeType) throws Exception;

    /** 对照 {@code BatchUpdateChunkEnabledStatus}。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception;

    /** 对照 {@code BatchUpdateChunkTagID}。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception;

    // ── 检索 ────────────────────────────────────────────────────────────────

    /** 对照 {@code Retrieve}。 */
    List<EngineTypes.RetrieveResult> retrieve(EngineTypes.RetrieveParams params) throws Exception;

    /**
     * 对照 {@code interfaces.KnowledgeIndexMover}：具备"迁移知识索引"能力的店再挂这个子口
     * （Go 用类型断言探测；Java 用 {@code instanceof}）。
     */
    interface KnowledgeIndexMover {

        /** 对照 {@code MoveKnowledgeIndices}（ES 两侧忽略 chunkIDs/dimension/knowledgeType）。 */
        void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                  List<String> chunkIds, int dimension, String knowledgeType)
                throws Exception;
    }
}
