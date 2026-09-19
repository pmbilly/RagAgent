package com.ragagent.browserskill.domain;

import java.time.OffsetDateTime;

/**
 * 对照 Go {@code browserskill.PairingRecord}（store.go L51-58）：短时效、一次性配对
 * 令牌哈希（五分钟有效期，scope_key 主键 upsert）。不作为响应体，无 JSON 注解。
 */
public class PairingRecord {

    private String scopeKey;
    private String tokenHash;
    private Long tenant;
    private String user;
    private OffsetDateTime expiresAt;

    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public Long getTenant() { return tenant; }
    public void setTenant(Long tenant) { this.tenant = tenant; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
