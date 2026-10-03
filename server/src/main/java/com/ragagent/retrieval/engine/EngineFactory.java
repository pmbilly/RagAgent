package com.ragagent.retrieval.engine;


import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.doris.DorisRetrieveRepository;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV7RetrieveRepository;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;
import com.ragagent.retrieval.engine.milvus.MilvusRetrieveRepository;
import com.ragagent.retrieval.engine.qdrant.QdrantRetrieveRepository;
import com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository;
import com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRetrieveRepository;
import com.ragagent.retrieval.engine.weaviate.WeaviateRetrieveRepository;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 检索引擎工厂——从 {@link VectorStore} 配置按引擎类型构建检索引擎服务，
 * 并承担运行时地址的 SSRF 校验。
 *
 * <h2>各引擎落地形态</h2>
 * <ul>
 *   <li>**elasticsearch**：按 {@code connection_config.version} 前缀选 v7/v8（空 → v8），
 *       建 driver → 包成 {@link KeywordsVectorHybridRetrieveEngineService}</li>
 *   <li>**postgres**：不经本工厂——读/写由既有 JDBC 件承担（{@code PgVectorRetrieveRepository} /
 *       {@code VectorStoreService}），走到这里即抛"明确指引"式异常；
 *       **sqlite**：真落地，介质为独立 SQLite 文件（{@code SQLITE_PATH}，见驱动类注释）</li>
 *   <li>**opensearch**：k-NN 驱动 +
 *       audit sink 注入；探针在构造期显形</li>
 *   <li>**doris**：MySQL 协议主链路 + Stream Load
 *       HTTP；addr 必填、database 必填、http_port 缺省 8030</li>
 *   <li>**qdrant**：REST 自持（HTTP/JSON：host/port（缺省 6334）/api_key/
 *       use_tls）</li>
 *   <li>**weaviate**：REST 自持（GraphQL 检索/
 *       列举与批量删除本就是 REST；批量创建走客户端自身的 REST 回落路径；host 缺省
 *       {@code weaviate:8080}、scheme 缺省 http、api_key 直取）</li>
 *   <li>**milvus**：REST v2 自持（{@code /v2/vectordb/…}
 *       零新依赖：建集合含 BM25 函数/稀疏列/indexParams、upsert/query/search/delete 均已对真服务端
 *       实测；addr 缺省 {@code localhost:19530}）</li>
 *   <li>**tencent_vectordb**：HTTP API 自持
 *       （{@code Authorization: Bearer account=…&api_key=…}，同一服务端的 HTTP 面；
 *       BM25 稀疏向量客户端编码）</li>
 * </ul>
 *
 * <h2>地址策略（逐引擎）</h2>
 * <ul>
 *   <li>postgres/sqlite 免检；elasticsearch/opensearch/milvus/tencent/doris 检 {@code addr}；
 *       qdrant 检 {@code host:port}（host 空或 port 0 时只检 host，去方括号）；
 *       weaviate 检 {@code host} + {@code grpc_address} 两处；未知类型报
 *       {@code vector store engine "<t>" has no SSRF address policy}；空地址放行；
 *       非空地址过 SSRF 校验，失败文案 {@code <label> failed SSRF validation: <err>}</li>
 *   <li>索引配置取值：shards 缺省 <b>0</b>、replicas 缺省 <b>-1</b>——
 *       0/-1 视为"未设置"，由各驱动按内部缺省落地</li>
 *   <li>不支持的类型文案 {@code unsupported engine type: <t>}（注意在策略校验之后抛出，
 *       对"策略未覆盖的类型"实际不可达）</li>
 * </ul>
 */
public final class EngineFactory {

    private EngineFactory() {
    }

    /** 建不起来时的失败（调用方可据文案分类）。 */
    public static class EngineNotSupportedException extends RuntimeException {
        public EngineNotSupportedException(String message) {
            super(message);
        }
    }

    /**
     * 先做地址策略校验，再按引擎类型建服务。
     * {@code guard} 为空 = 测试口（跳过地址校验）。
     */
    public static KeywordsVectorHybridRetrieveEngineService createFromStore(VectorStore store,
                                                                            SsrfGuard guard) {
        return createFromStore(store, guard, null);
    }

