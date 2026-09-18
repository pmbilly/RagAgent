package com.ragagent.memory.domain;

/**
 * 一条语义命中，以及它有多接近
 * （对照 Go {@code interfaces.MemoryVectorHit}，internal/types/interfaces/memory.go L39-42）。
 *
 * @param item  命中的记忆条目
 * @param score 余弦相似度
 */
public record MemoryVectorHit(MemoryItem item, double score) {
}
