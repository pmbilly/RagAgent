package com.ragagent.auth.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * users.preferences jsonb 列（对照 Go types/user.go UserPreferences）。
 *
 * 三个字段全为可选（指针语义）：JSON 中省略 = 客户端没传这个 key（部分更新时保留原值）。
 * 空对象序列化为 {}，与 Go 的 "Empty struct serialises to {}" 契约一致
 * （users 表该列 NOT NULL DEFAULT '{}'）。
 *
 * last_active_tenant_id 的特殊值：0 = 清除偏好的哨兵；N = 偏好空间 id。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPreferences {

    private Long lastActiveTenantId;
    private Boolean oidcOnlyLogin;

    public Long getLastActiveTenantId() { return lastActiveTenantId; }
    public void setLastActiveTenantId(Long v) { lastActiveTenantId = v; }
    public Boolean getOidcOnlyLogin() { return oidcOnlyLogin; }
    public void setOidcOnlyLogin(Boolean v) { oidcOnlyLogin = v; }
}
