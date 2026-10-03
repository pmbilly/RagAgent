package com.ragagent.auth.apikey.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * tenant_api_keys 表实体。
 *
 * <p>租户级与平台级 Key 共用一张表：平台级 Key 的 {@code tenant_id} 为 NULL，
 * 每次请求用 X-Tenant-ID 选择目标空间。{@code key_hash} 用于认证查找；
 * {@code api_key} 在 SYSTEM_AES_KEY 已配置时以密文存储。</p>
 *
 * <h2>落库行为清单</h2>
 * <ul>
 *   <li><b>写路径加密</b>：已配置 SYSTEM_AES_KEY 且 {@code apiKey} 非空时，
 *       先用 AES-GCM 加密再落库；
 *       加密失败**绝不放行明文**，直接中断写入。<br>
 *       落点：{@code TenantAPIKeyRepository.insert/updateApiKeyHash} 在写库前
 *       调 {@link com.ragagent.common.crypto.CryptoService#encryptAESGCM}，
 *       失败抛 {@code IllegalStateException}。</li>
 *   <li><b>读路径解密</b>：读出后解密 {@code api_key}，失败**抛错**
 *       （严格模式，不是宽容模式）。<br>
 *       落点：{@code TenantAPIKeyRepository.mapRow(...)} 在每条 SELECT 后调
 *       {@code CryptoService.decryptStoredSecret}。按 hash 的认证查找
 *       **跳过**解密，占位符 hash 列表路径不跳过——两条读路径的差异必须保留。</li>
 *   <li><b>默认排序</b>：列表查询显式 {@code ORDER BY created_at DESC}。</li>
 *   <li><b>唯一索引 / 外键</b>：{@code key_hash} 有 {@code uniqueIndex}；
 *       {@code tenant_id} / {@code revoked_at} 各有普通索引（迁移 000065/000071）。
 *       迁移不改，索引以迁移为准。</li>
 *   <li><b>时间戳</b>：由 repository 显式写 {@code created_at}/{@code updated_at}
 *       （H2/PG 兼容，避免依赖 MetaObjectHandler 的全局配置）。</li>
 * </ul>
 *
 * <p><b>JSON 契约</b>：本实体**不是**响应体——四个 api-keys 端点都经
 * {@code tenantAPIKeyForResponse} 投影到
 * {@link TenantAPIKeyResponse}。字段命名对齐响应契约（camelCase），
 * 且 {@code key_hash} 必须双向忽略（不进 JSON）。</p>
 */
@TableName(value = "tenant_api_keys", autoResultMap = true)
public class TenantAPIKey {

    /** 迁移 000065 是 {@code BIGSERIAL PRIMARY KEY} → 自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台级 Key 为 null（迁移 000071 放开 NOT NULL）。 */
    private Long tenantId;

    private String scopeType;

    private String name;

    /**
     * 认证查找用的 SHA-256 十六进制摘要。
     * 明文 Token 永不回显，摘要也不外泄。
     */
    @JsonIgnore
    private String keyHash;

    /**
     * 明文 Token，**建 Key 时返回一次**，之后只存密文/摘要。
     *
     * <p>JSON 名为 {@code api_key}（**不是**被忽略字段）：它只出现在
     * 创建响应里（经 {@code TenantAPIKeyCreateResponse.token} 字段），
     * 以及 List/Update 响应里由响应投影带出的
     * 已存值（库中密文解密后的明文）。</p>
     */
    private String apiKey;

    private boolean fullAccess;

    /**
     * KB 白名单。三态语义见 {@link APIKeyStringListTypeHandler}：
     * {@code null} = 不限制（full-access）/ 未指定；{@code []} = 显式空；
     * 有值 = 白名单。
     */
    @TableField(typeHandler = APIKeyStringListTypeHandler.class)
    private List<String> knowledgeBaseIds;

    /** 有界授权清单，见 {@link APIKeyCapability}。 */
    @TableField(typeHandler = APIKeyStringListTypeHandler.class)
    private List<String> capabilities;

    private OffsetDateTime lastUsedAt;

    private OffsetDateTime expiresAt;

    private OffsetDateTime revokedAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    // ── 访问器 ──

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String v) { scopeType = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    @JsonIgnore
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String v) { keyHash = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public boolean isFullAccess() { return fullAccess; }
    public void setFullAccess(boolean v) { fullAccess = v; }
    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<String> v) { knowledgeBaseIds = v; }
    public List<String> getCapabilities() { return capabilities; }
    public void setCapabilities(List<String> v) { capabilities = v; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime v) { lastUsedAt = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { expiresAt = v; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime v) { revokedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }

    // ── 派生方法（不是字段 → 必须 @JsonIgnore） ──

    /**
     * 是否平台级 Key。
     *
     * <p>⚠️ 这是**方法**：不是字段，不会出现在 JSON 里。
     * 不加 {@code @JsonIgnore} 会被 Jackson 当成属性写出 {@code "platform":true}
     * ——最容易复发的坑。</p>
     */
    @JsonIgnore
    public boolean isPlatform() {
        return APIKeyScopeType.PLATFORM.equals(APIKeyScopeType.normalize(scopeType));
    }

    /** 租户 ID；null 视为 0。 */
    @JsonIgnore
    public long tenantIdValue() {
        return tenantId == null ? 0L : tenantId;
    }
}
