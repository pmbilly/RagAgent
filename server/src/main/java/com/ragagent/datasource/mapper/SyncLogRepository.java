package com.ragagent.datasource.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import com.ragagent.datasource.domain.SyncLog;
import org.springframework.stereotype.Component;

/**
 * 同步日志的存储契约。
 *
 * <h2>存储语义</h2>
 * <ol>
 *   <li><b>错误文案逐字稳定</b>：{@code "sync log is nil"} / {@code "id is empty"} /
 *       {@code "data source id is empty"} / {@code "sync log id is empty"} /
 *       {@code "sync log not found"}。</li>
 *   <li><b>⚠️ {@link #findLatest} 查不到时回 {@code null} 而不是抛错</b>：
 *       与同文件其它读方法（{@link #findById} 上抛）**不同**。别统一。</li>
 *   <li><b>分页钳制在仓储层</b>：{@code limit <= 0 → 10}、{@code offset < 0 → 0}。</li>
 *   <li><b>两处 {@code updated_at} 的列集恰好差一列</b>：
 *       {@link #cancelPendingByDataSource} 路径显式补上它；
 *       {@link #updateResult} 里显式给出。两处都别多列也别漏列。</li>
 *   <li><b>物理删</b>：{@code sync_logs} 没有 {@code deleted_at}，
 *       {@link #cleanupOldLogs} 是全局 DELETE。</li>
 * </ol>
 */
@Component
public class SyncLogRepository {

    private static final String PG_JSON = "typeHandler=" + PgJsonTypeHandler.class.getName();

    private final SyncLogMapper mapper;

