package com.ragagent.datasource.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.common.web.PgJsonTypeHandler;

/**
 * 一个配置好的外部数据源（对照 Go {@code types.DataSource}，
 * internal/types/datasource.go L64-142；表 {@code data_sources}，
 * 迁移 {@code 000029_datasource_tables.up.sql}）。
 *
 * <h2>它不是响应体（但仍然要逐字节对齐）</h2>
 * <p>handler 一律经 {@code dto.NewDataSourceResponse(ds)} 出参——Credential map
 * 按构造被剥离。不过：</p>
 * <ol>
 *   <li>它的 {@code config} / {@code last_sync_cursor} / {@code last_sync_result}
 *       是**原样透传**给 DTO 的 jsonb 载荷（{@code json.RawMessage}），
 *       所以本对象的字段序列化字节会影响线上；</li>
 *   <li>{@code latest_sync_log} 是 {@code *types.SyncLog}，而 SyncLog 是裸实体响应体。</li>
 * </ol>
 *
 * <h2>Go 实录（{@code DataSourceJsonTest} 逐字节钉住）</h2>
 * <pre>
 *   DataSource{} →
 *   {"id":"","tenant_id":0,"knowledge_base_id":"","name":"","type":"","config":null,
 *    "sync_schedule":"","sync_mode":"","status":"","conflict_strategy":"","sync_deletions":false,
 *    "last_sync_at":null,"last_sync_cursor":null,"last_sync_result":null,"error_message":"",
 *    "sync_log_retention_days":0,"created_at":"0001-01-01T00:00:00Z",
 *    "updated_at":"0001-01-01T00:00:00Z","deleted_at":null,"total_items_synced":0,
 *    "latest_sync_log":null}
 * </pre>
 * <p><b>一个 omitempty 都没有</b>——{@code scheduled}/{@code error_message} 空串照输出、
 * 三个 JSON 列 null 照输出、{@code total_items_synced} 与 {@code latest_sync_log}
 * 即使不落库也在 JSON 里。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子 {@code BeforeCreate}</b>：
 *       <pre>if d.ID == "" { d.ID = uuid.New().String() }</pre>
 *       Java 等效在 {@code DataSourceRepository.create} 里显式执行。</li>
 *   <li><b>关联预加载</b>：无。{@code TotalItemsSynced} / {@code LatestSyncLog}
 *       是"查询时另行填充"的两个 {@code gorm:"-"} 字段，仓库层不碰它们
 *       （service 层逐个 data source 补）。</li>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt} → Java 用**显式
 *       {@code deleted_at IS NULL} 条件**，不用 {@code @TableLogic}
 *       （约定 §8：datetime 逻辑删除值在 MP 各版本行为敏感，显式条件语义确定）。
 *       {@code Delete} 因此是 {@code UPDATE … SET deleted_at = now}。</li>
 *   <li><b>默认排序</b>：仓库层显式写，共两处——
 *       {@code created_at DESC}（{@code FindByKnowledgeBase}、{@code FindActive}）。
 *       {@code FindByID} 走 {@code First}，得到 {@code ORDER BY id}（主键唯一）。</li>
 *   <li><b>唯一索引/外键</b>：只有普通索引
 *       （{@code idx_data_sources_{tenant_id,knowledge_base_id,type,status,deleted_at}}），
 *       **没有唯一约束**——同名数据源可以并存。
 *       {@code sync_logs.data_source_id} 对本法有 {@code ON DELETE CASCADE} 外键，
 *       但 H2 测试库不建（与既有表一致）。</li>
 *   <li><b>自动时间戳</b>：{@code created_at}（AutoCreateTime）与
 *       {@code updated_at}（AutoUpdateTime）在 CREATE 时**零值才补 now**；
 *       在 {@code Updates(结构体)} 时 {@code updated_at} **无条件覆盖成 now**。</li>
 *   <li><b>⚠️ 带 DEFAULT 的列在 CREATE 时会被 GORM 用默认值改写并回写结构体</b>
 *       （{@code callbacks/create.go} L336-341，见 §9「GORM 的 CREATE 零值→DDL 默认值替换」）：
 *       <ul>
 *         <li>{@code sync_mode} ""→{@code 'incremental'}</li>
 *         <li>{@code status} ""→{@code 'active'}</li>
 *         <li>{@code conflict_strategy} ""→{@code 'overwrite'}</li>
 *         <li>{@code sync_log_retention_days} 0→30</li>
 *         <li>{@code sync_deletions} false→true（**但随后被仓储显式改回来**，见下）</li>
 *       </ul>
 *       Java 侧由 {@code DataSourceRepository.applyInsertDefaults} 复刻，
 *       使得内存对象与落库值都与 Go 一致。</li>
 *   <li><b>⚠️ {@code sync_deletions} 的三步舞</b>：GORM 会把非指针 bool 的 {@code false}
 *       当成零值、替成 {@code default:true} 并回写内存，于是"用户选了 false"会丢。
 *       仓储的做法是在同一事务里
 *       {@code Create} → {@code UpdateColumn("sync_deletions", 原值)} → 再把原值写回内存对象。
 *       <b>净效果就是"落库与内存都等于调用方给的值"</b>，所以 Java 侧直接插原值即可
 *       （{@code DataSourceRepository.create} 里有逐行说明）。</li>
 *   <li><b>jsonb 列</b>：{@code config} / {@code last_sync_cursor} / {@code last_sync_result}
 *       用 {@link PgJsonTypeHandler} 映射成 {@link JsonNode}。三列在迁移里**都没有 DEFAULT**
 *       ——所以 MyBatis-Plus 对 null 字段省略该列恰好落到 SQL NULL，与 Go 的
 *       {@code JSON.Value()} 对空值回 {@code nil, nil} 一致，**不需要**
 *       {@code FieldStrategy.ALWAYS}（那条规则只针对带 DEFAULT 的 jsonb 列，
 *       例如 wiki 的 {@code page_metadata}，见 §9）。</li>
 *   <li><b>{@code gorm:"-"}</b>：{@code TotalItemsSynced} 与 {@code LatestSyncLog}
 *       不落库 → Java 侧 {@code @TableField(exist = false)}。</li>
 * </ol>
 */
