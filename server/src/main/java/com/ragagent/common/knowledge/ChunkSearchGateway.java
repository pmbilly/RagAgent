package com.ragagent.common.knowledge;

import java.util.List;

/**
 * chunk 行的**只读端口**（检索编排批量取 chunk 事实的最小接口）。
 *
 * <p>由知识域实现（{@code ChunkRepository}），消费方（{@code retrieval} 的混合检索）
 * 注入接口而非 {@code ChunkRepository} + {@code Chunk} 实体。</p>
 */
public interface ChunkSearchGateway {

    /**
     * 批量取 chunk 事实（租户内、软删不可见，与包内 {@code listChunksById} 同语义）；
     * 缺失 id 跳过，查不到返回空列表。
     */
    List<ChunkFacts> findChunks(long tenantId, List<String> chunkIds);
}
