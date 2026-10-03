package com.ragagent.retrieval.engine.qdrant;

/**
 * Qdrant point 的 payload 模型。
 *
 * <p>字段与 payload 键一一对应（snake_case）；{@code embedding} 只在 CopyIndices
 * 回搬时需要（检索结果不带向量）。</p>
 */
public final class QdrantVectorEmbedding {

    public String content = "";
    public String sourceId = "";
    public int sourceType;
    public String chunkId = "";
    public String knowledgeId = "";
    public String knowledgeBaseId = "";
    public String tagId = "";
    public float[] embedding;
    public boolean isEnabled;
}
