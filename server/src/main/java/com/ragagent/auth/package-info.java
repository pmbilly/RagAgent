/**
 * 认证与租户域：登录/OIDC/令牌、用户与租户目录、RBAC 与会话身份。
 * 对外入口 {@code /api/v1/auth}（{@link com.ragagent.auth.controller.AuthController}）与租户目录
 * （{@link com.ragagent.auth.controller.TenantCatalogController}）；身份经 {@code TenantContext} 下传全应用。
  */
package com.ragagent.auth;
