package com.ragagent.retrieval.engine.milvus;

/**
 * Milvus 行模型。
 * 字段名即 collection 的列名（snake_case，与 upsert 请求逐列对应）。
 */
public final class MilvusVectorEmbedding {

    /** 主键（VarChar，非自增；一律新 UUID）。 */
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
