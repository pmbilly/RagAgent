package com.ragagent.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.knowledge.service.VectorStoreService;
import com.ragagent.retrieval.engine.EngineFactory;
import com.ragagent.retrieval.engine.EngineRegistry;
import com.ragagent.retrieval.engine.EngineTypes;
import com.ragagent.retrieval.engine.KeywordsVectorHybridRetrieveEngineService;
import com.ragagent.retrieval.engine.opensearch.OpenSearchRetrieveRepository;
import com.ragagent.retrieval.engine.PgVectorEngineRepository;
import com.ragagent.retrieval.engine.PgVectorRetrieveRepository;
import com.ragagent.retrieval.engine.RetrieveEngineService;
import com.ragagent.retrieval.engine.StoreEngineFactory;
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
 *       其余驱动（sqlite/qdrant/milvus/weaviate/doris/tencent_vectordb/opensearch）未落地，
 *       明确 WARN（Go 会真注册——诚实降级备案，随 driver 批补）。</li>
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
                                                  OpenSearchAuditSinkAdapter osAuditSink) {
        // DB-store 工厂带 OpenSearch 的 audit sink（照 Go createOpenSearchEngine 的
        // WithAuditSink；其它引擎忽略 sink——Go 同）
        EngineRegistry registry = new EngineRegistry(storeRepo,
                store -> EngineFactory.createFromStore(store, guard, osAuditSink));
        // Go: strings.Split(os.Getenv("RETRIEVE_DRIVER"), ",")——不 trim，精确匹配
        String driver = System.getenv("RETRIEVE_DRIVER");
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
