package com.ragagent.browserskill.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeDeserializer;
import com.ragagent.common.web.GoTimeSerializer;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 对照 Go {@code browserskill.DeviceRecord}（store.go L25-49）：持久化的设备授权元数据，
 * 只存 SHA-256 令牌哈希、绝不存密钥。每租户/用户一台注册设备是当前产品契约。
 *
 * <p>JSON 投影只暴露 id/label/expires_at/renew_after/created_at/last_seen_at/
 * revoked_at(omitempty)——Go 的 json:"-" 字段全部 {@link JsonIgnore}；
 * 键名逐字段对照 Go 的 json tag（约束 §7.5 第 4 条）。本类型是
 * GET /api/v1/me/browser 响应里 device 键的形状（AccountStatus 嵌套输出）。</p>
 */
@TableName("browser_devices")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceRecord {

    /** GORM primaryKey；JSON "-" */
    @TableId("scope_key")
    @JsonIgnore
    private String scopeKey;

    /** uniqueIndex；响应键 id */
    @JsonProperty("id")
    private String id;

    @JsonIgnore
    private Long tenant;

    /** 列名是带引号的 "user"（迁移 000093 原文） */
    @TableField("\"user\"")
    @JsonIgnore
    private String user;

    @JsonProperty("label")
    private String label;

    /** uniqueIndex；JSON "-" */
    @JsonIgnore
    private String tokenHash;

    @JsonIgnore
    private String previousHash;

    @JsonIgnore
    private OffsetDateTime previousUntil;

    @JsonProperty("expires_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime expiresAt;

    @JsonProperty("renew_after")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime renewAfter;

    @JsonProperty("created_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime createdAt;

    @JsonProperty("last_seen_at")
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime lastSeenAt;

    /** 指针 + omitempty：null 省略（Go *time.Time） */
    @JsonProperty("revoked_at")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = GoTimeSerializer.class)
    @JsonDeserialize(using = GoTimeDeserializer.class)
    private OffsetDateTime revokedAt;

    @JsonIgnore
    private String owner;

    @JsonIgnore
    private String leaseKey;

    @JsonIgnore
    private String ownerUrl;

    @JsonIgnore
    private OffsetDateTime leaseUntil;

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getTenant() { return tenant; }
    public void setTenant(Long tenant) { this.tenant = tenant; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }
    public OffsetDateTime getPreviousUntil() { return previousUntil; }
    public void setPreviousUntil(OffsetDateTime previousUntil) { this.previousUntil = previousUntil; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getRenewAfter() { return renewAfter; }
    public void setRenewAfter(OffsetDateTime renewAfter) { this.renewAfter = renewAfter; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getLeaseKey() { return leaseKey; }
    public void setLeaseKey(String leaseKey) { this.leaseKey = leaseKey; }
    public String getOwnerUrl() { return ownerUrl; }
    public void setOwnerUrl(String ownerUrl) { this.ownerUrl = ownerUrl; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime leaseUntil) { this.leaseUntil = leaseUntil; }

    /** 对照 DeviceRecord.scope()（store.go L49） */
    public Scope scope() {
        return new Scope(tenant == null ? 0 : tenant, user == null ? "" : user);
    }
}
