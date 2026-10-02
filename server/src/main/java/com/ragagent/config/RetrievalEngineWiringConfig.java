package com.ragagent.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ragagent.common.retrieval.RetrievalDriverProperties;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.retrieval.engine.VectorStoreService;
import com.ragagent.retrieval.engine.EngineFactory;
import com.ragagent.retrieval.engine.EngineRegistry;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.KeywordsVectorHybridRetrieveEngineService;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieveEngineService;
import com.ragagent.retrieval.engine.TenantStoreOwnership;
import com.ragagent.retrieval.engine.VectorStoreRepoOwnership;
import com.ragagent.vectorstore.mapper.VectorStoreRepository;

/**
 * 检索引擎层的生产装配——对照 Go {@code container.BuildContainer} 的
 * {@code initRetrieveEngineRegistry}（container.go L1122-1200+）。
 *
 * <h2>装配三件</h2>
 * <ul>
 *   <li>{@link EngineRegistry}：挂 storeRepo + 引擎工厂（含 SSRF 地址策略），
 *       让 DB-store（vector_stores 表绑定）能按需重建；</li>
 *   <li><b>env-store 注册</b>：按 {@code RETRIEVE_DRIVER} 逐段注册进程级引擎——
 *       postgres 由 {@link PgVectorEngineRepository} 承担（既有 JDBC 件的引擎口适配）；
 *       elasticsearch_v7/v8 从 {@code ELASTICSEARCH_ADDR/USERNAME/PASSWORD} 现场建驱动；
 *       opensearch / doris / qdrant / weaviate / milvus 同法（{@code OPENSEARCH_*} /
 *       {@code DORIS_*} / {@code QDRANT_*} / {@code WEAVIATE_*} / {@code MILVUS_*} /
 *       {@code TENCENT_VECTORDB_*} / sqlite（{@code SQLITE_PATH}，独立文件）；
 *       <b>九家店至此全部落地</b>。</li>
 *   <li>{@link TenantStoreOwnership}：store 归属查表（工厂的跨租户防御）。</li>
 * </ul>
 *
 * <p>注册失败只记日志继续（照 Go 的 {@code Register ... failed: %v}）——注册表缺一个
 * env-store 时检索按"租户有效引擎里挑得到谁"运行，不阻塞启动。</p>
 */
