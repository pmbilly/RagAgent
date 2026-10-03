package com.ragagent.websearch.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * web_search_providers 表实体（迁移 000030 引入）。
 *
 * <p>时间列在 PG 是 naive TIMESTAMP：写本地墙钟、读按 UTC 解释。
 * created_at 允许 SQL NULL（早期全列覆盖写入的产物）；null 读出后在
 * 响应里呈现 {@code 0001-01-01T00:00:00Z}。</p>
 */
@TableName(value = "web_search_providers", autoResultMap = true)
public class WebSearchProvider {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String name;
    private String provider;
    private String description;
    @TableField(typeHandler = WebSearchParamsTypeHandler.class)
    private WebSearchProviderParams parameters;
    /**
     * 「is 前缀布尔」坑：字段名保留 isDefault 但显式 @TableField 钉列名，
     * MP 的列解析不依赖 getter 推断。本实体不作响应体（响应走 dto），无 Jackson 双键风险。
     */
    @TableField("is_default")
    private boolean isDefault;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public WebSearchProviderParams getParameters() { return parameters; }
    public void setParameters(WebSearchProviderParams v) { parameters = v; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean v) { isDefault = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
