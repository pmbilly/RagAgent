package com.ragagent.retrieval.engine;


import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.doris.DorisRetrieveRepository;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV7RetrieveRepository;
import com.ragagent.retrieval.engine.elasticsearch.ElasticsearchV8RetrieveRepository;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;
import com.ragagent.retrieval.engine.qdrant.QdrantRetrieveRepository;
import com.ragagent.vectorstore.domain.ConnectionConfig;
import com.ragagent.vectorstore.domain.IndexConfig;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 检索引擎工厂——对照 Go {@code internal/container/engine_factory.go}（391 行）的
 * {@code createEngineServiceFromStore} + {@code validateRuntimeVectorStoreAddresses}。
 *
 * <h2>本批落地范围</h2>
 * <ul>
 *   <li>**elasticsearch**：按 {@code connection_config.version} 前缀选 v7/v8（空 → v8，照
 *       {@code isESv7}），建 driver → 包成
 *       {@link KeywordsVectorHybridRetrieveEngineService}（照 {@code NewKVHybridRetrieveEngine}）</li>
 *   <li>**postgres/sqlite**：Go 走 GORM/JDBC 直连（{@code postgresRepo.NewPostgresRetrieveEngineRepository}）；
 *       本仓 postgres 由既有 JDBC 件承担（读 {@code PgVectorRetrieveRepository} / 写
 *       {@code VectorStoreService}），不经本工厂 → 明确指引式 XDEP；sqlite driver 未落地</li>
 *   <li>**opensearch**：照 createOpenSearchEngine 真落地（k-NN 驱动 +
 *       audit sink 注入；探针在构造期显形）</li>
 *   <li>**doris**：照 {@code createDorisEngine} 真落地（MySQL 协议主链路 + Stream Load
 *       HTTP；addr 必填、database 必填、http_port 缺省 8030）</li>
 *   <li>**qdrant**：照 {@code createQdrantEngine} 真落地——**REST 自持**（Go 是 gRPC
 *       客户端；本仓照 ES/OpenSearch 先例走 HTTP/JSON：host/port（缺省 6334）/api_key/
 *       use_tls）</li>
 *   <li>**milvus/weaviate/tencent_vectordb**：driver 未落地 →
 *       诚实 XDEP（milvus/weaviate/腾讯属 "协议决策"族）</li>
 * </ul>
 *
 * <h2>照抄点</h2>
 * <ul>
 *   <li>地址策略（{@code validateRuntimeVectorStoreAddresses}）**逐引擎**：
 *       postgres/sqlite 免检；elasticsearch/opensearch/milvus/tencent/doris 检 {@code addr}；
 *       qdrant 检 {@code host:port}（host 空或 port 0 时只检 host，去方括号）；
 *       weaviate 检 {@code host} + {@code grpc_address} 两处；未知类型报
 *       {@code vector store engine "<t>" has no SSRF address policy}；空地址放行；
 *       非空地址过 SSRF 校验，失败文案 {@code <label> failed SSRF validation: <err>}</li>
 *   <li>索引配置取值：shards 缺省 <b>0</b>、replicas 缺省 <b>-1</b>（照
 *       {@code GetNumberOfShards(0)} / {@code GetNumberOfReplicas(-1)}——
 *       Go 的文档注明 0 视为"未设置"）</li>
 *   <li>不支持的类型文案 {@code unsupported engine type: <t>}（照 Go；注意在策略校验之后，
 *       与 Go 一样对"策略未覆盖的类型"实际不可达）</li>
 * </ul>
 */
public final class EngineFactory {

    private EngineFactory() {
    }

    /** 建不起来时的失败（照 Go 的 error 链；调用方可据文案分类）。 */
    public static class EngineNotSupportedException extends RuntimeException {
        public EngineNotSupportedException(String message) {
            super(message);
        }
    }

    /**
     * 对照 {@code createEngineServiceFromStore}：先做地址策略校验，再按引擎类型建服务。
     * {@code guard} 为空 = 测试口（跳过地址校验，照 Go 的"无 SSRF 组件"不可达情形）。
     */
    public static KeywordsVectorHybridRetrieveEngineService createFromStore(VectorStore store,
                                                                            SsrfGuard guard) {
        return createFromStore(store, guard, null);
    }

    /**
     * 带 audit sink 的重载——Go 的 {@code createOpenSearchEngine} 注入
     * {@code WithAuditSink}（索引创建/重索引事件）；其它引擎忽略（Go 同）。
     * {@code sink} 为 null = no-op（测试口）。
     */
    public static KeywordsVectorHybridRetrieveEngineService createFromStore(VectorStore store,
                                                                            SsrfGuard guard,
                                                                            OpenSearchRetrieveRepository.AuditSink auditSink) {
        validateRuntimeVectorStoreAddresses(store, guard);
        String engineType = store.getEngineType() == null ? "" : store.getEngineType();
        switch (engineType) {
            case EngineTypes.ENGINE_ELASTICSEARCH: {
                ConnectionConfig cc = store.getConnectionConfig();
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
                // 备案：Go 在此返回 postgresRepo（GORM 直连）；本仓 postgres 的读/写分别由
                // PgVectorRetrieveRepository / VectorStoreService（JDBC）承担，不经引擎工厂。
                throw new EngineNotSupportedException(
                        "postgres retriever is served by the existing JDBC pieces"
                        + " (PgVectorRetrieveRepository / VectorStoreService), not by this"
                        + " factory");
            case EngineTypes.ENGINE_SQLITE:
                throw new EngineNotSupportedException("retriever engine sqlite driver not ported"
                        + " in this batch (tracked as W5γ4 follow-up)");
            case EngineTypes.ENGINE_OPENSEARCH: {
                // 照 Go createOpenSearchEngine：env-store（前缀 id）折叠为 ""——
                // env store 共享集群、无 per-store 索引前缀；NewRepository 的 ≥16
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
                // 照 Go createDorisEngine：Addr 承担 host:9030 的 MySQL 端点；
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
                // 照 Go createQdrantEngine：host（空=localhost 由客户端缺省）、port 缺省 6334、
                // api_key、use_tls；地址策略在 validateRuntimeVectorStoreAddresses 已校验。
                ConnectionConfig ccQdrant = store.getConnectionConfig() == null
                        ? new ConnectionConfig() : store.getConnectionConfig();
                int port = ccQdrant.port == 0 ? 6334 : ccQdrant.port;
                QdrantRetrieveRepository repo = QdrantRetrieveRepository.create(ccQdrant.host,
                        port, ccQdrant.apiKey, ccQdrant.useTls, store.getIndexConfig(), guard);
                return new KeywordsVectorHybridRetrieveEngineService(repo,
                        EngineTypes.ENGINE_QDRANT);
            }
            case EngineTypes.ENGINE_MILVUS:
            case EngineTypes.ENGINE_WEAVIATE:
            case EngineTypes.ENGINE_TENCENT_VECTORDB:
                throw new EngineNotSupportedException("retriever engine " + engineType
                        + " driver not ported in this batch (tracked as W5γ4 follow-up)");
            default:
                // 照 Go：validate 的 default 分支先报"无地址策略"，此处实际不可达
                throw new EngineNotSupportedException("unsupported engine type: " + engineType);
        }
    }

    /** 对照 {@code validateRuntimeVectorStoreAddresses}：逐引擎的地址策略 + SSRF 校验。 */
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
