package com.ragagent.system.service;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.ragagent.storage.domain.StorageBackend;
import com.ragagent.common.retrieval.RetrievalDriverProperties;
import com.ragagent.common.storage.StorageAllowList;
import com.ragagent.storage.mapper.StorageBackendRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

/**
 * /system 组读端点的计算逻辑（对照 Go internal/handler/system.go 的
 * GetSystemInfo / GetStorageEngineStatus 与 deployment_capabilities.go 的快照装配）。
 *
 * <p><b>已知差异（部署状态语义，非降级）</b>：</p>
 * <ul>
 *   <li>version/commit_id/build_time：Go 用 ldflags 注入（缺注入的 dev 形态恒 "unknown"）；
 *       Java 改用 Spring Boot build-info.properties（构建期生成，dev 也有真实值），
 *       {@code weknora.system.*} 配置仍可覆盖（测试固定值走这条路径）。</li>
 *   <li>java_version（原 go_version）：字段随实现改名——Java 后端没有 Go 版本，
 *       此处输出 JVM 运行时版本（{@code System.getProperty("java.version")}）。
 *       前端 SystemInfo.vue 与 i18n 已同步。</li>
 *   <li>db_version：Go 读 golang-migrate 的缓存版本；Java 读同一 dev 库的
 *       flyway_schema_history（同一套迁移、同一条数据库）。H2 测试库无该表 → 空串省略。</li>
 *   <li>graph_database_engine：Go 看 neo4j driver 是否为 nil；Java 看 NEO4J_ENABLE env
 *       （同一判定源，未启用 → "Not Enabled"）。</li>
 *   <li>vector_store_engine：Go 先看 cfg.VectorDatabase.Driver（yaml）；Java 无该 cfg
 *       层，恒走 RETRIEVE_DRIVER env 路径（dev 两侧 yaml/env 都未配置 → "未配置"）。</li>
 * </ul>
 */
@Service
public class SystemInfoService {

    private static final DateTimeFormatter RFC3339_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final StorageAllowList allowList;
    private final StorageBackendRepository backendRepository;
    private final DataSource dataSource;
    /** 构建期生成的 META-INF/build-info.properties；缺失（如纯 IDE 运行）时回退 "unknown"。 */
    private final ObjectProvider<BuildProperties> buildProperties;
    /** 图库仓储（D 批）：引擎名按**真实驱动**报告（对照 Go 的 neo4jDriver != nil 判定）。 */
    private final com.ragagent.retrieval.graph.RetrieveGraphRepository graphRepository;
    /** RETRIEVE_DRIVER（B6 批 3：属性绑定，取代裸 env 读；未配置 → 页面显示「未配置」）。 */
    private final RetrievalDriverProperties driverProperties;

    /** 覆盖项（配置/测试可固定值）；为空则取构建信息或运行时值。edition 无构建注入。 */
    @Value("${weknora.system.version:}")
    private String versionOverride;
    @Value("${weknora.system.edition:standard}")
    private String edition;
    @Value("${weknora.system.commit-id:}")
    private String commitIdOverride;
    @Value("${weknora.system.build-time:}")
    private String buildTimeOverride;
    @Value("${weknora.system.java-version:}")
    private String javaVersionOverride;

    public SystemInfoService(StorageAllowList allowList,
                             StorageBackendRepository backendRepository,
                             DataSource dataSource,
                             ObjectProvider<BuildProperties> buildProperties,
                             com.ragagent.retrieval.graph.RetrieveGraphRepository graphRepository,
                             RetrievalDriverProperties driverProperties) {
        this.allowList = allowList;
        this.backendRepository = backendRepository;
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
        this.driverProperties = driverProperties;
        this.graphRepository = graphRepository;
    }

    public String getVersion() {
        return orUnknown(firstNonEmpty(versionOverride, buildInfoVersion()));
    }

    public String getEdition() { return edition; }

    public String getCommitId() {
        return orUnknown(firstNonEmpty(commitIdOverride, buildInfo("commitId")));
    }

    public String getBuildTime() {
        BuildProperties bp = buildProperties.getIfAvailable();
        String fromBuild = bp == null || bp.getTime() == null ? "" : RFC3339_UTC.format(bp.getTime());
        return orUnknown(firstNonEmpty(buildTimeOverride, fromBuild));
    }

    /** 对照 Go 的 runtime.Version()（golden 的 go_version 形态）；Java 输出 JVM 版本。 */
    public String getJavaVersion() {
        return orUnknown(firstNonEmpty(javaVersionOverride, System.getProperty("java.version")));
    }

    private String buildInfoVersion() {
        BuildProperties bp = buildProperties.getIfAvailable();
        return bp == null ? "" : bp.getVersion();
    }

