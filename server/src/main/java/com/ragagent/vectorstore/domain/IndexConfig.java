package com.ragagent.vectorstore.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 对照 Go {@code types.IndexConfig}（internal/types/vectorstore.go L244-268）。
 * 全字段 omitempty；未知键容忍（jsonb 演进 + 其它引擎的键可能出现在历史行里——
 * Go 的注释明说「All omitempty so other engines' serialized IndexConfig is unchanged」）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexConfig {

    public String indexName = "";
    public int numberOfShards;
    public int numberOfReplicas;
    public String collectionPrefix = "";
    public String collectionName = "";
    public int shardNumber;
    public int replicationFactor;
    public int shardsNum;
    public int replicaNumber;
    public int desiredShardCount;
    public int bucketsNum;
    public int replicationNum;
    public int hnswM;
    public int hnswEfConstruction;
    public int hnswEfSearch;
    public String knnEngine = "";

    /** 对照 GetIndexNameOrDefault（服务层去重用；env 回退不在本层）。@JsonIgnore 同上 */
    @JsonIgnore
    public String getIndexNameOrDefault(String engineType) {
        return switch (engineType == null ? "" : engineType) {
            case "elasticsearch" -> notEmpty(indexName) ? indexName : "xwrag_default";
            case "qdrant" -> notEmpty(collectionPrefix) ? collectionPrefix : "weknora_embeddings";
            case "milvus" -> notEmpty(collectionName) ? collectionName : "weknora_embeddings";
            case "tencent_vectordb" -> notEmpty(collectionName) ? collectionName : "weknora_embeddings";
            case "weaviate" -> notEmpty(collectionPrefix) ? collectionPrefix : "Weknora_embeddings";
            case "doris" -> {
                if (notEmpty(collectionPrefix)) {
                    yield collectionPrefix;
                }
                yield notEmpty(collectionName) ? collectionName : "weknora_embeddings";
            }
            default -> indexName;
        };
    }

    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
