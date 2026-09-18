package com.ragagent.datasource.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.DataSourceException;
import org.springframework.stereotype.Component;

/**
 * 数据源的存储契约（对照 Go {@code DataSourceRepository}，
 * internal/application/repository/datasource_repo.go L13-151）。
 *
 * <h2>逐条对齐的 Go 语义（读代码前先看这几条）</h2>
 * <ol>
 *   <li><b>入参校验的错误文案逐字照抄</b>：{@code "data source is nil"} /
 *       {@code "id is empty"} / {@code "data source id is empty"} /
 *       {@code "knowledge base id is empty"} / {@code "data source not found"}。
 *       它们不直接上线（handler 另外映射成 404），但保持一致便于对照排查。</li>
 *   <li><b>"查不到"是错误而不是 {@code null}</b>：唯一的读方法 {@link #findById}
 *       未命中时抛 {@link DataSourceException.NotFoundException}
 *       （Go 的 {@code errors.New("data source not found")}）。
 *       这与 memory 仓储"查不到回 null"的约定**相反**，别混。</li>
 *   <li><b>列表方法的空结果是 {@code []} 而不是 {@code null}</b>：GORM 的
 *       {@code Find(&slice)} 会把 nil 切片初始化成非 nil 空切片。</li>
 *   <li><b>两个列表都按 {@code created_at DESC}</b>（Go 的 {@code Order(...)}）。</li>
 *   <li><b>软删除</b>：所有读写都带 {@code deleted_at IS NULL}；
 *       {@link #delete} 是 {@code UPDATE … SET deleted_at = now}。</li>
 *   <li><b>GORM 的 CREATE 默认值替换</b>（{@code callbacks/create.go} L336-341）：
 *       带**字面量** {@code default:} tag 的字段在 CREATE 时若为零值，
 *       GORM 会用默认值**替换并回写结构体**——本表是
 *       {@code sync_mode}/{@code status}/{@code conflict_strategy}/{@code sync_log_retention_days}。
 *       见 {@link #applyInsertDefaults}。</li>
 *   <li><b>自动时间戳</b>：CREATE 时 {@code created_at} 与 {@code updated_at}
 *       **零值才补 now**（GORM 的 {@code ConvertToCreateValues} 只在 isZero 时替换，
 *       且 {@code update_track_time} 在普通 Create 里未设置）。</li>
 *   <li><b>{@code Updates(结构体)} 跳过零值</b>（§9）：string {@code ""}、数值 {@code 0}、
 *       bool {@code false}、指针 nil、切片 nil 一律不进 SET；
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
     * 对照 Go {@code Create}。
     *
     * <h2>⚠️ {@code sync_deletions} 的"三步舞"在 Java 侧退化成一步</h2>
     * <p>Go 的写法是：{@code tx.Create(ds)}（GORM 把 {@code default:true} 替换掉非指针
     * bool 的 {@code false}，**同时写库和写内存**）→ 再
     * {@code UpdateColumn("sync_deletions", 捕获的原值)} → 最后
     * {@code ds.SyncDeletions = 捕获的原值} 把内存改回来。</p>
     * <p>把这三步连起来看，<b>落库值与内存值都等于调用方给的原始值</b>——
     * 也就是说整个来回的净效果与"直接插原值"完全一致（连 {@code updated_at}
     * 也一样：{@code UpdateColumn} 带 {@code SkipHooks}，不刷时间戳）。
     * 所以 Java 侧**直接插调用方的值**，少一次 UPDATE；这不是简化语义，
     * 而是把 Go 那段绕路的目的（"让用户选的 false 活下来"）用最直接的方式达成。</p>
     *
     * <p>{@code id} 的 UUID 生成对照 Go 的 {@code BeforeCreate} 钩子。</p>
     */
    public void create(DataSource ds) {
        if (ds == null) {
            throw new DataSourceException("data source is nil");
        }
        // 对照 Go 的 BeforeCreate 钩子
        if (ds.getId().isEmpty()) {
            ds.setId(UUID.randomUUID().toString());
        }
        applyInsertDefaults(ds);
        stampForCreate(ds);
        tx.inTransaction(() -> mapper.insert(ds));
    }

    /**
     * 对照 Go {@code Update}：{@code tx.Model(ds).Updates(ds)} 之后，
     * 再无条件把 {@code sync_deletions} 写成调用方的值。
     *
     * <p>第二步是刻意的：{@code Updates(结构体)} 会跳过零值，所以用户想关掉
     * {@code sync_deletions} 时那一次更新根本不会出现在 SET 里。
     * 这与 {@code UpdateSyncState} 用 map 绕开零值是同一个问题的两种解法，
     * **不要合并这两个方法**：{@code UpdateSyncState} 只写同步执行产出的几列，
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

            // GORM 的 struct Updates 对 updated_at 是**无条件覆盖**（AutoUpdateTime），
            // 它在 SET 里的位置与零值规则无关。
            //
            // ⚠️ 这里算一次、同时写进 SET 与**内存对象**——GORM 的
            // `stmt.SetColumn("updated_at", curTime)` 是写进 `stmt.Dest`（就是调用方那个
            // `*types.DataSource`）的，所以 Go 的 `UpdateDataSource` 返回给 handler 的
            // 那个结构体上，`updated_at` 已经是 DB 里那个新值（实测：PUT 的响应里
            // updated_at 是本次更新时间，而 created_at 因为零值被跳过、仍是 Go 零值）。
            // Java 的 wrapper 不回写实体，少了这一步 PUT 响应会变成
            // `"updated_at":"0001-01-01T00:00:00Z"`（golden ds-update.json 钉住）。
            OffsetDateTime updatedAt = OffsetDateTime.now();
            w.set("updated_at", updatedAt);
            ds.setUpdatedAt(updatedAt);

            // created_at 是 AutoCreateTime（不是 AutoUpdateTime）→ 走普通的零值规则：
            // 非零才进 SET。加载出来的 ds 一定带着原值，所以线上会多写一次同值列。
            if (!GoTimeSerializer.isGoZero(ds.getCreatedAt())) {
                w.set("created_at", ds.getCreatedAt());
            }
            // deleted_at 是 gorm.DeletedAt：Valid 为 true 时非零 → 会被写进 SET。
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
            // （"把 error_message 改成空串"在这种调用下不生效，§9 的 session/message 同款）。
            // 清空要走 UpdateSyncState（它用显式 map）。
            if (nonEmpty(ds.getErrorMessage())) {
                w.set("error_message", ds.getErrorMessage());
            }
            if (ds.getSyncLogRetentionDays() != 0) {
                w.set("sync_log_retention_days", ds.getSyncLogRetentionDays());
            }

            mapper.update(null, w);

            // Go 的第二步：无条件写 sync_deletions（绕开零值跳过）。
            mapper.updateSyncDeletions(ds.getId(), ds.isSyncDeletions());
        });
    }

    /**
     * 对照 Go {@code UpdateSyncState}：只写同步执行产出的几列。
     *
     * <p>Go 用 {@code Updates(map)} 绕开零值省略，好让"清空 error_message"真的落库。
     * Java 侧同样是显式列集（{@code updated_at} 与 Go 的 map 一致，由调用方一并给出），
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
     * 对照 Go {@code Delete}：软删。
     *
     * <p>不存在的 id 是**空操作**（{@code rowsAffected = 0}）而不是错误——
     * Go 的 {@code Delete} 只看 {@code .Error}，删 0 行不算错。</p>
     */
    public void delete(String id) {
        if (id == null || id.isEmpty()) {
            throw new DataSourceException("id is empty");
        }
        mapper.softDeleteById(id, OffsetDateTime.now());
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code FindByID}。
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
     * 对照 Go {@code FindByKnowledgeBase}：某个知识库下的全部数据源，{@code created_at DESC}。
     *
     * <p>无行时回**空列表**（GORM 的 {@code Find} 会把 nil 切片初始化成非 nil 空切片）。</p>
     */
    public List<DataSource> findByKnowledgeBase(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            throw new DataSourceException("knowledge base id is empty");
        }
        List<DataSource> rows = mapper.selectByKnowledgeBase(kbId);
        return rows == null ? new ArrayList<>() : rows;
    }

    /**
     * 对照 Go {@code FindActive}：调度器用的"活着且有 cron 表达式"的数据源。
     *
     * <p>无行时回**空列表**。注意它**不按租户过滤**——这是刻意的：调度器跨租户跑。</p>
     */
    public List<DataSource> findActive() {
        List<DataSource> rows = mapper.selectActive(DataSourceConstants.DATA_SOURCE_STATUS_ACTIVE);
        return rows == null ? new ArrayList<>() : rows;
    }

    // ── 内部工具 ───────────────────────────────────────────────────────────

    /**
     * 对照 GORM 在 CREATE 时对**带字面量 default tag 的零值字段**做的替换
     * （{@code callbacks/create.go} L336-341）：用默认值填入，**并回写结构体**。
     *
     * <p>⚠️ {@code sync_deletions} **刻意不在这里**：它的默认值是 {@code true}，
     * 但 Go 的仓储在 {@code Create} 里紧接着用 {@code UpdateColumn} 把调用方的值写了回去，
     * 净效果就是"原值"。在这里把它改成 true 反而会与 Go 的最终状态分叉
     * ——见 {@link #create}。</p>
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
     * 对照 GORM 在 CREATE 时的时间戳规则：{@code created_at} 与 {@code updated_at}
     * **各自零值才补 {@code now}**（{@code callbacks/create.go} L336-343）。
     *
     * <p>注意与 {@code Updates(struct)} 的差别：那边 {@code updated_at} 是**无条件覆盖**。
     * 差别来自 GORM 只在 {@code update_track_time}（即 {@code Save} 路径）才会覆盖非零值。</p>
     */
    private static void stampForCreate(DataSource ds) {
        OffsetDateTime now = OffsetDateTime.now();
        if (GoTimeSerializer.isGoZero(ds.getCreatedAt())) {
            ds.setCreatedAt(now);
        }
        if (GoTimeSerializer.isGoZero(ds.getUpdatedAt())) {
            ds.setUpdatedAt(now);
        }
    }

    /** Go 的零值判定：string 的非零就是非空。 */
    private static boolean nonEmpty(String v) {
        return v != null && !v.isEmpty();
    }

    /**
     * 只写非 null 的 jsonb 列。
     *
     * <p>⚠️ 必须用 3 参 {@code set(column, value, mapping)}：MyBatis-Plus 的
     * {@code UpdateWrapper.set()} <b>不套用实体上的 {@code @TableField(typeHandler=…)}</b>，
     * 会退化成 Java 序列化，落库时报
     * {@code Data conversion error converting "CAST(X'aced0005...)"}（§9）。</p>
     */
    private static void setJson(UpdateWrapper<DataSource> w, String column, JsonNode value) {
        if (value == null) {
            return;
        }
        w.set(column, value, PG_JSON);
    }
}
