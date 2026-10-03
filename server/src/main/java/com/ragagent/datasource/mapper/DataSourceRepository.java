package com.ragagent.datasource.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ZeroTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import org.springframework.stereotype.Component;

/**
 * 数据源的存储契约。
 *
 * <h2>存储语义（读代码前先看这几条）</h2>
 * <ol>
 *   <li><b>入参校验的错误文案逐字稳定</b>：{@code "data source is nil"} /
 *       {@code "id is empty"} / {@code "data source id is empty"} /
 *       {@code "knowledge base id is empty"} / {@code "data source not found"}。
 *       它们不直接上线（handler 另外映射成 404），但保持一致便于对照排查。</li>
 *   <li><b>"查不到"是错误而不是 {@code null}</b>：唯一的单行读方法 {@link #findById}
 *       未命中时抛 {@link DataSourceException.NotFoundException}。
 *       这与 memory 仓储"查不到回 null"的约定**相反**，别混。</li>
 *   <li><b>列表方法的空结果是 {@code []} 而不是 {@code null}</b>。</li>
 *   <li><b>两个列表都按 {@code created_at DESC}</b>。</li>
 *   <li><b>软删除</b>：所有读写都带 {@code deleted_at IS NULL}；
 *       {@link #delete} 是 {@code UPDATE … SET deleted_at = now}。</li>
 *   <li><b>CREATE 时的列默认值替换</b>：
 *       带 DDL 默认值的列在插入时若为零值，
 *       用默认值**替换并回写实体**——本表是
 *       {@code sync_mode}/{@code status}/{@code conflict_strategy}/{@code sync_log_retention_days}。
 *       见 {@link #applyInsertDefaults}。</li>
 *   <li><b>自动时间戳</b>：CREATE 时 {@code created_at} 与 {@code updated_at}
 *       **零值才补 now**。</li>
 *   <li><b>按实体整体更新跳过零值</b>：string {@code ""}、数值 {@code 0}、
 *       bool {@code false}、指针 null、列表 null 一律不进 SET；
 *       {@code updated_at} 例外——**无条件覆盖成 now**。</li>
 * </ol>
 */
@Component
public class DataSourceRepository {

    private static final String PG_JSON = "typeHandler=" + PgJsonTypeHandler.class.getName();

    private final DataSourceMapper mapper;
    private final DataSourceTxTemplate tx;

