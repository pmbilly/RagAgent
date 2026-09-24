package com.ragagent.retrieval.engine;

import java.util.List;
import java.util.Map;

import com.ragagent.embedding.Embedder;
import com.ragagent.retrieval.engine.EngineTypes.IndexInfo;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveParams;
import com.ragagent.retrieval.engine.EngineTypes.RetrieveResult;

/**
 * 检索引擎服务端口——对照 Go {@code interfaces.RetrieveEngineService}
 * （types/interfaces/retriever.go L101-156）。
 *
 * <p>Go 里它是"引擎服务"层：{@code Index}/{@code BatchIndex} 自己算向量（收
 * {@code embedding.Embedder}），其余方法是纯转发；{@link KeywordsVectorHybridRetrieveEngineService}
 * 是它的实现。</p>
 *
 * <p>Java 差异（备案）：Go 的 {@code ctx} 去掉（约定 §1）；Go 用类型断言探测
 * {@code interfaces.KnowledgeIndexMover}，Java 用 {@code instanceof}
 * {@link KnowledgeIndexMover}。</p>
 */
public interface RetrieveEngineService {

    /** 对照 {@code EngineType()}。 */
    String engineType();

    /** 对照 {@code Support()}。 */
    List<String> support();

    /** 对照 {@code Index}：嵌入 + 落库；{@code retrieverTypes} 决定要不要算向量。 */
    void index(Embedder embedder, IndexInfo indexInfo, List<String> retrieverTypes) throws Exception;

    /** 对照 {@code BatchIndex}。 */
    void batchIndex(Embedder embedder, List<IndexInfo> indexInfoList,
                    List<String> retrieverTypes) throws Exception;

    /** 对照 {@code EstimateStorageSize}。 */
    long estimateStorageSize(Embedder embedder, List<IndexInfo> indexInfoList,
                             List<String> retrieverTypes);

    /** 对照 {@code CopyIndices}。 */
    void copyIndices(String sourceKnowledgeBaseId, Map<String, String> sourceToTargetKbIdMap,
                     Map<String, String> sourceToTargetChunkIdMap, String targetKnowledgeBaseId,
                     int dimension, String knowledgeType) throws Exception;

    /** 对照 {@code DeleteByChunkIDList}。 */
    void deleteByChunkIdList(List<String> chunkIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 对照 {@code DeleteBySourceIDList}。 */
    void deleteBySourceIdList(List<String> sourceIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 对照 {@code DeleteByKnowledgeIDList}。 */
    void deleteByKnowledgeIdList(List<String> knowledgeIdList, int dimension, String knowledgeType)
            throws Exception;

    /** 对照 {@code BatchUpdateChunkEnabledStatus}。 */
    void batchUpdateChunkEnabledStatus(Map<String, Boolean> chunkStatusMap) throws Exception;

    /** 对照 {@code BatchUpdateChunkTagID}。 */
    void batchUpdateChunkTagID(Map<String, String> chunkTagMap) throws Exception;

    /** 对照内嵌的 {@code RetrieveEngine}（{@code Retrieve} / {@code Support}）。 */
    List<RetrieveResult> retrieve(RetrieveParams params) throws Exception;

    /**
     * 对照 Go {@code interfaces.KnowledgeIndexMover}：具备"迁移知识索引"能力的服务再挂这个子口
     * （Go 用类型断言探测；Java 用 {@code instanceof}）。{@code CompositeRetrieveEngine}
     * 据此判定"整条链能不能迁移"。
     */
    interface KnowledgeIndexMover {

        /** 对照 {@code MoveKnowledgeIndices}。 */
        void moveKnowledgeIndices(String sourceKb, String targetKb, String knowledgeId,
                                  List<String> chunkIds, int dimension, String knowledgeType)
                throws Exception;
    }

    /**
     * 对照 Go 复合引擎里的匿名能力探测
     * {@code interface{ ValidateKnowledgeIndexMove(context.Context) error }}：不是
     * {@code KnowledgeIndexMover} 的必填项，挂了就先跑校验（迁移前的整体预检）。
     */
    interface KnowledgeIndexMoveValidator {

        /** 对照 {@code ValidateKnowledgeIndexMove}。 */
        void validateKnowledgeIndexMove();
    }
}
