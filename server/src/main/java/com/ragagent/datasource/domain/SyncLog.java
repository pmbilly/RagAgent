package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次同步任务的执行记录（对照 Go {@code types.SyncLog}，
 * internal/types/datasource.go L144-209；表 {@code sync_logs}，
 * 迁移 {@code 000029_datasource_tables.up.sql}）。
 *
 * <p><b>这是响应体</b>：{@code GET /datasources/:id/sync-logs} 与
 * {@code GET /datasources/sync-logs/:log_id} 都是 {@code c.JSON(200, log)}
 * ——裸实体，没有信封。</p>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   SyncLog{} →
 *   {"id":"","data_source_id":"","tenant_id":0,"status":"","started_at":"0001-01-01T00:00:00Z",
 *    "finished_at":null,"items_total":0,"items_created":0,"items_updated":0,"items_deleted":0,
 *    "items_skipped":0,"items_failed":0,"error_message":"","result":null,
 *    "created_at":"0001-01-01T00:00:00Z","updated_at":"0001-01-01T00:00:00Z"}
 * </pre>
 * <p><b>一个 omitempty 都没有</b>——{@code error_message} 空串照常输出、
 * {@code finished_at} 为 nil 时输出 {@code null}、{@code result} 空时输出 {@code null}。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 {@code BeforeCreate}</b>：
 *       <pre>
 *         if s.ID == ""        { s.ID = uuid.New().String() }
 *         if s.StartedAt.IsZero() { s.StartedAt = time.Now().UTC() }
 *       </pre>
 *       Java 等效在 {@link com.ragagent.datasource.mapper.SyncLogMapper} 的
 *       {@code RepositoryCreate} 处显式执行（见 {@code SyncLogRepository.create}）。</li>
 *   <li><b>关联预加载</b>：无。</li>
 *   <li><b>软删除</b>：<b>没有</b> {@code deleted_at} 列——本表只有物理删
 *       （{@code CleanupOldLogs}）。别给它加 {@code @TableLogic}。</li>
 *   <li><b>默认排序</b>：仓库层显式写，共两处：
 *       {@code started_at DESC}（{@code FindByDataSource}，带 limit/offset）与
 *       {@code started_at DESC, id ASC}（{@code FindLatest}——第一个来自显式的
 *       {@code Order("started_at DESC")}，{@code id} 是 GORM {@code First(...)}
 *       **追加**的主键序，见 §9 附注）。{@code FindByID} 走 {@code First}，
 *       得到 {@code ORDER BY id}（主键唯一，等于无排序）。</li>
 *   <li><b>唯一索引/外键</b>：{@code data_source_id} 在迁移里对
 *       {@code data_sources(id)} 有 {@code ON DELETE CASCADE} 外键——
 *       H2 测试库里**不建**该约束（与既有表一致），由 service 层保证引用有效。</li>
 *   <li><b>自动时间戳</b>：{@code created_at}（AutoCreateTime）与
 *       {@code updated_at}（AutoUpdateTime）在 CREATE 时**零值才补 now**；
 *       在 {@code Updates(结构体)} 时 {@code updated_at} **无条件覆盖成 now**。
 *       注意 {@code started_at} **不是**自动时间戳（字段名不匹配 GORM 的约定），
 *       它的 DDL {@code DEFAULT CURRENT_TIMESTAMP} 只是兜底，Go 的钩子会显式填它。</li>
 *   <li><b>jsonb 列</b>：{@code result} 用 {@link PgJsonTypeHandler} 映射成
 *       {@link JsonNode}（Go 是 {@code types.JSON} = {@code json.RawMessage}，
 *       两者都是"原样存取的 JSON 文本"）。{@code sync_logs.result} 在迁移里
 *       **没有 DEFAULT**——所以 MyBatis-Plus 对 null 字段省略该列恰好落到 SQL NULL，
 *       与 Go 显式写 NULL 一致，不需要 {@code FieldStrategy.ALWAYS}。</li>
 * </ol>
 */
@TableName(value = "sync_logs", autoResultMap = true)
@JsonPropertyOrder({"id", "data_source_id", "tenant_id", "status", "started_at", "finished_at",
        "items_total", "items_created", "items_updated", "items_deleted", "items_skipped",
        "items_failed", "error_message", "result", "created_at", "updated_at"})
public class SyncLog {

    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    /** 指向 {@code data_sources.id}（迁移里有 ON DELETE CASCADE 外键）。 */
    @TableField("data_source_id")
    @JsonProperty("data_source_id")
    private String dataSourceId = "";

    @TableField("tenant_id")
    @JsonProperty("tenant_id")
    private Long tenantId = 0L;

    /** running / success / partial / failed / canceled。 */
    @TableField("status")
    @JsonProperty("status")
    private String status = "";

    /** 同步开始时间。Go 的 {@code BeforeCreate} 在零值时补 {@code time.Now().UTC()}。 */
    @TableField("started_at")
    @JsonProperty("started_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime startedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    /** 同步完成时间。指针 → nil 输出 {@code null}。 */
    @TableField("finished_at")
    @JsonProperty("finished_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime finishedAt;

    @TableField("items_total")
    @JsonProperty("items_total")
    private int itemsTotal;

    @TableField("items_created")
    @JsonProperty("items_created")
    private int itemsCreated;

    @TableField("items_updated")
    @JsonProperty("items_updated")
    private int itemsUpdated;

    @TableField("items_deleted")
    @JsonProperty("items_deleted")
    private int itemsDeleted;

    @TableField("items_skipped")
    @JsonProperty("items_skipped")
    private int itemsSkipped;

    @TableField("items_failed")
    @JsonProperty("items_failed")
    private int itemsFailed;

    /** 失败时的错误详情。**无 omitempty**：空串照常输出。 */
    @TableField("error_message")
    @JsonProperty("error_message")
    private String errorMessage = "";

    /** 详细的同步结果（JSON）。Go 的 {@code types.JSON}：空时序列化成 {@code null}。 */
    @TableField(value = "result", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("result")
    private JsonNode result;

    @TableField("created_at")
    @JsonProperty("created_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    @TableField("updated_at")
    @JsonProperty("updated_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

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
        startedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
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
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * 对照 Go {@code SyncLog.ParseResult}：解析 {@code result} 列。
     *
     * @return 列为 SQL NULL 时回 {@code null}（Go 的 {@code len(s.Result) == 0} 短路）；
     *         JSON 非法时抛 {@link DataSourceException}（Go 把 unmarshal 错误原样上抛）
     */
    public SyncResult parseResult() {
        try {
            return SyncResult.fromJson(result);
        } catch (RuntimeException e) {
            throw new DataSourceException("parse sync log result: " + e.getMessage(), e);
        }
    }
}