    private String buildInfo(String key) {
        BuildProperties bp = buildProperties.getIfAvailable();
        if (bp == null) {
            return "";
        }
        String v = bp.get(key);
        return v == null ? "" : v;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String orUnknown(String v) {
        return v == null || v.isEmpty() ? "unknown" : v;
    }

    /**
     * 对照 supportsRetrieverType + getKeywordIndexEngine / getVectorStoreEngine：
     * RETRIEVE_DRIVER 逗号拆分 → 按映射表过滤该能力 → ", " 连接；空 → "未配置"。
     */
    public String keywordIndexEngine() {
        return engineList(true);
    }

    public String vectorStoreEngine() {
        return engineList(false);
    }

    private String engineList(boolean keyword) {
        String retrieveDriver = driverProperties.driver();
        if (retrieveDriver == null || retrieveDriver.isEmpty()) {
            return "未配置";
        }
        List<String> capable = new ArrayList<>();
        for (String driver : retrieveDriver.split(",")) {
            String d = driver.trim();
            if (supportsRetrieverType(d, keyword)) {
                capable.add(d);
            }
        }
        return capable.isEmpty() ? "未配置" : String.join(", ", capable);
    }

    /**
     * 对照 retrieverEngineMapping（types/tenant.go L17-57）：
     * postgres/qdrant/milvus/weaviate/doris/sqlite/tencent_vectordb/opensearch 双能力，
     * elasticsearch_v7 仅 keywords、elasticsearch_v8 双能力。
     */
    static boolean supportsRetrieverType(String driver, boolean keyword) {
        Set<String> vectorCapable = Set.of("postgres", "elasticsearch_v8", "qdrant", "milvus",
                "weaviate", "doris", "sqlite", "tencent_vectordb", "opensearch");
        if (keyword) {
            return vectorCapable.contains(driver) || "elasticsearch_v7".equals(driver);
        }
        return vectorCapable.contains(driver);
    }

    /**
     * 对照 {@code getGraphDatabaseEngine}（system.go L610-616）：Go 看
     * {@code h.neo4jDriver == nil}；Java 同口径——看仓储的**真实驱动**是否已建
     * （NEO4J_ENABLE=true 且连上才是 Neo4j；配了但没连上属于启动失败，不会走到这里）。
     */
    public String graphDatabaseEngine() {
        if (graphRepository instanceof com.ragagent.retrieval.graph.Neo4jGraphRepository repo
                && repo.enabled()) {
            return "Neo4j";
        }
        return "Not Enabled";
    }

    /** 对照 isMinioEnvAvailable。 */
    public boolean isMinioEnvAvailable() {
        return env("MINIO_ENDPOINT") && env("MINIO_ACCESS_KEY_ID") && env("MINIO_SECRET_ACCESS_KEY");
    }

    private static boolean env(String name) {
        String v = System.getenv(name);
        return v != null && !v.isEmpty();
    }

    /**
     * 对照 database.CachedMigrationVersion：同一 dev 库上 Java 侧迁移由 Flyway 执行，
     * 版本号同源。H2 测试库无 flyway_schema_history → 空串（键省略）。
     */
    public String dbVersion() {
        try (Connection cn = DataSourceUtils.doGetConnection(dataSource)) {
            try (var ps = cn.prepareStatement(
                    "SELECT COALESCE(MAX(CAST(version AS INTEGER)), 0) FROM flyway_schema_history WHERE success = TRUE");
                 var rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return String.valueOf(rs.getInt(1));
                }
            }
        } catch (RuntimeException | java.sql.SQLException e) {
            // H2 测试库没有该表 → 空串（键省略）
        }
        return "";
    }

    /** 对照 runtime.ServerStartedAt 的 RFC3339(UTC) 形态（/info 的 started_at）。 */
    public String startedAt() {
        long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        return RFC3339_UTC.format(Instant.ofEpochMilli(start));
    }

    /** 对照 runtime.ServerUptime().Seconds() 截断。 */
    public long uptimeSeconds() {
        long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        return Duration.between(Instant.ofEpochMilli(start), Instant.now()).toSeconds();
    }

    /**
     * 对照 activeBackendProviders：status=active 的多实例后端 provider 集合
     * （小写去空白；查询失败回落空集合走 legacy 检查，Go 同为 best-effort）。
     */
    public Map<String, Boolean> activeBackendProviders(long tenantId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (tenantId <= 0) {
            return result;
        }
        try {
            for (StorageBackend backend : backendRepository.list(tenantId)) {
                if (backend == null || !"active".equals(backend.getStatus())) {
                    continue;
                }
                String provider = backend.getProvider() == null ? "" : backend.getProvider();
                result.put(provider.toLowerCase().trim(), true);
            }
        } catch (RuntimeException e) {
            // best-effort：查询失败回落 legacy 配置检查（Go 同样记 WARN + 空集合）
        }
        return result;
    }

    public StorageAllowList allowList() {
        return allowList;
    }
}
