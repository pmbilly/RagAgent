package com.ragagent.websearch.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对照 Go {@code types.WebSearchProviderEntity}（表 web_search_providers，迁移 000030）。
 *
 * <p>时间列在 PG 是 naive TIMESTAMP：写本地墙钟、读按 UTC 解释（pgx 语义）。
 * Go 的 PUT 路径用 {@code Select("*")} 写全新实体 → created_at 被写成 Go 零值——
 * Java 侧把该列写 SQL NULL、读回 null 归一为 Go 零值（响应 {@code 0001-01-01T00:00:00Z}），
 * 跨语言等价（Go 读 NULL → 零值；Java 读 year-1 → 同字面量）。</p>
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
     * §9「is 前缀布尔」坑：字段名保留 isDefault 但显式 @TableField 钉列名，
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
