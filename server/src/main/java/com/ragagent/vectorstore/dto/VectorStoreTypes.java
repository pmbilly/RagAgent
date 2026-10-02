package com.ragagent.vectorstore.dto;

import java.util.ArrayList;
import com.ragagent.common.deployment.AppEnvLookup;
import java.util.List;


/**
 * 对照 Go {@code types.GetVectorStoreTypes()}（types/vectorstore.go L665-784）：
 * /vector-stores/types 的静态元数据。postgres/sqlite 不在列（仅 env store 可达——
 * Go 原注释）。tencent_vectordb 的 replica_number 缺省读 TENCENT_VECTORDB_REPLICA_NUMBER
 * （非法/负值回落 1）。条目与字段顺序、每个 default 的有无（interface 持 false 也会
 * 输出 {@code "default":false}）= Go 数组/struct 字面量形态（契约）。
 */
public final class VectorStoreTypes {

    private VectorStoreTypes() {
    }

    public static List<TypeInfo> all() {
        int tencentReplicas = resolveTencentVectorDBReplicaNumber(
                AppEnvLookup.get("TENCENT_VECTORDB_REPLICA_NUMBER"));
        List<TypeInfo> out = new ArrayList<>();

        // elasticsearch
        out.add(type("elasticsearch", "Elasticsearch",
                listOf(
                        field("addr", "string", true, "URL", "http://localhost:9200"),
                        field("username", "string", false, "Username", "elastic"),
                        secret("password", "Password")),
                listOf(
                        field("indexName", "string", false, "Index Name", "weknora"),
                        numField("number_of_shards", false, "Shards", 4, null, null),
                        numField("number_of_replicas", false, "Replicas", 1, null, null))));

        // qdrant
        out.add(type("qdrant", "Qdrant",
                listOf(
                        field("host", "string", true, "Host", "localhost"),
                        numField("port", false, "Port", 6334, null, null),
                        secret("apiKey", "API Key"),
                        boolField("useTls", false, "Use TLS", false)),
                listOf(
                        field("collectionPrefix", "string", false, "Collection Prefix", "weknora_embeddings"),
                        numField("shard_number", false, "Shard Number", 1, null, null),
                        numField("replication_factor", false, "Replication Factor", 1, null, null))));

        // milvus
        out.add(type("milvus", "Milvus",
                listOf(
                        field("addr", "string", true, "Address", "localhost:19530"),
                        field("database", "string", false, "Database Name", null),
                        field("username", "string", false, "Username", "root"),
                        secret("password", "Password")),
                listOf(
                        field("collectionName", "string", false, "Collection Name", "weknora_embeddings"),
                        numField("shards_num", false, "Shards (write parallelism)", 1, null, null),
                        numField("replica_number", false, "In-memory Replicas (read HA)", 1, null, null))));

        // tencent_vectordb
        out.add(type("tencent_vectordb", "Tencent VectorDB",
                listOf(
                        field("addr", "string", true, "Address", "http://localhost:8080"),
                        field("username", "string", true, "Username", null),
                        secretRequired("apiKey", "API Key"),
                        field("database", "string", false, "Database", "weknora")),
                listOf(
                        field("collectionName", "string", false, "Collection Name", "weknora_embeddings"),
                        numField("shards_num", false, "Shards", 1, null, null),
                        numField("replica_number", false, "Replicas", tencentReplicas, null, null))));

        // weaviate
        out.add(type("weaviate", "Weaviate",
                listOf(
                        field("host", "string", true, "Host", "weaviate:8080"),
                        field("grpcAddress", "string", false, "gRPC Address", "weaviate:50051"),
                        field("scheme", "string", false, "Scheme", "http"),
                        secret("apiKey", "API Key")),
                listOf(
                        field("collectionPrefix", "string", false, "Collection Prefix", "Weknora_embeddings"),
                        numField("desired_shard_count", false, "Shard Count", 1, null, null),
                        numField("replication_factor", false, "Replication Factor", 1, null, null))));

        // doris
        out.add(type("doris", "Apache Doris",
                listOf(
                        field("addr", "string", true, "FE MySQL Address (host:port)", "doris-fe:9030"),
                        numField("http_port", false, "FE HTTP Port (Stream Load)", 8030, null, null),
                        field("database", "string", true, "Database", "weknora"),
                        field("username", "string", false, "Username", "root"),
                        secret("password", "Password")),
                listOf(
                        field("collectionPrefix", "string", false, "Table Prefix", "weknora_embeddings"),
                        numField("buckets_num", false, "Buckets per table", 10, null, null),
                        numField("replication_num", false, "Replication Num", 1, null, null))));

        // opensearch（HNSW 字段 immutable + min/max + knn_engine enum）
        out.add(type("opensearch", "OpenSearch",
                listOf(
                        field("addr", "string", true, "URL", "https://localhost:9200"),
                        field("username", "string", false, "Username", "admin"),
                        secret("password", "Password"),
                        boolField("insecure_skip_verify", false,
                                "Skip TLS certificate verification. For self-signed dev clusters only — "
                                        + "never enable in production.", false)),
                listOf(
                        field("indexName", "string", false, "Index Name", "weknora"),
                        numField("number_of_shards", false, "Shards", 4, 1.0, 64.0),
                        numField("number_of_replicas", false, "Replicas", 1, 0.0, 10.0),
                        immutableNum("hnsw_m", "HNSW graph degree (M). Immutable after index creation.",
                                16, 2.0, 100.0),
                        immutableNum("hnsw_ef_construction",
                                "HNSW build candidate list. Higher (e.g. 200-512) improves recall at the cost of build time. Immutable after creation.",
                                100, 2.0, 4096.0),
                        immutableNum("hnsw_ef_search",
                                "HNSW search candidate list. Effective on the faiss engine; the lucene engine reads it at query time. Immutable (no settings-update path).",
                                100, 1.0, 10000.0),
                        knnEngine())));
        return out;
    }

