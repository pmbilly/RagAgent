package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 按 principal 隔离的 MCP OAuth token。
 *
 * <p>Agent 代表**发起调用的 principal** 连接 MCP 服务，所以 token 按
 * (tenant_id, principal_type, principal_id, service_id) 隔离——
 * 不是按 user 隔离。</p>
 *
 * <p>⚠️ 历史要点：隔离维度已从 (tenant, user, service) 演进为
 * (tenant, principal_type, principal_id, service)，索引名为
 * {@code idx_mcp_oauth_tokens_tenant_principal_svc}，user_id 放宽到 VARCHAR(512)。
 * <b>迁移脚本（000062 未含 principal 列、000064 才补）曾落后于模型，
 * 以当前模型为准。</b></p>
 *
 * <p>另有 refresh 租约 CAS：RefreshLeaseID / RefreshLeaseUntil 协调**跨实例**的
 * refresh-token 轮换，保证同一时刻只有一个所有者能拿旧 refresh token 去换新，
 * 这是纯运维字段，永不对外暴露。</p>
 *
 * 落库行为清单：
 * <ul>
 *   <li>表名 {@code mcp_oauth_tokens}（Go 显式 TableName）</li>
 *   <li>钩子 BeforeCreate/BeforeSave：加密 access_token / refresh_token → TypeHandler</li>
 *   <li>钩子 AfterFind：宽容解密 → TypeHandler 读路径</li>
 *   <li>无 DeletedAt（唯一索引 idx_mcp_oauth_tokens_tenant_principal_svc）→ 硬删除</li>
 * </ul>
 */
@TableName("mcp_oauth_tokens")
public class McpOAuthToken {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    /**
     * 历史遗留的「所有者 id」列。新代码以 principal_type/principal_id 为准；
     * 为空时由仓储层填 principal.StorageID()。
     */
    private String userId;
    private String principalType;
    private String principalId;
    private String serviceId;
    /** 秘密：落库加密、读回宽容解密 */
    @TableField(typeHandler = McpSecretTypeHandler.class)
    private String accessToken;
    /** 秘密：落库加密、读回宽容解密 */
    @TableField(typeHandler = McpSecretTypeHandler.class)
    private String refreshToken;
    private String tokenType;
    private OffsetDateTime expiresAt;
    /** 运维字段，永不暴露 */
    private String refreshLeaseId;
    /** 运维字段，永不暴露 */
    private OffsetDateTime refreshLeaseUntil;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public String getPrincipalType() { return principalType; }
    public void setPrincipalType(String v) { principalType = v; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String v) { principalId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId = v; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String v) { accessToken = v; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String v) { refreshToken = v; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String v) { tokenType = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }
    public String getRefreshLeaseId() { return refreshLeaseId; }
    public void setRefreshLeaseId(String v) { refreshLeaseId = v; }
    public OffsetDateTime getRefreshLeaseUntil() { return refreshLeaseUntil; }
    public void setRefreshLeaseUntil(OffsetDateTime v) { refreshLeaseUntil = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
}
