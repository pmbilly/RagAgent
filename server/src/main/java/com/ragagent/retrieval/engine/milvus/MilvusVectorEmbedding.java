package com.ragagent.retrieval.engine.milvus;

/**
 * Milvus 行模型——对照 Go {@code milvus.MilvusVectorEmbedding}（structs.go L22-33）。
 * 字段名即 collection 的列名（snake_case，照 {@code createUpsert} 的逐列写法）。
 */
public final class MilvusVectorEmbedding {

    /** 主键（VarChar，非自增；本仓一律新 UUID，照 Go）。 */
    public String id = "";
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
