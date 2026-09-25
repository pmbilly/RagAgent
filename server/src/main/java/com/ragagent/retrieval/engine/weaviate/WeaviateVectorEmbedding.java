package com.ragagent.retrieval.engine.weaviate;

/**
 * Weaviate 对象的 properties 模型——对照 Go {@code weaviate.WeaviateVectorEmbedding}
 * （structs.go L21-31）。字段与 payload 键一一对应（snake_case，照 {@code createPayload}）。
 */
public final class WeaviateVectorEmbedding {

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