    /** knn_engine：Default "lucene"、immutable、enum=[lucene,faiss]、无 min/max */
    private static FieldInfo knnEngine() {
        FieldInfo f = base("knn_engine", "string", false, null, "k-NN backend.", "lucene");
        f.immutable = true;
        f.enumValues = List.of("lucene", "faiss");
        return f;
    }

    private static int resolveTencentVectorDBReplicaNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 1;
        }
        try {
            int replicas = Integer.parseInt(raw.trim());
            return replicas < 0 ? 1 : replicas;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ── builders ──

    private static List<FieldInfo> listOf(FieldInfo... items) {
        List<FieldInfo> out = new ArrayList<>();
        for (FieldInfo f : items) {
            out.add(f);
        }
        return out;
    }

    private static FieldInfo field(String name, String type, boolean required, String description, String dflt) {
        return base(name, type, required, null, description, dflt);
    }

    private static FieldInfo secret(String name, String description) {
        return base(name, "string", false, true, description, null);
    }

    private static FieldInfo secretRequired(String name, String description) {
        return base(name, "string", true, true, description, null);
    }

    private static FieldInfo numField(String name, boolean required, String description, Integer dflt,
            Double min, Double max) {
        FieldInfo f = base(name, "number", required, null, description, dflt);
        f.min = min;
        f.max = max;
        return f;
    }

    private static FieldInfo boolField(String name, boolean required, String description, Boolean dflt) {
        return base(name, "boolean", required, null, description, dflt);
    }

    private static FieldInfo immutableNum(String name, String description, Integer dflt, Double min, Double max) {
        FieldInfo f = base(name, "number", false, null, description, dflt);
        f.min = min;
        f.max = max;
        f.immutable = true;
        return f;
    }

    private static FieldInfo base(String name, String type, boolean required, Boolean sensitive,
            String description, Object defaultValue) {
        FieldInfo f = new FieldInfo();
        f.name = name;
        f.type = type;
        f.required = required;
        f.sensitive = sensitive != null && sensitive;
        f.description = description == null || description.isEmpty() ? null : description;
        // Go Default 是 interface{}：nil（省略）与持零值（输出，如 "default":false）并存
        f.defaultValue = defaultValue;
        return f;
    }

    private static TypeInfo type(String type, String displayName, List<FieldInfo> conn, List<FieldInfo> index) {
        TypeInfo t = new TypeInfo();
        t.type = type;
        t.displayName = displayName;
        t.connectionFields = conn;
        t.indexFields = index;
        return t;
    }

        public static class TypeInfo {
        public String type;
        public String displayName;
        public List<FieldInfo> connectionFields;
        public List<FieldInfo> indexFields;
    }

        public static class FieldInfo {
        public String name;
        public String type;
        public boolean required;
        public boolean sensitive;
        public Object defaultValue;
        public String description;
        public boolean immutable;

        public Double min;

        public Double max;
        public List<String> enumValues;
    }
}
