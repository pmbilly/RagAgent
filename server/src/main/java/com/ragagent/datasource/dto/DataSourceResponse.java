package com.ragagent.datasource.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoMapSerializer;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.datasource.domain.DataSource;
import com.ragagent.datasource.domain.DataSourceConfig;
import com.ragagent.datasource.domain.DataSourceConstants;
import com.ragagent.datasource.domain.SyncLog;

/**
 * 数据源管理端点的响应体（对照 Go {@code dto.DataSourceResponse}，
 * internal/handler/dto/datasource.go L17-46）。
 *
 * <h2>它为什么不是裸实体</h2>
 * <p>Go 的 {@code types.DataSource.Config} 里躺着加密后的连接器凭据。直接把实体丢给
 * {@code c.JSON} 会把密文（以及历史行里的明文）送到前端，所以每一个管理端点都必须
 * 经本类出参——<b>credentials 按构造被剥离</b>，"配没配"只经
 * {@code credentials.credentials.configured} 这一个布尔暴露。</p>
 *
 * <h2>键序 = Go struct 声明序</h2>
 * <pre>
 *   {"id","tenant_id","knowledge_base_id","name","type","config","sync_schedule","sync_mode",
 *    "status","conflict_strategy","sync_deletions","last_sync_at","last_sync_cursor",
 *    "last_sync_result","error_message","sync_log_retention_days","created_at","updated_at",
 *    "total_items_synced","latest_sync_log","credentials"}
 * </pre>
 * <p>Go 的 struct 按声明序输出（不是字母序）——{@code config} 夹在 {@code type} 与
 * {@code sync_schedule} 之间、{@code credentials} 在最后。</p>
 *
 * <h2>omitempty 逐字段（照抄 Go 的 tag）</h2>
 * <ul>
 *   <li>{@code config}：指针 + omitempty → ParseConfig 失败时为 {@code null} 并<b>省略整个键</b>；</li>
 *   <li>{@code last_sync_cursor} / {@code last_sync_result}：{@code json.RawMessage} + omitempty
 *       → {@code len(bytes)==0} 省略。Java 用 {@code NON_EMPTY}（JsonNode 的 {@code null}
 *       与空节点都省略）；</li>
 *   <li>{@code error_message}：string + omitempty → 空串省略（注意这与实体自身
 *       "恒输出空串"的形态**不同**，只有 DTO 这一层省略）；</li>
 *   <li>{@code latest_sync_log}：指针 + omitempty → nil 省略；</li>
 *   <li>{@code credentials}：map + omitempty → 空 map 省略。Go 的实现**恒**塞一个
 *       {@code credentials} 键进去，所以线上永远出现；</li>
 *   <li>{@code last_sync_at} / {@code created_at} / {@code updated_at} / {@code total_items_synced}
 *       / {@code sync_deletions} / 四个字符串字段：<b>没有</b> omitempty → 恒输出（null 照输出）。</li>
 * </ul>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子</b>：无（本类不落表）。</li>
 *   <li><b>关联预加载</b>：{@code latest_sync_log} 是"由 service 逐个回填"的
 *       {@code gorm:"-"} 字段，不是 Preload。</li>
 *   <li><b>软删除 / 默认排序 / 唯一索引 / 自动时间戳</b>：全无。</li>
 * </ol>
 */
public class DataSourceResponse {

    private String id = "";

    private long tenantId;

    private String knowledgeBaseId = "";

    private String name = "";

    private String type = "";

    private DataSourceConfigDto config;

    private String syncSchedule = "";

    private String syncMode = "";

    private String status = "";

    private String conflictStrategy = "";

    private boolean syncDeletions;

    private OffsetDateTime lastSyncAt;

    private JsonNode lastSyncCursor;

    private JsonNode lastSyncResult;

    private String errorMessage;

    private int syncLogRetentionDays;

    private OffsetDateTime createdAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private OffsetDateTime updatedAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private long totalItemsSynced;

    private SyncLog latestSyncLog;

    /**
     * 单个逻辑凭据字段。挂 {@link GoMapSerializer}：Go 的 map 恒按 key 排序，
     * 而这里只有一个键——排序本身无所谓，但挂上它同时保证 {@code NON_EMPTY} 的语义
     * （自定义序列化器会让 {@code @JsonInclude(NON_EMPTY)} 失效，见 §9）。
     */
    private Map<String, CredentialFieldMetadata> credentials;