@Configuration
public class RetrievalEngineWiringConfig {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEngineWiringConfig.class);

    @Bean
    public PgVectorEngineRepository pgVectorEngineRepository(PgVectorRetrieveRepository readRepo,
                                                             VectorStoreService writeSvc,
                                                             DataSource dataSource) {
        return new PgVectorEngineRepository(readRepo, writeSvc, dataSource);
    }

    @Bean
    public EngineRegistry retrievalEngineRegistry(VectorStoreRepository storeRepo, SsrfGuard guard,
                                                  PgVectorEngineRepository pgAdapter,
                                                  OpenSearchAuditSinkAdapter osAuditSink,
                                                  RetrievalDriverProperties driverProperties) {
        // DB-store 工厂带 OpenSearch 的 audit sink（照 Go createOpenSearchEngine 的
        // WithAuditSink；其它引擎忽略 sink——Go 同）
        EngineRegistry registry = new EngineRegistry(storeRepo,
                store -> EngineFactory.createFromStore(store, guard, osAuditSink));
        // Go: strings.Split(os.Getenv("RETRIEVE_DRIVER"), ",")——不 trim，精确匹配
        String driver = driverProperties.driver();
        String[] drivers = driver == null ? new String[] {""} : driver.split(",");
        registerEnvStores(registry, drivers, pgAdapter, osAuditSink, guard);
        return registry;
    }

    /** env-store 注册主体（抽出便于测试：传定 drivers 而不读进程环境）。 */
    static void registerEnvStores(EngineRegistry registry, String[] drivers,
                                  PgVectorEngineRepository pgAdapter,
                                  OpenSearchAuditSinkAdapter osAuditSink, SsrfGuard guard) {
        for (String d : drivers) {
            switch (d == null ? "" : d) {
                case "postgres":
                    register(registry, new KeywordsVectorHybridRetrieveEngineService(pgAdapter,
                            EngineTypes.ENGINE_POSTGRES), "postgres");
                    break;
                case "elasticsearch_v8":
                    envElasticsearch(registry, false);
                    break;
                case "elasticsearch_v7":
                    envElasticsearch(registry, true);
                    break;
                case "opensearch":
                    envOpenSearch(registry, osAuditSink, guard);
                    break;
                case "doris":
                    envDoris(registry, guard);
                    break;
                case "qdrant":
                    envQdrant(registry, guard);
                    break;
                case "weaviate":
                    envWeaviate(registry, guard);
                    break;
                case "milvus":
                    envMilvus(registry, guard);
                    break;
                case "tencent_vectordb":
                    envTencentVectorDb(registry, guard);
                    break;
                case "sqlite":
                    envSqlite(registry);
                    break;
                case "":
                    break;
                default:
                    log.warn("retriever engine {} driver not ported in this deployment"
                            + " (tracked as W5γ4 follow-up)", d);
                    break;
            }
        }
    }

    /**
     * env-path 的 OpenSearch 注册——照 Go container.go L1205-1227：连接配置取
     * OPENSEARCH_ADDR/USERNAME/PASSWORD/OPENSEARCH_INSECURE_SKIP_VERIFY（equalFold
     * "true"）；client 失败 / repository 失败（探针：版本 + 每节点 k-NN 插件）/
     * Register 失败分段记 error，互不掩盖。与 ES 的 env-path 不同：OpenSearch 的
     * 客户端构造<b>无条件过 SSRF 校验</b>（Go 的 NewOpenSearchClient 内置）→ 传 guard。
     */
    private static void envOpenSearch(EngineRegistry registry, OpenSearchAuditSinkAdapter sink,
                                      SsrfGuard guard) {
        String label = "opensearch";
        String addr = env("OPENSEARCH_ADDR");
        if (addr.isEmpty()) {
            log.error("Create {} client failed: {}", label,
                    "opensearch: ConnectionConfig.Addr required: opensearch: invalid index config");
            return;
        }
        boolean insecure = "true".equalsIgnoreCase(env("OPENSEARCH_INSECURE_SKIP_VERIFY"));
        try {
            OpenSearchRetrieveRepository repo = new OpenSearchRetrieveRepository(addr, "",
                    null, env("OPENSEARCH_USERNAME"), env("OPENSEARCH_PASSWORD"), insecure,
                    guard);
            if (sink != null) {
                repo.withAuditSink(sink);
            }
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_OPENSEARCH), label);
        } catch (RuntimeException e) {
            log.error("Create {} repository failed: {}", label, e.getMessage());
        }
    }

    /**
     * env-path 的 Doris 注册——照 Go container.go L1355-1400：{@code DORIS_ADDR}（缺省
     * {@code doris-fe:9030}）/ {@code DORIS_DATABASE}（缺省 {@code weknora}）/
     * {@code DORIS_USERNAME}（缺省 {@code root}）/ {@code DORIS_PASSWORD} /
     * {@code DORIS_HTTP_PORT}（缺省 8030）；Stream Load 的 HTTP base = addr 的 host + 该端口。
     * 地址过 SSRF 校验（Go 的全局 MySQL dialer 在拨号时校验；本仓在构造期校验一次——同
     * ES/OpenSearch 驱动的姿态）。
     */
    private static void envDoris(EngineRegistry registry, SsrfGuard guard) {
        String label = "doris";
        String addr = env("DORIS_ADDR");
        if (addr.isEmpty()) {
            addr = "doris-fe:9030";
        }
        String database = env("DORIS_DATABASE");
        if (database.isEmpty()) {
            database = "weknora";
        }
        String username = env("DORIS_USERNAME");
        if (username.isEmpty()) {
            username = "root";
        }
        String password = env("DORIS_PASSWORD");
        int httpPort = 8030;
        String rawPort = env("DORIS_HTTP_PORT");
        if (!rawPort.isEmpty()) {
            try {
                httpPort = Integer.parseInt(rawPort);
            } catch (NumberFormatException ignored) {
                // Go：strconv.Atoi 失败 → 保留缺省
            }
        }
        String httpBase = "http://"
                + com.ragagent.retrieval.engine.doris.DorisRetrieveRepository.hostFromAddr(addr)
                + ":" + httpPort;
        try {
            com.ragagent.retrieval.engine.doris.DorisRetrieveRepository repo =
                    com.ragagent.retrieval.engine.doris.DorisRetrieveRepository.create(
                            addr, httpBase, username, password, database, null, guard);
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_DORIS), label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    /**
     * env-path 的 Qdrant 注册——照 Go container.go L1228-1270：{@code QDRANT_HOST}（缺省
     * {@code localhost}）/ {@code QDRANT_PORT}（缺省 6334，Atoi 失败保缺省）/
     * {@code QDRANT_API_KEY} / {@code QDRANT_USE_TLS}（非 "false"/"0" 即开，大小写不敏感 +
     * trim）。地址过 SSRF 校验（Go 的 gRPC dialer 在拨号时校验；本仓构造期一次）。
     */
    private static void envQdrant(EngineRegistry registry, SsrfGuard guard) {
        String label = "qdrant";
        String host = env("QDRANT_HOST");
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = 6334;
        String rawPort = env("QDRANT_PORT");
        if (!rawPort.isEmpty()) {
            try {
                port = Integer.parseInt(rawPort);
            } catch (NumberFormatException ignored) {
                // Go：strconv.Atoi 失败 → 保留缺省
            }
        }
        boolean useTls = false;
        String rawTls = env("QDRANT_USE_TLS");
        if (!rawTls.isEmpty()) {
            String lower = rawTls.trim().toLowerCase(java.util.Locale.ROOT);
            useTls = !"false".equals(lower) && !"0".equals(lower);
        }
        try {
            com.ragagent.retrieval.engine.qdrant.QdrantRetrieveRepository repo =
                    com.ragagent.retrieval.engine.qdrant.QdrantRetrieveRepository.create(host,
                            port, env("QDRANT_API_KEY"), useTls, null, guard);
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_QDRANT), label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    /**
     * env-path 的 Weaviate 注册——照 Go container.go L1280-1315：{@code WEAVIATE_HOST}
     * （缺省 {@code weaviate:8080}）/ {@code WEAVIATE_GRPC_ADDRESS}（缺省
     * {@code weaviate:50051}；本实现走 REST，仅作配置面保留）/ {@code WEAVIATE_SCHEME}
     * （缺省 http）/ {@code WEAVIATE_AUTH_ENABLED}（equalFold "true" 且 API key 非空才带）
     * + {@code WEAVIATE_API_KEY}。地址过 SSRF 校验（Go 是自定义 HTTP 客户端逐请求校验）。
     */
    private static void envWeaviate(EngineRegistry registry, SsrfGuard guard) {
        String label = "weaviate";
        String host = env("WEAVIATE_HOST");
        if (host.isEmpty()) {
            host = "weaviate:8080";
        }
        String scheme = env("WEAVIATE_SCHEME");
        if (scheme.isEmpty()) {
            scheme = "http";
        }
        String apiKey = "";
        if ("true".equalsIgnoreCase(env("WEAVIATE_AUTH_ENABLED").trim())) {
            apiKey = env("WEAVIATE_API_KEY").trim();
        }
        try {
            com.ragagent.retrieval.engine.weaviate.WeaviateRetrieveRepository repo =
                    com.ragagent.retrieval.engine.weaviate.WeaviateRetrieveRepository.create(host,
                            scheme, apiKey, null, guard);
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_WEAVIATE), label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    /**
     * env-path 的 Milvus 注册——照 Go container.go L1318-1355：{@code MILVUS_ADDRESS}
     * （缺省 {@code localhost:19530}）/ {@code MILVUS_USERNAME} / {@code MILVUS_PASSWORD} /
     * {@code MILVUS_DB_NAME}（均非空才设，照 Go）。地址过 SSRF 校验（Go 是 gRPC dialer 逐拨号）。
     */
    private static void envMilvus(EngineRegistry registry, SsrfGuard guard) {
        String label = "milvus";
        String addr = env("MILVUS_ADDRESS");
        if (addr.isEmpty()) {
            addr = "localhost:19530";
        }
        try {
            com.ragagent.retrieval.engine.milvus.MilvusRetrieveRepository repo =
                    com.ragagent.retrieval.engine.milvus.MilvusRetrieveRepository.create(addr,
                            env("MILVUS_USERNAME"), env("MILVUS_PASSWORD"), env("MILVUS_DB_NAME"),
                            null, guard);
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_MILVUS), label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    /**
     * env-path 的腾讯 VectorDB 注册——照 Go container.go L1402-1432：{@code TENCENT_VECTORDB_ADDR}
     * / {@code TENCENT_VECTORDB_USERNAME} / {@code TENCENT_VECTORDB_API_KEY} <b>三者缺一即拒</b>
     * （Go 只打 "Missing Tencent VectorDB configuration" 日志并跳过）+ {@code TENCENT_VECTORDB_DATABASE}
     * （缺省 {@code weknora}）。HTTP 客户端构造不拨号（首个请求才连）。
     */
    private static void envTencentVectorDb(EngineRegistry registry, SsrfGuard guard) {
        String label = "tencent_vectordb";
        String addr = env("TENCENT_VECTORDB_ADDR");
        String username = env("TENCENT_VECTORDB_USERNAME");
        String apiKey = env("TENCENT_VECTORDB_API_KEY");
        if (addr.isEmpty() || username.isEmpty() || apiKey.isEmpty()) {
            log.error("Missing Tencent VectorDB configuration");
            return;
        }
        try {
            com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRetrieveRepository repo =
                    com.ragagent.retrieval.engine.tencentvectordb.TencentVectorDbRetrieveRepository
                            .create(addr, username, apiKey, env("TENCENT_VECTORDB_DATABASE"),
                                    null, guard);
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_TENCENT_VECTORDB), label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    /**
     * env-path 的 SQLite 注册——照 Go container.go L1151-1160（直接用产品库的 {@code db}）。
     * 本仓产品库是 PG：改为一颗独立 SQLite 文件，路径 {@code SQLITE_PATH}（缺省
     * {@code ./data/weknora-retrieval.sqlite}）。建表/建 FTS 在构造期完成（照 Go 的
     * AutoMigrate + initFTS5）；失败只记日志不炸装配（照 Go 的 Register 失败分支）。
     */
    private static void envSqlite(EngineRegistry registry) {
        try {
            com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository repo =
                    com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository.create(
                            com.ragagent.retrieval.engine.sqlite.SqliteRetrieveRepository
                                    .resolvePath(null));
            register(registry, new KeywordsVectorHybridRetrieveEngineService(repo,
                    EngineTypes.ENGINE_SQLITE), "sqlite");
        } catch (RuntimeException e) {
            log.error("Register sqlite retrieve engine failed: {}", e.toString());
        }
    }

    @Bean
    public TenantStoreOwnership tenantStoreOwnership(VectorStoreRepository storeRepo) {
        return new VectorStoreRepoOwnership(storeRepo);
    }

    private static void register(EngineRegistry registry, RetrieveEngineService service,
                                 String label) {
        try {
            registry.register(service);
        } catch (RuntimeException e) {
            log.error("Register {} retrieve engine failed: {}", label, e.getMessage());
            return;
        }
        log.info("Register {} retrieve engine success", label);
    }

    /**
     * env-path 的 ES 注册——照 Go：客户端从 ELASTICSEARCH_* env 建，索引名/shards/replicas
     * 走缺省（indexCfg=nil 的语义）；地址是运维给的，不走 SSRF 组件（guard 传 null，
     * 与 Go 的 env-path 无 SSRF RoundTripper 一致——SSRF 校验属 DB-store 工厂路径）。
     */
    private static void envElasticsearch(EngineRegistry registry, boolean v7) {
        String label = v7 ? "elasticsearch_v7" : "elasticsearch_v8";
        String addr = env("ELASTICSEARCH_ADDR");
        if (addr.isEmpty()) {
            log.error("Create {} client failed: ELASTICSEARCH_ADDR is required", label);
            return;
        }
        try {
            KeywordsVectorHybridRetrieveEngineService engine =
                    new KeywordsVectorHybridRetrieveEngineService(v7
                            ? new com.ragagent.retrieval.engine.elasticsearch
                                    .ElasticsearchV7RetrieveRepository(addr, "", 0, -1,
                                    env("ELASTICSEARCH_USERNAME"), env("ELASTICSEARCH_PASSWORD"), null)
                            : new com.ragagent.retrieval.engine.elasticsearch
                                    .ElasticsearchV8RetrieveRepository(addr, "", 0, -1,
                                    env("ELASTICSEARCH_USERNAME"), env("ELASTICSEARCH_PASSWORD"), null),
                            EngineTypes.ENGINE_ELASTICSEARCH);
            register(registry, engine, label);
        } catch (RuntimeException e) {
            log.error("Create {} client failed: {}", label, e.toString());
        }
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v;
    }
}
