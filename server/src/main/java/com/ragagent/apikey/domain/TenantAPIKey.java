package com.ragagent.apikey.domain;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * tenant_api_keys 表实体（对照 Go {@code types.TenantAPIKey}，
 * internal/types/tenant_api_key.go L18-38）。
 *
 * <p>租户级与平台级 Key 共用一张表：平台级 Key 的 {@code tenant_id} 为 NULL，
 * 每次请求用 X-Tenant-ID 选择目标空间。{@code key_hash} 用于认证查找；
 * {@code api_key} 在 SYSTEM_AES_KEY 已配置时以密文存储。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ul>
 *   <li><b>钩子 BeforeSave</b>（Go L245-257）：{@code GetAESKey() != nil && APIKey != ""}
 *       时用 AES-GCM 加密后再落库，用 {@code tx.Statement.SetColumn} 覆盖列值；
 *       加密失败**绝不放行明文**，直接中断写入。<br>
 *       等效 Java 代码：{@code TenantAPIKeyRepository.insert/updateApiKeyHash} 在写库前
 *       调 {@link com.ragagent.common.crypto.CryptoService#encryptAESGCM}，
 *       失败抛 {@code IllegalStateException}（对照 Go 的 {@code fmt.Errorf(...)}）。</li>
 *   <li><b>钩子 AfterFind</b>（Go L259-266）：读出后 {@code DecryptStoredSecret} 解密
 *       {@code api_key}，失败**抛错**（严格模式，不是宽容模式）。<br>
 *       等效 Java 代码：{@code TenantAPIKeyRepository.mapRow(...)} 在每条 SELECT 后调
 *       {@code CryptoService.decryptStoredSecret}。注意 Go 的 {@code GetAPIKeyByHash}
 *       用 {@code Session{SkipHooks: true}} **跳过**解密，而
 *       {@code ListKeysWithPlaceholderHash} 不跳过——两条读路径的差异必须保留。</li>
 *   <li><b>默认排序</b>：Go 的 repository 在 {@code ListAPIKeys} /
 *       {@code ListPlatformAPIKeys} 里显式 {@code Order("created_at DESC")}，
 *       Java 侧同样显式写在 SQL 里。</li>
 *   <li><b>唯一索引 / 外键</b>：{@code key_hash} 有 {@code uniqueIndex}；
 *       {@code tenant_id} / {@code revoked_at} 各有普通索引（迁移 000065/000071）。
 *       迁移不改，索引以迁移为准。</li>
 *   <li><b>自动时间戳</b>：Go 的 {@code CreatedAt}/{@code UpdatedAt} 由 GORM 自动写。
 *       本表由 repository 显式写 {@code created_at}/{@code updated_at}（H2/PG 兼容，
 *       避免依赖 MetaObjectHandler 的全局配置）。</li>
 * </ul>
 *
 * <p><b>JSON 契约</b>：本实体**不是**响应体——四个 api-keys 端点都经
 * {@code tenantAPIKeyForResponse} 投影到
 * {@link TenantAPIKeyResponse}。但它的字段仍逐字对齐 Go 的 json tag，
 * 因为它是"有 json tag 的领域对象"，且 {@code key_hash} 必须双向忽略
 * （Go 的 {@code json:"-"}）。</p>
 */
@TableName(value = "tenant_api_keys", autoResultMap = true)
public class TenantAPIKey {

    /** 迁移 000065 是 {@code BIGSERIAL PRIMARY KEY} → 自增。 */
    @TableId(type = IdType.AUTO)
    @JsonProperty("id")
    private Long id;

    /** 平台级 Key 为 null（迁移 000071 放开 NOT NULL）。 */
    @JsonProperty("tenant_id")
    private Long tenantId;

    @JsonProperty("scope_type")
    private String scopeType;

    @JsonProperty("name")
    private String name;

    /**
     * 认证查找用的 SHA-256 十六进制摘要。Go 的 tag 是 {@code json:"-"}——
     * 明文 Token 永不回显，摘要也不外泄。
     */
    @JsonIgnore
    private String keyHash;

    /**
     * 明文 Token，**建 Key 时返回一次**，之后只存密文/摘要。
     *
     * <p>注意 Go 的 json tag 是 {@code api_key}（**不是** {@code "-"}）：它只出现在
     * 创建响应里（经 {@code tenantAPIKeyCreateResponse.Token} 字段），
     * 以及 List/Update 响应里由 {@code tenantAPIKeyForResponse} 带出的
     * 已存值（库中密文经 AfterFind 解密后的明文）。</p>
     */
    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("full_access")
    private boolean fullAccess;

    /**
     * KB 白名单。三态语义见 {@link APIKeyStringListTypeHandler}：
     * {@code null} = 不限制（full-access）/ 未指定；{@code []} = 显式空；
     * 有值 = 白名单。
     */
    @TableField(typeHandler = APIKeyStringListTypeHandler.class)
    @JsonProperty("knowledge_base_ids")
    private List<String> knowledgeBaseIds;

    /** 有界授权清单，见 {@link APIKeyCapability}。 */
    @TableField(typeHandler = APIKeyStringListTypeHandler.class)
    @JsonProperty("capabilities")
    private List<String> capabilities;

    @JsonProperty("last_used_at")
    private OffsetDateTime lastUsedAt;

    @JsonProperty("expires_at")
    private OffsetDateTime expiresAt;

    @JsonProperty("revoked_at")
    private OffsetDateTime revokedAt;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
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

    // ── 派生方法（对照 Go 的方法，**不是字段** → 必须 @JsonIgnore） ──

    /**
     * 对照 Go {@code (*TenantAPIKey).IsPlatform}（L56-58）。
     *
     * <p>⚠️ Go 里这是**方法**：不是 struct 字段，不会出现在 JSON 里。
     * 不加 {@code @JsonIgnore} 会被 Jackson 当成属性写出 {@code "platform":true}
     * ——约定 §9 里复发率最高的坑（阶段 3、4.1 各踩一次）。</p>
     */
    @JsonIgnore
    public boolean isPlatform() {
        return APIKeyScopeType.PLATFORM.equals(APIKeyScopeType.normalize(scopeType));
    }

    /** 对照 Go {@code (*TenantAPIKey).TenantIDValue}（L60-65）：nil 指针 → 0。 */
    @JsonIgnore
    public long tenantIdValue() {
        return tenantId == null ? 0L : tenantId;
    }
}
