package com.ragagent.vectorstore.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 对照 Go {@code types.BuildEnvVectorStores / FindEnvVectorStore / buildEnvStoreForDriver}
 * （types/vectorstore.go L789-968）：由 RETRIEVE_DRIVER 派生的虚拟库（__env_* 前缀 id）。
 * 纯函数（env 经 {@link EnvLookup} 注入）；本部署 RETRIEVE_DRIVER 未配置 → 空列表。
 */
public final class EnvVectorStores {

    public static final String ENV_STORE_ID_PREFIX = "__env_";

    private EnvVectorStores() {
    }

    public interface EnvLookup extends UnaryOperator<String> {
    }

    public static boolean isEnvStoreId(String id) {
        return id != null && id.startsWith(ENV_STORE_ID_PREFIX);
    }

    public static List<VectorStore> build(String retrieveDriver, EnvLookup env) {
        List<VectorStore> stores = new ArrayList<>();
        if (retrieveDriver == null || retrieveDriver.isEmpty()) {
            return stores;
        }
        for (String driver : retrieveDriver.split(",")) {
            String d = driver.trim();
            if (d.isEmpty()) {
                continue;
            }
            VectorStore s = forDriver(d, env);
            if (s != null) {
                stores.add(s);
            }
        }
        return stores;
    }

    public static VectorStore find(String retrieveDriver, EnvLookup env, String id) {
        for (VectorStore s : build(retrieveDriver, env)) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        return null;
    }

    private static String lookup(EnvLookup env, String key) {
        return env == null ? "" : env.apply(key);
    }

    private static VectorStore forDriver(String driver, EnvLookup env) {
        return switch (driver) {
            case "postgres" -> store("__env_postgres__", "PostgreSQL", "postgres",
                    conn(null, null, null, null, true, null, 0), null);
            case "sqlite" -> {
                VectorStore s = base("__env_sqlite__", "SQLite", "sqlite");
                s.setConnectionConfig(new ConnectionConfig());
                s.setIndexConfig(new IndexConfig());
                yield s;
            }
            case "elasticsearch_v8" -> esStore("__env_elasticsearch_v8__", "Elasticsearch v8", env);
            case "elasticsearch_v7" -> esStore("__env_elasticsearch_v7__", "Elasticsearch v7", env);
            case "opensearch" -> {
                ConnectionConfig c = new ConnectionConfig();
                c.addr = lookup(env, "OPENSEARCH_ADDR");
                c.username = lookup(env, "OPENSEARCH_USERNAME");
                c.password = lookup(env, "OPENSEARCH_PASSWORD");
                c.insecureSkipVerify = "true".equalsIgnoreCase(lookup(env, "OPENSEARCH_INSECURE_SKIP_VERIFY"));
                IndexConfig ic = new IndexConfig();
                ic.indexName = lookup(env, "OPENSEARCH_INDEX");
                yield store("__env_opensearch__", "OpenSearch", "opensearch", c, ic);
            }
            case "qdrant" -> {
                ConnectionConfig c = new ConnectionConfig();
                c.host = lookup(env, "QDRANT_HOST");
                c.apiKey = lookup(env, "QDRANT_API_KEY");
                yield store("__env_qdrant__", "Qdrant", "qdrant", c, null);
            }
            case "milvus" -> {
                ConnectionConfig c = new ConnectionConfig();
                c.addr = lookup(env, "MILVUS_ADDRESS");
                c.username = lookup(env, "MILVUS_USERNAME");
                c.password = lookup(env, "MILVUS_PASSWORD");
                yield store("__env_milvus__", "Milvus", "milvus", c, null);
            }
            case "tencent_vectordb" -> {
                ConnectionConfig c = new ConnectionConfig();
                c.addr = lookup(env, "TENCENT_VECTORDB_ADDR");
                c.username = lookup(env, "TENCENT_VECTORDB_USERNAME");
                c.apiKey = lookup(env, "TENCENT_VECTORDB_API_KEY");
                c.database = lookup(env, "TENCENT_VECTORDB_DATABASE");
                IndexConfig ic = new IndexConfig();
                ic.collectionName = lookup(env, "TENCENT_VECTORDB_COLLECTION");
                yield store("__env_tencent_vectordb__", "Tencent VectorDB", "tencent_vectordb", c, ic);
            }
            case "weaviate" -> {
                ConnectionConfig c = new ConnectionConfig();
                c.host = lookup(env, "WEAVIATE_HOST");
                c.grpcAddress = lookup(env, "WEAVIATE_GRPC_ADDRESS");
                c.scheme = lookup(env, "WEAVIATE_SCHEME");
                c.apiKey = lookup(env, "WEAVIATE_API_KEY");
                yield store("__env_weaviate__", "Weaviate", "weaviate", c, null);
            }
            case "doris" -> {
                int httpPort = 0;
                String raw = lookup(env, "DORIS_HTTP_PORT");
                if (raw != null && !raw.isEmpty()) {
                    try {
                        httpPort = Integer.parseInt(raw);
                    } catch (NumberFormatException ignored) {
                        // Go: err → 保持 0
                    }
                }
                ConnectionConfig c = new ConnectionConfig();
                c.addr = lookup(env, "DORIS_ADDR");
                c.httpPort = httpPort;
                c.database = lookup(env, "DORIS_DATABASE");
                c.username = lookup(env, "DORIS_USERNAME");
                c.password = lookup(env, "DORIS_PASSWORD");
                IndexConfig ic = new IndexConfig();
                ic.collectionPrefix = lookup(env, "DORIS_TABLE_PREFIX");
                yield store("__env_doris__", "Apache Doris", "doris", c, ic);
            }
            default -> null;
        };
    }

    private static VectorStore esStore(String id, String name, EnvLookup env) {
        ConnectionConfig c = new ConnectionConfig();
        c.addr = lookup(env, "ELASTICSEARCH_ADDR");
        c.username = lookup(env, "ELASTICSEARCH_USERNAME");
        c.password = lookup(env, "ELASTICSEARCH_PASSWORD");
        IndexConfig ic = new IndexConfig();
        ic.indexName = lookup(env, "ELASTICSEARCH_INDEX");
        return store(id, name, "elasticsearch", c, ic);
    }

    private static ConnectionConfig conn(String addr, String username, String password, String apiKey,
            boolean useDefaultConnection, String host, int port) {
        ConnectionConfig c = new ConnectionConfig();
        c.addr = addr == null ? "" : addr;
        c.username = username == null ? "" : username;
        c.password = password == null ? "" : password;
        c.apiKey = apiKey == null ? "" : apiKey;
        c.useDefaultConnection = useDefaultConnection;
        c.host = host == null ? "" : host;
        c.port = port;
        return c;
    }

    private static VectorStore base(String id, String name, String engineType) {
        VectorStore s = new VectorStore();
        s.setId(id);
        s.setTenantId(0L);
        s.setName(name);
        s.setEngineType(engineType);
        return s;
    }

    private static VectorStore store(String id, String name, String engineType,
            ConnectionConfig conn, IndexConfig index) {
        VectorStore s = base(id, name, engineType);
        s.setConnectionConfig(conn == null ? new ConnectionConfig() : conn);
        s.setIndexConfig(index == null ? new IndexConfig() : index);
        return s;
    }
}
