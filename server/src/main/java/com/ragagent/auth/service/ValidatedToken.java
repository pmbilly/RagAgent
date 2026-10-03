package com.ragagent.auth.service;

import com.ragagent.auth.domain.User;

/**
 * validateToken 的返回值。
 *
 * @param user     校验通过的用户
 * @param tenantId JWT 的 tenant_id claim（0 表示 claim 缺失，调用方按既定语义 fallback）
 */
public record ValidatedToken(User user, long tenantId) {
}
