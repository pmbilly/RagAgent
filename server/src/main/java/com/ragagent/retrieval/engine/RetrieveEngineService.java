package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * 检索引擎服务端口——"引擎服务"层。
 *
 * <p>{@code index}/{@code batchIndex} 自己算向量（收 {@link Embedder}），其余方法纯转发；
 * {@link KeywordsVectorHybridRetrieveEngineService} 是它的实现。</p>
 *
 * <p>能力探测用 {@code instanceof} {@link KnowledgeIndexMover}。</p>
 */
public interface RetrieveEngineService {

    /** 引擎类型标识。 */
    String engineType();

    /** 支持的检索类型。 */
    List<String> support();

    /** 单条写入：嵌入 + 落库；{@code retrieverTypes} 决定要不要算向量。 */
    void index(Embedder embedder, IndexInfo indexInfo, List<String> retrieverTypes) throws Exception;

    /** 批量写入：嵌入 + 落库。 */
    void batchIndex(Embedder embedder, List<IndexInfo> indexInfoList,
                    List<String> retrieverTypes) throws Exception;

    /** 存储体量估算。 */
    long estimateStorageSize(Embedder embedder, List<IndexInfo> indexInfoList,
                             List<String> retrieverTypes);

    /** 跨知识库复制索引。 */
    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                     Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                     int dimension, String knowledgeType) throws Exception;

    /** 按 chunk ID 列表删除。 */
    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 按 source ID 列表删除。 */
    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 按知识 ID 列表删除。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 按 ChunkID 批量更新启用状态。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception;

    /** 按 ChunkID 批量更新标签。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception;

    /** 按参数检索。 */
    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception;

    /**
     * 具备"迁移知识索引"能力的服务再挂这个子口（用 {@code instanceof} 探测）。
     * {@code CompositeRetrieveEngine}
     * 据此判定"整条链能不能迁移"。
     */
    interface KnowledgeIndexMover {

        /** 迁移某知识的全部索引。 */
        void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                  List<String> chunkIds, int dimension, String knowledgeType)
                throws Exception;
    }

    /**
     * 迁移前的整体预检口：不是
     * {@code KnowledgeIndexMover} 的必填项，挂了就先跑校验。
     */
    interface KnowledgeIndexMoveValidator {

        /** 迁移前预检。 */
        void validateKnowledgeIndexMove();
    }
}
