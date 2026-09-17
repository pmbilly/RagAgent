package com.ragagent.mcp.mapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import com.ragagent.mcp.domain.McpMetadata;
import com.ragagent.mcp.domain.McpMetadataSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 目录快照仓储（对照 Go internal/application/repository/mcp_metadata.go 中挂在
 * mcpServiceRepository 上的三个方法）。
 *
 * <p>方言分叉对应 Go 的 {@code metadataToolCountExpr(db)}：只有 PG 才有
 * {@code jsonb_array_length}，其它库用 {@code json_array_length}。
 * 方言在启动时从 DataSource 探测一次（等价 Go 每次调用 {@code db.Name()} 的结果，
 * 同一进程内不会变）。</p>
 *
 * <p>保存语义（对照 SaveMetadata）：<b>陈旧快照不得覆盖新快照</b>。
 * Go 用 {@code ON CONFLICT ... WHERE synced_at <= excluded.synced_at}，
 * Java 用 UPDATE-带版本护栏 → 未命中再 INSERT → 唯一键冲突回落 UPDATE。</p>
 */
@Component
public class McpMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(McpMetadataRepository.class);

    private final McpMetadataMapper mapper;
    private final boolean postgres;

    public McpMetadataRepository(McpMetadataMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.postgres = detectPostgres(dataSource);
    }

    /** 供测试断言方言探测；对照 Go db.Name() == "postgres" */
    public boolean isPostgres() {
        return postgres;
    }

    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            boolean pg = product != null && product.toLowerCase().contains("postgres");
            log.debug("mcp metadata dialect resolved: product={} postgres={}", product, pg);
            return pg;
        } catch (SQLException e) {
            log.warn("mcp metadata dialect detection failed, falling back to non-postgres: {}",
                    e.toString());
            return false;
        }
    }

    /** 对照 GetMetadata：不存在返回 null */
    public McpMetadata getMetadata(long tenant, String service, String principal) {
        return mapper.getMetadata(tenant, service, principal == null ? "" : principal);
    }

    /**
     * 对照 ListMetadataSummaries：principals 为空返回空列表
     * （Go 返回 (nil, nil)，上层按空处理）。
     */
    public List<McpMetadataSummary> listMetadataSummaries(long tenant, List<String> principals) {
        if (principals == null || principals.isEmpty()) {
            return List.of();
        }
        return postgres
                ? mapper.listSummariesPostgres(tenant, principals)
                : mapper.listSummariesDefault(tenant, principals);
    }

    /** 对照 SaveMetadata：upsert + 版本护栏；陈旧写入静默丢弃（不报错） */
    public void saveMetadata(McpMetadata snapshot) {
        if (snapshot.getPrincipal() == null) {
            snapshot.setPrincipal("");
        }
        if (mapper.updateIfNotOlder(snapshot) > 0) {
            return;
        }
        // 未命中：要么行不存在（应插入），要么行更新（陈旧快照，必须丢弃）
        if (mapper.countByKey(snapshot.getTenantId(), snapshot.getServiceId(), snapshot.getPrincipal()) > 0) {
            return;
        }
        try {
            mapper.insertSnapshot(snapshot);
        } catch (DataIntegrityViolationException e) {
            // 并发插入：输家重试带护栏的 UPDATE，仍可能因版本较旧而放弃
            mapper.updateIfNotOlder(snapshot);
        }
    }
}
