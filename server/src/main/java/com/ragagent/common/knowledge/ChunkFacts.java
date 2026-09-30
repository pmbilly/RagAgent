package com.ragagent.common.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * chunk 行的「检索事实」——检索编排（命中过滤、父块/相邻块/关系块扩召回、结果装配）
 * 真正读取的字段。
 *
 * <p>为什么不直接传 {@code knowledge.domain.Chunk} 实体：检索引擎域不该依赖知识域的
 * 表实体。这里按读取面裁剪：内容与坐标（{@code content/startAt/endAt/chunkIndex/
 * contentRevision}）、块类型与索引状态（命中过滤）、邻接与关系（扩召回）、
 * 两条 json 列（FAQ 负例问题、块元数据）。</p>
 *
 * <p>只带调用方真正读取的字段；需要更多字段时**先改这里**，别把实体漏出去。</p>
 */
public record ChunkFacts(
        String id,
        String knowledgeId,
        String content,
        String chunkType,
        String indexStatus,
        boolean enabled,
        int chunkIndex,
        int startAt,
        int endAt,
        int contentRevision,
        String parentChunkId,
        String preChunkId,
        String nextChunkId,
        JsonNode relationChunks,
        JsonNode metadata) {
}