    /**
     * 带 audit sink 的重载——opensearch 注入
     * {@code WithAuditSink}（索引创建/重索引事件）；其它引擎忽略。
     * {@code sink} 为 null = no-op（测试口）。
     */
    public static KeywordsVectorHybridRetrieveEngineService createFromStore(VectorStore store,
                                                                            SsrfGuard guard,
                                                                            OpenSearchRetrieveRepository.AuditSink auditSink) {
        validateRuntimeVectorStoreAddresses(store, guard);
        String engineType = store.getEngineType() == null ? "" : store.getEngineType();
        switch (engineType) {
            case EngineTypes.ENGINE_ELASTICSEARCH: {
                // 与其余分支同款判空：connection_config 为 NULL 的 DB-store 行
                // （type handler 可能给 null）在启动/重建期会 NPE
                ConnectionConfig cc = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                IndexConfig idx = store.getIndexConfig();
                String indexName = idx == null ? "" : idx.indexName;
                int shards = idx != null && idx.numberOfShards > 0 ? idx.numberOfShards : 0;
                int replicas = idx != null && idx.numberOfReplicas > 0 ? idx.numberOfReplicas : -1;
                boolean v7 = cc.version != null && cc.version.startsWith("7.");
                RetrieveEngineRepository repo = v7
                        ? new ElasticsearchV7RetrieveRepository(cc.addr, indexName, shards,
                                replicas, cc.username, cc.password, guard)
                        : new ElasticsearchV8RetrieveRepository(cc.addr, indexName, shards,
                                replicas, cc.username, cc.password, guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_ELASTICSEARCH);
            }
            case EngineTypes.ENGINE_POSTGRES:
                // postgres 的读/写由
                // PgVectorRetrieveRepository / VectorStoreService（JDBC）承担，不经引擎工厂。
                throw new EngineNotSupportedException(
                        "postgres retriever is served by the existing JDBC pieces"
                        + " (PgVectorRetrieveRepository / VectorStoreService), not by this"
                        + " factory");
            case EngineTypes.ENGINE_SQLITE: {
                // **忽略 store 的 connection_config**——SQLite 引擎不按店连接，
                // 介质是一颗独立 SQLite 文件：路径取 connection_config.addr（若像路径）
                // → env SQLITE_PATH → 缺省 ./data/weknora-retrieval.sqlite。
                // 地址免 SSRF（无网络面）。
                ConnectionConfig ccSqlite = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                SqliteRetrieveRepository repo = SqliteRetrieveRepository.create(
                        SqliteRetrieveRepository.resolvePath(ccSqlite.addr));
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_SQLITE);
            }
            case EngineTypes.ENGINE_OPENSEARCH: {
                // env-store（前缀 id）折叠为 ""——
                // env store 共享集群、无 per-store 索引前缀；索引名 ≥16
                // 字符规则由驱动强制。探针（版本 + k-NN 插件）在构造期显形。
                ConnectionConfig ccOs = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                IndexConfig idxOs = store.getIndexConfig();
                String storeId = com.ragagent.vectorstore.domain.EnvVectorStores
                        .isEnvStoreId(store.getId()) ? "" : store.getId();
                OpenSearchRetrieveRepository repo = new OpenSearchRetrieveRepository(
                        ccOs.addr, storeId, idxOs, ccOs.username, ccOs.password,
                        ccOs.insecureSkipVerify, guard);
                if (auditSink != null) {
                    repo.withAuditSink(auditSink);
                }
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_OPENSEARCH);
            }
            case EngineTypes.ENGINE_DORIS: {
                // Addr 承担 host:9030 的 MySQL 端点；
                // HTTPPort + Addr 的 host 部分组成 Stream Load 的 HTTP base（缺省 FE 8030）。
                ConnectionConfig ccDoris = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                String addr = ccDoris.addr == null ? "" : ccDoris.addr;
                if (addr.isEmpty()) {
                    throw new EngineNotSupportedException(
                            "doris connection requires addr (host:port)");
                }
                if (ccDoris.database == null || ccDoris.database.isEmpty()) {
                    throw new EngineNotSupportedException("doris connection requires database");
                }
                int httpPort = ccDoris.httpPort > 0 ? ccDoris.httpPort : 8030;
                String httpBase = "http://" + DorisRetrieveRepository.hostFromAddr(addr)
                        + ":" + httpPort;
                DorisRetrieveRepository repo = DorisRetrieveRepository.create(addr, httpBase,
                        ccDoris.username, ccDoris.password, ccDoris.database,
                        store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_DORIS);
            }
            case EngineTypes.ENGINE_QDRANT: {
                // host（空=localhost 由客户端缺省）、port 缺省 6334、
                // api_key、use_tls；地址策略在 validateRuntimeVectorStoreAddresses 已校验。
                ConnectionConfig ccQdrant = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                int port = ccQdrant.port == 0 ? 6334 : ccQdrant.port;
                QdrantRetrieveRepository repo = QdrantRetrieveRepository.create(ccQdrant.host,
                        port, ccQdrant.apiKey, ccQdrant.useTls, store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_QDRANT);
            }
            case EngineTypes.ENGINE_WEAVIATE: {
                // host 缺省 weaviate:8080、scheme 缺省 http、
                // api_key 直取（工厂路径不看 WEAVIATE_AUTH_ENABLED）；
                // grpc_address 缺省 weaviate:50051 但本实现走 REST（见驱动类注释）。
                ConnectionConfig ccWeaviate = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                String weaviateHost = ccWeaviate.host == null || ccWeaviate.host.isEmpty()
                        ? "weaviate:8080" : ccWeaviate.host;
                String weaviateScheme = ccWeaviate.scheme == null || ccWeaviate.scheme.isEmpty()
                        ? "http" : ccWeaviate.scheme;
                WeaviateRetrieveRepository repo = WeaviateRetrieveRepository.create(weaviateHost,
                        weaviateScheme, ccWeaviate.apiKey, store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_WEAVIATE);
            }
            case EngineTypes.ENGINE_MILVUS: {
                // addr 缺省 localhost:19530；username/password/
                // database 非空才设；本实现走 REST v2（见驱动类注释）。
                ConnectionConfig ccMilvus = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                String milvusAddr = ccMilvus.addr == null || ccMilvus.addr.isEmpty()
                        ? "localhost:19530" : ccMilvus.addr;
                MilvusRetrieveRepository repo = MilvusRetrieveRepository.create(milvusAddr,
                        ccMilvus.username, ccMilvus.password, ccMilvus.database,
                        store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_MILVUS);
            }
            case EngineTypes.ENGINE_TENCENT_VECTORDB: {
                // addr 必填（只收 http:// 或裸 host，
                // https 被拒）、username/apiKey 必填（"username or key is empty"）。
                ConnectionConfig ccTencent = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                TencentVectorDbRetrieveRepository repo = TencentVectorDbRetrieveRepository.create(
                        ccTencent.addr, ccTencent.username, ccTencent.apiKey, ccTencent.database,
                        store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_TENCENT_VECTORDB);
            }
            default:
                // validate 的 default 分支先报"无地址策略"，此处实际不可达
                throw new EngineNotSupportedException("unsupported engine type: " + engineType);
        }
    }

    /** 逐引擎的地址策略 + SSRF 校验。 */
    static void validateRuntimeVectorStoreAddresses(VectorStore store, SsrfGuard guard) {
        ConnectionConfig cc = store.getConnectionConfig() == null ? new ConnectionConfig()
                : store.getConnectionConfig();
        String engineType = store.getEngineType() == null ? "" : store.getEngineType();
        switch (engineType) {
            case EngineTypes.ENGINE_POSTGRES:
            case EngineTypes.ENGINE_SQLITE:
                return;
            case EngineTypes.ENGINE_ELASTICSEARCH:
            case EngineTypes.ENGINE_OPENSEARCH:
            case EngineTypes.ENGINE_MILVUS:
            case EngineTypes.ENGINE_TENCENT_VECTORDB:
            case EngineTypes.ENGINE_DORIS:
                check("vector store address", cc.addr, guard);
                return;
            case EngineTypes.ENGINE_QDRANT: {
                String endpoint = cc.host == null ? "" : cc.host;
                if (!endpoint.isEmpty() && cc.port != 0) {
                    endpoint = "[" + stripBrackets(endpoint) + "]:" + cc.port;
                }
                check("qdrant address", endpoint, guard);
                return;
            }
            case EngineTypes.ENGINE_WEAVIATE:
                check("weaviate HTTP address", cc.host, guard);
                check("weaviate gRPC address", cc.grpcAddress, guard);
                return;
            default:
                throw new EngineNotSupportedException("vector store engine \"" + engineType
                        + "\" has no SSRF address policy");
        }
    }

    private static String stripBrackets(String host) {
        String trimmed = host.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void check(String label, String endpoint, SsrfGuard guard) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.isEmpty()) {
            return;
        }
        if (guard == null) {
            return;
        }
        try {
            guard.validateURLForSSRF(value);
        } catch (RuntimeException e) {
            throw new EngineNotSupportedException(
                    label + " failed SSRF validation: " + e.getMessage());
        }
    }

}
