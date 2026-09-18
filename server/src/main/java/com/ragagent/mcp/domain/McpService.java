package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragagent.common.web.PgJsonTypeHandler;
import com.ragagent.common.web.GoMapSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * MCP 服务配置实体（对照 Go types.MCPService，internal/types/mcp.go:25-45）。
 *
 * GORM 隐式行为清单（约定 §3）：
 * - 软删除 → 显式 isNull("deleted_at")
 * - 钩子 BeforeCreate：ID 为空时生成 UUID（Java 由 service 生成，语义等价）
 * - 表名靠默认复数化（Go 无 TableName()）→ 显式 @TableName("mcp_services")
 * - GORM tag 里的 uniqueIndex:idx_tenant_name 在**磁盘上并不存在**对应的唯一索引
 *   （迁移里没有）——Go 标签与 SQL 有偏差，**以 SQL 为准**，Java 不加唯一约束
 * - jsonb 列：headers / auth_config / advanced_config / stdio_config / env_vars
 *
 * **密钥处理**：authConfig 内的 apiKey/token 落库加密、读回宽容解密，全部由
 * {@link McpAuthConfigTypeHandler} 承担（对照 Go 的 driver.Valuer/sql.Scanner）。
 * 主资源响应**不走本实体**——走 dto.McpServiceResponse，构造期就没有秘密字段。
 */
@TableName(value = "mcp_services", autoResultMap = true)
public class McpService {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String name;
    private String description;
    private boolean enabled;
    private String transportType;
    /** 可选：SSE / HTTP Streamable 时必填 */
    private String url;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, String> headers;
    @TableField(typeHandler = McpAuthConfigTypeHandler.class)
    private McpAuthConfig authConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private McpAdvancedConfig advancedConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    private McpStdioConfig stdioConfig;
    @TableField(typeHandler = PgJsonTypeHandler.class)
    // Go 对 map 恒按字节序输出；Jackson 不排——不挂这个，多键 map 会与 Go 分叉
    @JsonSerialize(using = GoMapSerializer.class)
    private Map<String, String> envVars;
    /** 是否为内置服务（对所有工作空间可见） */
    private boolean isBuiltin;
    /** 本地维护，不被目录刷新覆盖 */
    private String usageInstructions;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;

    /**
     * 保留历史服务上的文档，直到它们通过单一 usage-instructions 字段被编辑
     * （对照 Go EffectiveUsageInstructions）。
     */
    public String effectiveUsageInstructions() {
        String text = usageInstructions == null ? "" : usageInstructions.trim();
        if (!text.isEmpty()) {
            return text;
        }
        return description == null ? "" : description.trim();
    }

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getTransportType() { return transportType; }
    public void setTransportType(String v) { transportType = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { url = v; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> v) { headers = v; }
    public McpAuthConfig getAuthConfig() { return authConfig; }
    public void setAuthConfig(McpAuthConfig v) { authConfig = v; }
    public McpAdvancedConfig getAdvancedConfig() { return advancedConfig; }
    public void setAdvancedConfig(McpAdvancedConfig v) { advancedConfig = v; }
    public McpStdioConfig getStdioConfig() { return stdioConfig; }
    public void setStdioConfig(McpStdioConfig v) { stdioConfig = v; }
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> v) { envVars = v; }
    public boolean isIsBuiltin() { return isBuiltin; }
    public void setIsBuiltin(boolean v) { isBuiltin = v; }
    public String getUsageInstructions() { return usageInstructions; }
    public void setUsageInstructions(String v) { usageInstructions = v == null ? "" : v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime v) { deletedAt = v; }
}