    public SyncLogRepository(SyncLogMapper mapper) {
        this.mapper = mapper;
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 插入一条同步日志。
     *
     * <p>⚠️ {@code started_at} 用 {@code now(UTC)}（**不是本地时间**）——
     * 别换成 {@code OffsetDateTime.now()} 的本地偏移。</p>
     *
     * <p>{@code created_at}/{@code updated_at} 零值才补 now。</p>
     */
    public void create(SyncLog log) {
        if (log == null) {
            throw new DataSourceException("sync log is nil");
        }
        if (log.getId().isEmpty()) {
            log.setId(UUID.randomUUID().toString());
        }
        if (GoTimeSerializer.isGoZero(log.getStartedAt())) {
            log.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (GoTimeSerializer.isGoZero(log.getCreatedAt())) {
            log.setCreatedAt(now);
        }
        if (GoTimeSerializer.isGoZero(log.getUpdatedAt())) {
            log.setUpdatedAt(now);
        }
        mapper.insert(log);
    }

    /**
     * 按实体更新：**跳过零值**，但 {@code updated_at} 无条件刷成 now。
     *
     * <p>要写"清空 error_message"这种零值，得用 {@link #updateResult}（显式列集）。</p>
     */
    public void update(SyncLog log) {
        if (log == null) {
            throw new DataSourceException("sync log is nil");
        }
        if (log.getId().isEmpty()) {
            throw new DataSourceException("sync log id is empty");
        }
        UpdateWrapper<SyncLog> w = new UpdateWrapper<SyncLog>().eq("id", log.getId());
        // AutoUpdateTime：无条件覆盖
        w.set("updated_at", OffsetDateTime.now());

        if (!GoTimeSerializer.isGoZero(log.getCreatedAt())) {
            w.set("created_at", log.getCreatedAt());
        }
        if (nonEmpty(log.getDataSourceId())) {
            w.set("data_source_id", log.getDataSourceId());
        }
        if (log.getTenantId() != null && log.getTenantId() != 0L) {
            w.set("tenant_id", log.getTenantId());
        }
        if (nonEmpty(log.getStatus())) {
            w.set("status", log.getStatus());
        }
        if (!GoTimeSerializer.isGoZero(log.getStartedAt())) {
            w.set("started_at", log.getStartedAt());
        }
        if (log.getFinishedAt() != null) {
            w.set("finished_at", log.getFinishedAt());
        }
        if (log.getItemsTotal() != 0) {
            w.set("items_total", log.getItemsTotal());
        }
        if (log.getItemsCreated() != 0) {
            w.set("items_created", log.getItemsCreated());
        }
        if (log.getItemsUpdated() != 0) {
            w.set("items_updated", log.getItemsUpdated());
        }
        if (log.getItemsDeleted() != 0) {
            w.set("items_deleted", log.getItemsDeleted());
        }
        if (log.getItemsSkipped() != 0) {
            w.set("items_skipped", log.getItemsSkipped());
        }
        if (log.getItemsFailed() != 0) {
            w.set("items_failed", log.getItemsFailed());
        }
        if (nonEmpty(log.getErrorMessage())) {
            w.set("error_message", log.getErrorMessage());
        }
        if (log.getResult() != null) {
            w.set("result", log.getResult(), PG_JSON);
        }
        mapper.update(null, w);
    }

    /**
     * 用**显式列集**只写同步执行产出的列，
     * 好让后续同步成功时真的能把 {@code error_message} 写空。
     *
     * <p>注意这里有 {@code updated_at}，
     * 而 {@link #cancelPendingByDataSource} 那边由 mapper 显式补。</p>
     */
    public void updateResult(SyncLog log) {
        if (log == null) {
            throw new DataSourceException("sync log is nil");
        }
        if (log.getId().isEmpty()) {
            throw new DataSourceException("sync log id is empty");
        }
        UpdateWrapper<SyncLog> w = new UpdateWrapper<SyncLog>().eq("id", log.getId());
        w.set("status", log.getStatus());
        w.set("finished_at", log.getFinishedAt());
        w.set("items_total", log.getItemsTotal());
        w.set("items_created", log.getItemsCreated());
        w.set("items_updated", log.getItemsUpdated());
        w.set("items_deleted", log.getItemsDeleted());
        w.set("items_skipped", log.getItemsSkipped());
        w.set("items_failed", log.getItemsFailed());
        w.set("error_message", log.getErrorMessage());
        if (log.getResult() != null) {
            w.set("result", log.getResult(), PG_JSON);
        }
        w.set("updated_at", OffsetDateTime.now());
        mapper.update(null, w);
    }

    /**
     * 把某个数据源所有非终态的同步日志
     * 标成 canceled（删数据源时用）。
     *
     * <p>线上写的是四列（含 {@code updated_at}），且 {@code finished_at} 与
     * {@code updated_at} 是**同一个 {@code now}**。</p>
     *
     * <p>非终态的判据是 {@code status IN ('running', 'pending')}——{@code "pending"}
     * 没有出现在任何常量里（{@link DataSourceConstants} 里已注明），保持内联字面量。</p>
     */
    public void cancelPendingByDataSource(String dsId) {
        if (dsId == null || dsId.isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        mapper.cancelPending(dsId,
                DataSourceConstants.SYNC_LOG_STATUS_CANCELED,
                DataSourceConstants.SYNC_LOG_STATUS_RUNNING,
                "pending",
                "data source deleted",
                now);
    }

    /**
     * 删掉早于保留期的同步日志。
     *
     * <p>{@code retentionDays <= 0} 回落到 30。界时刻在 Java 侧算好后传参
     * （{@code started_at < NOW() - INTERVAL ? DAY} 的 PG 写法 H2 不认；语义等价，
     * {@code NOW()} 与"调用时刻"的差别在微秒级）。</p>
     */
    public void cleanupOldLogs(int retentionDays) {
        int days = retentionDays <= 0
                ? DataSourceConstants.FALLBACK_RETENTION_DAYS : retentionDays;
        mapper.deleteStartedBefore(OffsetDateTime.now().minusDays(days));
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 按 id 取单条同步日志。
     *
     * @throws DataSourceException         id 为空
     * @throws DataSourceException.NotFoundException 未命中
     */
    public SyncLog findById(String id) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException("id is empty");
        }
        SyncLog log = mapper.selectByIdOrNull(id);
        if (log == null) {
            throw new DataSourceException.NotFoundException("sync log not found");
        }
        return log;
    }

    /**
     * 某个数据源的同步历史，{@code started_at DESC}。
     *
     * <p>无行时回**空列表**。</p>
     */
    public List<SyncLog> findByDataSource(String dsId, int limit, int offset) {
        if (dsId == null || dsId.isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        int effectiveLimit = limit <= 0 ? DataSourceConstants.DEFAULT_SYNC_LOG_PAGE_SIZE : limit;
        int effectiveOffset = offset < 0 ? 0 : offset;
        List<SyncLog> rows = mapper.selectByDataSource(dsId, effectiveLimit, effectiveOffset);
        return rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 最近一条同步日志。
     *
     * <p>⚠️ <b>未命中回 {@code null}（不是错误）</b>。
     * 调用方（UI 的"最后同步状态"）把 {@code null} 当成"从没同步过"。</p>
     */
    public SyncLog findLatest(String dsId) {
        if (dsId == null || dsId.isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        return mapper.selectLatest(dsId);
    }

    /**
     * 防止同一次同步被并发跑两遍。
     *
     * @return 是否存在 running 状态的日志
     */
    public boolean hasRunningSync(String dsId) {
        if (dsId == null || dsId.isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        return mapper.countByStatus(dsId, DataSourceConstants.SYNC_LOG_STATUS_RUNNING) > 0;
    }

    /** 零值判定：string 的非零就是非空。 */
    private static boolean nonEmpty(String v) {
        return v != null && !v.isEmpty();
    }
}
