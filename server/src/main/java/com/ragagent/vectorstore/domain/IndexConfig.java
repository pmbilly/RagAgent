package com.ragagent.vectorstore.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.IndexConfig}（internal/types/vectorstore.go L244-268）。
 * 全字段 omitempty；未知键容忍（jsonb 演进 + 其它引擎的键可能出现在历史行里——
 * Go 的注释明说「All omitempty so other engines' serialized IndexConfig is unchanged」）。
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IndexConfig {

    @JsonProperty("index_name")
    public String indexName = "";
    @JsonProperty("number_of_shards")
    public int numberOfShards;
    @JsonProperty("number_of_replicas")
    public int numberOfReplicas;
    @JsonProperty("collection_prefix")
    public String collectionPrefix = "";
    @JsonProperty("collection_name")
    public String collectionName = "";
    @JsonProperty("shard_number")
    public int shardNumber;
    @JsonProperty("replication_factor")
    public int replicationFactor;
    @JsonProperty("shards_num")
    public int shardsNum;
    @JsonProperty("replica_number")
    public int replicaNumber;
    @JsonProperty("desired_shard_count")
    public int desiredShardCount;
    @JsonProperty("buckets_num")
    public int bucketsNum;
    @JsonProperty("replication_num")
    public int replicationNum;
    @JsonProperty("hnsw_m")
    public int hnswM;
    @JsonProperty("hnsw_ef_construction")
    public int hnswEfConstruction;
    @JsonProperty("hnsw_ef_search")
    public int hnswEfSearch;
    @JsonProperty("knn_engine")
    public String knnEngine = "";

    /** 对照 GetIndexNameOrDefault（服务层去重用；env 回退不在本层）。@JsonIgnore 同上 */
    @com.fasterxml.jackson.annotation.JsonIgnore
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
