package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MCP 服务的 OAuth 客户端凭据。
 *
 * <p>支持 RFC 7591 动态客户端注册的服务：client_id（及可选 client_secret）
 * **每个服务只注册一次**，该服务的所有用户复用，避免每次授权都走一轮注册。</p>
 *
 * <p>每 (tenant_id, service_id) 一行。client_secret 落库加密（SYSTEM_AES_KEY 配置时
 * AES-256-GCM），由 {@link McpSecretTypeHandler} 承担。</p>
 *
 * 落库行为清单：
 * <ul>
 *   <li>表名固定为 {@code mcp_oauth_clients}（默认复数化策略会得到错误表名）→
 *       Java {@code @TableName("mcp_oauth_clients")}</li>
 *   <li>插入前：ID 为空则生成 UUID（Java 由调用方生成，语义等价）</li>
 *   <li>写入前：加密 client_secret → TypeHandler 写路径</li>
 *   <li>读出后：宽容解密 → TypeHandler 读路径</li>
 *   <li>无 DeletedAt → **硬删除**（DeleteClient 是物理 DELETE）</li>
 *   <li>唯一索引 idx_mcp_oauth_clients_tenant_svc(tenant_id, service_id) —— 与迁移一致</li>
 * </ul>
 */
@TableName("mcp_oauth_clients")
public class McpOAuthClient {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String serviceId;
    private String clientId;
    /** 秘密：落库加密、读回宽容解密。JSON 序列化恒不输出。 */
    @TableField(typeHandler = McpSecretTypeHandler.class)
    private String clientSecret;
    private String redirectUri;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId = v; }
    public String getClientId() { return clientId; }
    public void setClientId(String v) { clientId = v; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String v) { clientSecret = v; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String v) { redirectUri = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
}
