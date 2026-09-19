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

import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.storage.StorageAllowList;
import com.ragagent.storage.mapper.StorageBackendRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

/**
 * /system 组读端点的计算逻辑（对照 Go internal/handler/system.go 的
 * GetSystemInfo / GetStorageEngineStatus 与 deployment_capabilities.go 的快照装配）。
 *
 * <p><b>已知差异（部署状态语义，非降级）</b>：</p>
 * <ul>
 *   <li>version/commit_id/build_time/go_version：Go 用 ldflags 注入（dev 恒 "unknown"）；
 *       Java 侧同样取常量 "unknown"（golden 钉住的就是 dev 部署的形态）。</li>
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

    /** Java 侧版本常量（Go ldflags 注入的 dev 形态是 "unknown"/"standard"）。 */
    @Value("${weknora.system.version:unknown}")
    private String version;
    @Value("${weknora.system.edition:standard}")
    private String edition;
    @Value("${weknora.system.commit-id:unknown}")
    private String commitId;
    @Value("${weknora.system.build-time:unknown}")
    private String buildTime;
    @Value("${weknora.system.go-version:unknown}")
    private String goVersion;

    public SystemInfoService(StorageAllowList allowList,
                             StorageBackendRepository backendRepository,
                             DataSource dataSource) {
        this.allowList = allowList;
        this.backendRepository = backendRepository;
        this.dataSource = dataSource;
    }

    public String getVersion() { return version; }
    public String getEdition() { return edition; }
    public String getCommitId() { return commitId; }
    public String getBuildTime() { return buildTime; }
    public String getGoVersion() { return goVersion; }

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
        String retrieveDriver = System.getenv("RETRIEVE_DRIVER");
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

    /** 对照 getGraphDatabaseEngine：未启用 → "Not Enabled"，启用 → "Neo4j"。 */
    public String graphDatabaseEngine() {
        String enable = System.getenv("NEO4J_ENABLE");
        if (enable == null || !"true".equalsIgnoreCase(enable)) {
            return "Not Enabled";
        }
        return "Neo4j";
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
