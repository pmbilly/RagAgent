package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.ZeroTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次同步任务的执行记录（表 {@code sync_logs}）。
 *
 * <p><b>这是响应体</b>：{@code GET /datasources/:id/sync-logs} 与
 * {@code GET /datasources/sync-logs/:log_id} 都直接返回裸实体，没有信封。</p>
 *
 * <h2>JSON 形状（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   new SyncLog() →
 *   {"id":"","dataSourceId":"","tenantId":0,"status":"","startedAt":"0001-01-01T00:00:00Z",
 *    "finishedAt":null,"itemsTotal":0,"itemsCreated":0,"itemsUpdated":0,"itemsDeleted":0,
 *    "itemsSkipped":0,"itemsFailed":0,"errorMessage":"","result":null,
 *    "createdAt":"0001-01-01T00:00:00Z","updatedAt":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p><b>键名＝字段名，所有键恒输出</b>——{@code errorMessage} 空串照常输出、
 * {@code finished_at} 为 null 时输出 {@code null}、{@code result} 空时输出 {@code null}。</p>
 *
 * <h2>落库行为清单</h2>
 * <ol>
 *   <li><b>插入钩子</b>：id 为空时生成 UUID；{@code started_at} 为零值时补
 *       {@code now(UTC)}——都在 {@link com.ragagent.datasource.mapper.SyncLogMapper} 的
 *       创建路径里显式执行（见 {@code SyncLogRepository.create}）。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：<b>没有</b> {@code deleted_at} 列——本表只有物理删
 *       （{@code CleanupOldLogs}）。别给它加 {@code @TableLogic}。</li>
 *   <li><b>默认排序</b>：仓库层显式写，共两处：
 *       {@code started_at DESC}（{@code FindByDataSource}，带 limit/offset）与
 *       {@code started_at DESC, id ASC}（{@code FindLatest}——后者用
 *       {@code id} 破平局，只在 {@code started_at} 完全并列时生效）。
 *       {@code FindByID} 是 {@code ORDER BY id}（主键唯一，等于无排序）。</li>
 *   <li><b>唯一索引/外键</b>：{@code data_source_id} 在迁移里对
 *       {@code data_sources(id)} 有 {@code ON DELETE CASCADE} 外键——
 *       H2 测试库里**不建**该约束（与既有表一致），由 service 层保证引用有效。</li>
 *   <li><b>自动时间戳</b>：{@code created_at} 与
 *       {@code updated_at} 在 CREATE 时**零值才补 now**；
 *       按实体整体更新时 {@code updated_at} **无条件覆盖成 now**。
 *       注意 {@code started_at} **不是**自动时间戳，
 *       它的 DDL {@code DEFAULT CURRENT_TIMESTAMP} 只是兜底，创建钩子会显式填它。</li>
 *   <li><b>jsonb 列</b>：{@code result} 用 {@link PgJsonTypeHandler} 映射成
 *       {@link JsonNode}（原样存取的 JSON 文本）。{@code sync_logs.result} 在迁移里
 *       **没有 DEFAULT**——所以 MyBatis-Plus 对 null 字段省略该列恰好落到 SQL NULL，
 *       与"显式写 NULL"语义一致，不需要 {@code FieldStrategy.ALWAYS}。</li>
 * </ol>
 */
@TableName(value = "sync_logs", autoResultMap = true)
public class SyncLog {

    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";

    /** 指向 {@code data_sources.id}（迁移里有 ON DELETE CASCADE 外键）。 */
    @TableField("data_source_id")
    private String dataSourceId = "";

    @TableField("tenant_id")
    private Long tenantId = 0L;

    /** running / success / partial / failed / canceled。 */
    @TableField("status")
    private String status = "";

    /** 同步开始时间。创建钩子在零值时补 {@code now(UTC)}。 */
    @TableField("started_at")
    private OffsetDateTime startedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    /** 同步完成时间。指针 → nil 输出 {@code null}。 */
    @TableField("finished_at")
    private OffsetDateTime finishedAt;

    @TableField("items_total")
    private int itemsTotal;

    @TableField("items_created")
    private int itemsCreated;

    @TableField("items_updated")
    private int itemsUpdated;

    @TableField("items_deleted")
    private int itemsDeleted;

    @TableField("items_skipped")
    private int itemsSkipped;

    @TableField("items_failed")
    private int itemsFailed;

    /** 失败时的错误详情。空串照常输出。 */
    @TableField("error_message")
    private String errorMessage = "";

    /** 详细的同步结果（JSON）。空时序列化成 {@code null}。 */
    @TableField(value = "result", typeHandler = PgJsonTypeHandler.class)
    private JsonNode result;

    @TableField("created_at")
    private OffsetDateTime createdAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    @TableField("updated_at")
    private OffsetDateTime updatedAt = ZeroTimeSerializer.ZERO_DATE_TIME;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public String getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(String v) { dataSourceId = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime v) {
        startedAt = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime v) { finishedAt = v; }

    public int getItemsTotal() { return itemsTotal; }
    public void setItemsTotal(int v) { itemsTotal = v; }

    public int getItemsCreated() { return itemsCreated; }
    public void setItemsCreated(int v) { itemsCreated = v; }

    public int getItemsUpdated() { return itemsUpdated; }
    public void setItemsUpdated(int v) { itemsUpdated = v; }

    public int getItemsDeleted() { return itemsDeleted; }
    public void setItemsDeleted(int v) { itemsDeleted = v; }

    public int getItemsSkipped() { return itemsSkipped; }
    public void setItemsSkipped(int v) { itemsSkipped = v; }

    public int getItemsFailed() { return itemsFailed; }
    public void setItemsFailed(int v) { itemsFailed = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v == null ? "" : v; }

    public JsonNode getResult() { return result; }
    public void setResult(JsonNode v) { result = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? ZeroTimeSerializer.ZERO_DATE_TIME : v;
    }

    /**
     * 解析 {@code result} 列。
     *
     * @return 列为 SQL NULL 时回 {@code null}；
     *         JSON 非法时抛 {@link DataSourceException}
     */
    public SyncResult parseResult() {
        try {
            return SyncResult.fromJson(result);
        } catch (RuntimeException e) {
            throw new DataSourceException("parse sync log result: " + e.getMessage(), e);
        }
    }
}
