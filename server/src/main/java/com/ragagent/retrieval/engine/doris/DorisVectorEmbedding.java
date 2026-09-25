package com.ragagent.retrieval.engine.doris;

/**
 * 落到 Doris 表里的一行的领域模型——对照 Go {@code doris.DorisVectorEmbedding}
 * （structs.go L61-72）。
 *
 * <p>字段顺序与 {@link DorisSql#COLUMNS} 的 INSERT 列序保持一致，
 * 调整时需要同时更新 {@code DorisRetrieveRepository.insertRows} 与列常量。</p>
 */
public final class DorisVectorEmbedding {

    public String id = "";
    public String content = "";
    public String sourceId = "";
    public int sourceType;
    public String chunkId = "";
    public String knowledgeId = "";
    public String knowledgeBaseId = "";
    public String tagId = "";
    public boolean isEnabled;
    public float[] embedding;
}