    public DataSourceRepository(DataSourceMapper mapper, DataSourceTxTemplate tx) {
        this.mapper = mapper;
        this.tx = tx;
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /**
     * 插入一个数据源。
     *
     * <h2>⚠️ {@code sync_deletions} 的"三步舞"在这里退化成一步</h2>
     * <p>历史实现是：插入（列默认值把非指针 bool 的 {@code false} 替成 {@code true}，
     * **同时写库和写内存**）→ 再单列更新回原值 → 最后把内存改回来。
     * 三步连起来的净效果是<b>落库值与内存值都等于调用方给的原始值</b>
     * （连 {@code updated_at} 也一样：单列更新不刷时间戳）。
     * 所以这里**直接插调用方的值**，少一次 UPDATE；这不是简化语义，
     * 而是把那段绕路的目的（"让用户选的 false 活下来"）用最直接的方式达成。</p>
     *
     * <p>{@code id} 为空时在这里生成 UUID。</p>
     */
    public void create(DataSource ds) {
        if (ds == null) {
            throw new DataSourceException("data source is nil");
        }
        // 生成主键
        if (ds.getId().isEmpty()) {
            ds.setId(UUID.randomUUID().toString());
        }
        applyInsertDefaults(ds);
        stampForCreate(ds);
        tx.inTransaction(() -> mapper.insert(ds));
    }

    /**
     * 更新：按实体零值规则生成列集之后，
     * 再无条件把 {@code sync_deletions} 写成调用方的值。
     *
     * <p>第二步是刻意的：按实体更新会跳过零值，所以用户想关掉
     * {@code sync_deletions} 时那一次更新根本不会出现在 SET 里。
     * 这与 {@code updateSyncState} 用显式列集绕开零值是同一个问题的两种解法，
     * **不要合并这两个方法**：{@code updateSyncState} 只写同步执行产出的几列，
     * 而本方法写的是用户可编辑的全部字段。</p>
     */
    public void update(DataSource ds) {
        if (ds == null) {
            throw new DataSourceException("data source is nil");
        }
        if (ds.getId().isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        tx.inTransaction(() -> {
            UpdateWrapper<DataSource> w = new UpdateWrapper<DataSource>()
                    .eq("id", ds.getId())
                    .isNull("deleted_at");

            // 按实体更新对 updated_at 是**无条件覆盖**，
            // 它在 SET 里的位置与零值规则无关。
            //
            // ⚠️ 这里算一次、同时写进 SET 与**内存对象**——历史实现会把新值
            // 回写到调用方传入的实体上，所以 PUT 的响应里 updated_at 是本次更新时间
            //（而 created_at 因为零值被跳过、保持零值，golden ds-update.json 钉住）。
            // Java 的 wrapper 不回写实体，少了这一步 PUT 响应会变成
            // `"updated_at":"0001-01-01T00:00:00Z"`。
            OffsetDateTime updatedAt = OffsetDateTime.now();
            w.set("updated_at", updatedAt);
            ds.setUpdatedAt(updatedAt);

            // created_at 走普通的零值规则：非零才进 SET。
            // 加载出来的 ds 一定带着原值，所以线上会多写一次同值列。
            if (!ZeroTimeSerializer.isZeroValue(ds.getCreatedAt())) {
                w.set("created_at", ds.getCreatedAt());
            }
            // deleted_at 非零时会被写进 SET；
            // 正常路径上它是 null（查询已滤掉已删行），保留判断只为逐条对齐。
            if (ds.getDeletedAt() != null) {
                w.set("deleted_at", ds.getDeletedAt());
            }

            if (ds.getTenantId() != null && ds.getTenantId() != 0L) {
                w.set("tenant_id", ds.getTenantId());
            }
            if (nonEmpty(ds.getKnowledgeBaseId())) {
                w.set("knowledge_base_id", ds.getKnowledgeBaseId());
            }
            if (nonEmpty(ds.getName())) {
                w.set("name", ds.getName());
            }
            if (nonEmpty(ds.getType())) {
                w.set("type", ds.getType());
            }
            setJson(w, "config", ds.getConfig());
            if (nonEmpty(ds.getSyncSchedule())) {
                w.set("sync_schedule", ds.getSyncSchedule());
            }
            if (nonEmpty(ds.getSyncMode())) {
                w.set("sync_mode", ds.getSyncMode());
            }
            if (nonEmpty(ds.getStatus())) {
                w.set("status", ds.getStatus());
            }
            if (nonEmpty(ds.getConflictStrategy())) {
                w.set("conflict_strategy", ds.getConflictStrategy());
            }
            if (ds.getLastSyncAt() != null) {
                w.set("last_sync_at", ds.getLastSyncAt());
            }
            setJson(w, "last_sync_cursor", ds.getLastSyncCursor());
            setJson(w, "last_sync_result", ds.getLastSyncResult());
            // ⚠️ error_message 是零值跳过的**最大受害者**：用本方法清空错误消息是做不到的
            // （"把 error_message 改成空串"在这种调用下不生效，session/message 同款）。
            // 清空要走 updateSyncState（它用显式列集）。
            if (nonEmpty(ds.getErrorMessage())) {
                w.set("error_message", ds.getErrorMessage());
            }
            if (ds.getSyncLogRetentionDays() != 0) {
                w.set("sync_log_retention_days", ds.getSyncLogRetentionDays());
            }

            mapper.update(null, w);

            // 第二步：无条件写 sync_deletions（绕开零值跳过）。
            mapper.updateSyncDeletions(ds.getId(), ds.isSyncDeletions());
        });
    }

    /**
     * 只写同步执行产出的几列。
     *
     * <p>用显式列集绕开零值省略，好让"清空 error_message"真的落库
     * （{@code updated_at} 也由调用方一并给出），
     * 所以这里**没有**零值判断——这正是它存在的理由。</p>
     *
     * <p>⚠️ 这个方法的存在本身是一条契约：如果把它实现成 {@link #update} 那样，
     * "同步成功后清掉上次的错误"就会静默失效。</p>
     */
    public void updateSyncState(DataSource ds) {
        if (ds == null) {
            throw new DataSourceException("data source is nil");
        }
        if (ds.getId().isEmpty()) {
            throw new DataSourceException("data source id is empty");
        }
        tx.inTransaction(() -> {
            UpdateWrapper<DataSource> w = new UpdateWrapper<DataSource>()
                    .eq("id", ds.getId())
                    .isNull("deleted_at");
            w.set("status", ds.getStatus());
            w.set("last_sync_at", ds.getLastSyncAt());
            setJson(w, "last_sync_cursor", ds.getLastSyncCursor());
            setJson(w, "last_sync_result", ds.getLastSyncResult());
            w.set("error_message", ds.getErrorMessage());
            w.set("updated_at", OffsetDateTime.now());
            mapper.update(null, w);
        });
    }

    /**
     * 软删。
     *
     * <p>不存在的 id 是**空操作**（{@code rowsAffected = 0}）而不是错误——
     * 删 0 行不算错。</p>
     */
    public void delete(String id) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException("id is empty");
        }
        mapper.softDeleteById(id, OffsetDateTime.now());
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 按 id 取单个数据源。
     *
     * @throws DataSourceException         id 为空
     * @throws DataSourceException.NotFoundException 未命中
     */
    public DataSource findById(String id) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException("id is empty");
        }
        DataSource ds = mapper.selectByIdOrNull(id);
        if (ds == null) {
            throw new DataSourceException.NotFoundException("data source not found");
        }
        return ds;
    }

    /**
     * 某个知识库下的全部数据源，{@code created_at DESC}。
     *
     * <p>无行时回**空列表**。</p>
     */
    public List<DataSource> findByKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new DataSourceException("knowledge base id is empty");
        }
        List<DataSource> rows = mapper.selectByKnowledgeBase(kbId);
        return rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 调度器用的"活着且有 cron 表达式"的数据源。
     *
     * <p>无行时回**空列表**。注意它**不按租户过滤**——这是刻意的：调度器跨租户跑。</p>
     */
    public List<DataSource> findActive() {
        List<DataSource> rows = mapper.selectActive(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        return rows == null ? new ArrayList<>() : rows;
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /**
     * 插入前把**带 DDL 默认值的零值列**用默认值填入，**并回写实体**。
     *
     * <p>⚠️ {@code sync_deletions} **刻意不在这里**：它的默认值是 {@code true}，
     * 但历史实现的净效果是"调用方的原值"（见 {@link #create}）。
     * 在这里把它改成 true 反而会与最终落库状态分叉。</p>
     */
    private static void applyInsertDefaults(DataSource ds) {
        if (ds.getSyncMode().isEmpty()) {
            ds.setSyncMode(DataSourceConstants.DEFAULT_SYNC_MODE);
        }
        if (ds.getStatus().isEmpty()) {
            ds.setStatus(DataSourceConstants.DEFAULT_STATUS);
        }
        if (ds.getConflictStrategy().isEmpty()) {
            ds.setConflictStrategy(DataSourceConstants.DEFAULT_CONFLICT_STRATEGY);
        }
        if (ds.getSyncLogRetentionDays() == 0) {
            ds.setSyncLogRetentionDays(DataSourceConstants.DEFAULT_SYNC_LOG_RETENTION_DAYS);
        }
    }

    /**
     * 插入前的时间戳规则：{@code created_at} 与 {@code updated_at}
     * **各自零值才补 {@code now}**。
     *
     * <p>注意与按实体更新的差别：那边 {@code updated_at} 是**无条件覆盖**。</p>
     */
    private static void stampForCreate(DataSource ds) {
        OffsetDateTime now = OffsetDateTime.now();
        if (ZeroTimeSerializer.isZeroValue(ds.getCreatedAt())) {
            ds.setCreatedAt(now);
        }
        if (ZeroTimeSerializer.isZeroValue(ds.getUpdatedAt())) {
            ds.setUpdatedAt(now);
        }
    }

    /** 零值判定：string 的非零就是非空。 */
    private static boolean nonEmpty(String v) {
        return v != null && !v.isEmpty();
    }

    /**
     * 只写非 null 的 jsonb 列。
     *
     * <p>⚠️ 必须用 3 参 {@code set(column, value, mapping)}：MyBatis-Plus 的
     * {@code UpdateWrapper.set()} <b>不套用实体上的 {@code @TableField(typeHandler=…)}</b>，
     * 会退化成 Java 序列化，落库时报
     * {@code Data conversion error converting "CAST(X'aced0005...)"}。</p>
     */
    private static void setJson(UpdateWrapper<DataSource> w, String column, JsonNode value) {
        if (value == null) {
            return;
        }
        w.set(column, value, PG_JSON);
    }
}