    /** 对照 Go {@code NewDataSourceResponse}：nil 入参回 nil。 */
    public static DataSourceResponse from(DataSource ds) {
        if (ds == null) {
            return null;
        }
        DataSourceResponse out = new DataSourceResponse();
        DataSourceConfigDto cfgDto = null;
        boolean configured = false;

        DataSourceConfig parsed = ds.parseConfig();
        if (parsed != null) {
            cfgDto = new DataSourceConfigDto();
            cfgDto.setType(parsed.getType());
            cfgDto.setResourceIds(parsed.getResourceIds());
            cfgDto.setSettings(parsed.getSettings());
            enrichRssFeedUrlsInSettings(ds.getType(), parsed, cfgDto);
            configured = parsed.hasConfiguredCredentials(ds.getType());
        }

        out.id = ds.getId();
        out.tenantId = ds.getTenantId() == null ? 0L : ds.getTenantId();
        out.knowledgeBaseId = ds.getKnowledgeBaseId();
        out.name = ds.getName();
        out.type = ds.getType();
        out.config = cfgDto;
        out.syncSchedule = ds.getSyncSchedule();
        out.syncMode = ds.getSyncMode();
        out.status = ds.getStatus();
        out.conflictStrategy = ds.getConflictStrategy();
        out.syncDeletions = ds.isSyncDeletions();
        out.lastSyncAt = ds.getLastSyncAt();
        out.lastSyncCursor = ds.getLastSyncCursor();
        out.lastSyncResult = ds.getLastSyncResult();
        // Go 的 ErrorMessage 带 omitempty：空串**省略键**（与实体自身的恒输出不同）
        out.errorMessage = ds.getErrorMessage();
        out.syncLogRetentionDays = ds.getSyncLogRetentionDays();
        out.createdAt = ds.getCreatedAt();
        out.updatedAt = ds.getUpdatedAt();
        out.totalItemsSynced = ds.getTotalItemsSynced() == null ? 0L : ds.getTotalItemsSynced();
        out.latestSyncLog = ds.getLatestSyncLog();
        out.credentials = CredentialsResponse.credentials(configured).fields();
        return out;
    }

    /**
     * 对照 Go {@code enrichRSSFeedURLsInSettings}：把 {@code feed_urls} 从 credentials
     * 补进 settings。
     *
     * <p>Feed URL <b>不是密钥</b>，但它历史上住在加密的 credentials blob 里；
     * 在"新的 settings 里没有"时把老位置的值回显出来，前端才不至于在编辑老数据源时
     * 看到空白的订阅地址。</p>
     */
    static void enrichRssFeedUrlsInSettings(String dsType, DataSourceConfig parsed,
                                            DataSourceConfigDto cfgDto) {
        if (!DataSourceConstants.CONNECTOR_TYPE_RSS.equals(dsType) || parsed == null || cfgDto == null) {
            return;
        }
        if (cfgDto.getSettings() != null) {
            Object v = cfgDto.getSettings().get("feed_urls");
            if (v instanceof String s && !s.trim().isEmpty()) {
                return;
            }
        }
        Map<String, Object> creds = parsed.getCredentials();
        if (creds == null) {
            return;
        }
        Object raw = creds.get("feed_urls");
        if (!(raw instanceof String feedUrls) || feedUrls.trim().isEmpty()) {
            return;
        }
        if (cfgDto.getSettings() == null) {
            cfgDto.setSettings(new java.util.LinkedHashMap<>());
        }
        cfgDto.getSettings().put("feed_urls", feedUrls);
    }

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public long getTenantId() { return tenantId; }
    public void setTenantId(long v) { tenantId = v; }

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { knowledgeBaseId = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }

    public DataSourceConfigDto getConfig() { return config; }
    public void setConfig(DataSourceConfigDto v) { config = v; }

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
    public void setErrorMessage(String v) { errorMessage = v; }

    public int getSyncLogRetentionDays() { return syncLogRetentionDays; }
    public void setSyncLogRetentionDays(int v) { syncLogRetentionDays = v; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }

    public long getTotalItemsSynced() { return totalItemsSynced; }
    public void setTotalItemsSynced(long v) { totalItemsSynced = v; }

    public SyncLog getLatestSyncLog() { return latestSyncLog; }
    public void setLatestSyncLog(SyncLog v) { latestSyncLog = v; }

    public Map<String, CredentialFieldMetadata> getCredentials() { return credentials; }
    public void setCredentials(Map<String, CredentialFieldMetadata> v) { credentials = v; }
}