@TableName(value = "data_sources", autoResultMap = true)
@JsonPropertyOrder({"id", "tenant_id", "knowledge_base_id", "name", "type", "config",
        "sync_schedule", "sync_mode", "status", "conflict_strategy", "sync_deletions",
        "last_sync_at", "last_sync_cursor", "last_sync_result", "error_message",
        "sync_log_retention_days", "created_at", "updated_at", "deleted_at",
        "total_items_synced", "latest_sync_log"})
public class DataSource {

    /** 唯一标识。Go 的 {@code BeforeCreate} 在为空时生成 UUID。 */
    @TableId(value = "id", type = IdType.INPUT)
    @JsonProperty("id")
    private String id = "";

    /** 多工作区隔离用的租户 ID。 */
    @TableField("tenant_id")
    @JsonProperty("tenant_id")
    private Long tenantId = 0L;

    /** 目标知识库 ID。 */
    @TableField("knowledge_base_id")
    @JsonProperty("knowledge_base_id")
    private String knowledgeBaseId = "";

    @TableField("name")
    @JsonProperty("name")
    private String name = "";

    /** 连接器类型（feishu / notion / confluence …），见 {@link DataSourceConstants}。 */
    @TableField("type")
    @JsonProperty("type")
    private String type = "";

    /**
     * 加密后的配置（API 凭据、token 等），以 AES-256-GCM 加密的 JSON 存。
     *
     * <p>由 {@link DataSourceConfig#toJSON()} 产出；读回用 {@link #parseConfig()}。</p>
     */
    @TableField(value = "config", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("config")
    private JsonNode config;

    /** 定时同步的 cron 表达式（例如每 6 小时一次）。无 omitempty → 空串照输出。 */
    @TableField("sync_schedule")
    @JsonProperty("sync_schedule")
    private String syncSchedule = "";

    /** {@code "incremental"}（推荐）或 {@code "full"}。CREATE 时零值被 GORM 替成默认值。 */
    @TableField("sync_mode")
    @JsonProperty("sync_mode")
    private String syncMode = "";

    /** active / paused / error。CREATE 时零值被 GORM 替成 {@code 'active'}。 */
    @TableField("status")
    @JsonProperty("status")
    private String status = "";

    /** overwrite 或 skip。CREATE 时零值被 GORM 替成 {@code 'overwrite'}。 */
    @TableField("conflict_strategy")
    @JsonProperty("conflict_strategy")
    private String conflictStrategy = "";

    /**
     * 是否同步源端的删除。
     *
     * <p>⚠️ 这是本表唯一"GORM 默认值会吃掉调用方取值"的列——见类注释第 8 条。
     * 仓储在事务里显式把调用方的值写回去，Java 侧直接插原值。</p>
     */
    @TableField("sync_deletions")
    @JsonProperty("sync_deletions")
    private boolean syncDeletions;

    /** 上次成功同步的时间。指针 → nil 输出 {@code null}。 */
    @TableField("last_sync_at")
    @JsonProperty("last_sync_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastSyncAt;

    /** 增量同步的游标/状态（连接器私有）。由 {@link SyncCursor#toJSON()} 产出。 */
    @TableField(value = "last_sync_cursor", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("last_sync_cursor")
    private JsonNode lastSyncCursor;

    /** 上次同步结果的摘要。由 {@link SyncResult#toJSON()} 产出。 */
    @TableField(value = "last_sync_result", typeHandler = PgJsonTypeHandler.class)
    @JsonProperty("last_sync_result")
    private JsonNode lastSyncResult;

    /** status 为 {@code "error"} 时的错误消息。无 omitempty → 空串照输出。 */
    @TableField("error_message")
    @JsonProperty("error_message")
    private String errorMessage = "";

    /** 同步日志保留天数（默认 30）。CREATE 时零值被 GORM 替成 30。 */
    @TableField("sync_log_retention_days")
    @JsonProperty("sync_log_retention_days")
    private int syncLogRetentionDays;

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

    /**
     * 软删除时间戳。{@code gorm.DeletedAt} 的 {@code MarshalJSON} 在未删除时输出
     * {@code null}、已删除时输出 RFC3339——Java 侧一个可空 {@code OffsetDateTime} 正好等价
     * （Jackson 不调自定义序列化器处理 null，见 §9）。
     */
    @TableField("deleted_at")
    @JsonProperty("deleted_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime deletedAt;

    /** 已同步条目总数。{@code gorm:"-"}：不落库，查询时由 service 计算填充。 */
    @TableField(exist = false)
    @JsonProperty("total_items_synced")
    private Long totalItemsSynced = 0L;

    /** 最近一次同步日志。{@code gorm:"-"}：不落库，查询时由 service 填充。 */
    @TableField(exist = false)
    @JsonProperty("latest_sync_log")
    private SyncLog latestSyncLog;

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v == null ? 0L : v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public JsonNode getConfig() { return config; }
    public void setConfig(JsonNode v) { config = v; }

    public String getSyncSchedule() { return syncSchedule; }
    public void setSyncSchedule(String v) { syncSchedule = v == null ? "" : v; }

    public String getSyncMode() { return syncMode; }
    public void setSyncMode(String v) { syncMode = v == null ? "" : v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { status = v == null ? "" : v; }

    public String getConflictStrategy() { return conflictStrategy; }
    public void setConflictStrategy(String v) { conflictStrategy = v == null ? "" : v; }

    public boolean isSyncDeletions() { return syncDeletions; }
    public void setSyncDeletions(boolean v) { syncDeletions = v; }

    public OffsetDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(OffsetDateTime v) { lastSyncAt = v; }

    public JsonNode getLastSyncCursor() { return lastSyncCursor; }
    public void setLastSyncCursor(JsonNode v) { lastSyncCursor = v; }

    public JsonNode getLastSyncResult() { return lastSyncResult; }
    public void setLastSyncResult(JsonNode v) { lastSyncResult = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v == null ? "" : v; }

    public int getSyncLogRetentionDays() { return syncLogRetentionDays; }
    public void setSyncLogRetentionDays(int v) { syncLogRetentionDays = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) {
        createdAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) {
        updatedAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }

    public Long getTotalItemsSynced() { return totalItemsSynced; }
    public void setTotalItemsSynced(Long v) { totalItemsSynced = v == null ? 0L : v; }

    public SyncLog getLatestSyncLog() { return latestSyncLog; }
    public void setLatestSyncLog(SyncLog v) { latestSyncLog = v; }

    // ── 解析方法（对照 Go 的四个 Parse*） ──────────────────────────────────

    /**
     * 对照 Go {@code DataSource.ParseConfig}：解析 {@code config} 列，
     * 并**宽容解密**其中每一项凭据。
     *
     * <p>对每项凭据三种情况：空串不动；历史明文（无 {@code enc:v1:} 前缀）原样返回，
     * 让老行不必迁移；带前缀的解密——失败时**不**让加载失败，而是把该字段置空并记日志。
     * 这样行仍然可见，{@code HasCredentials()} 回 false，UI 显示"凭据未配置"，
     * 用户可以重新输入而不丢掉数据源的其余部分。</p>
     */
    public DataSourceConfig parseConfig() {
        DataSourceConfig parsed = DataSourceConfig.fromJson(config);
        if (parsed == null) {
            return null;
        }
        java.util.Map<String, Object> creds = parsed.getCredentials();
        if (creds == null || creds.isEmpty()) {
            return parsed;
        }
        com.ragagent.common.crypto.CryptoService crypto = new com.ragagent.common.crypto.CryptoService();
        for (java.util.Map.Entry<String, Object> entry : creds.entrySet()) {
            Object v = entry.getValue();
            if (!(v instanceof String s) || s.isEmpty()) {
                continue;
            }
            com.ragagent.common.crypto.CryptoService.LenientResult r =
                    crypto.decryptStoredSecretLenient(s);
            if (r.ok()) {
                entry.setValue(r.plaintext());
            } else {
                // 与其它 Scan 路径同理：别让加载失败——置空让行保持可见。
                org.slf4j.LoggerFactory.getLogger(DataSource.class).warn(
                        "[crypto] datasource credential \"{}\": decrypt failed "
                                + "(SYSTEM_AES_KEY missing/rotated?), treating as unconfigured",
                        entry.getKey());
                entry.setValue("");
            }
        }
        return parsed;
    }

    /** 对照 Go {@code DataSource.ParseSyncCursor}。 */
    public SyncCursor parseSyncCursor() {
        return SyncCursor.fromJson(lastSyncCursor);
    }

    /** 对照 Go {@code DataSource.ParseSyncResult}。 */
    public SyncResult parseSyncResult() {
        return SyncResult.fromJson(lastSyncResult);
    }
}
