/**
 * API 密钥域：租户级 API Key 的签发、校验与访问控制——路由策略（{@link com.ragagent.apikey.domain.APIKeyRoutePolicies}）
 * 决定"这把 Key 能走哪些路由"，认证通道（{@link com.ragagent.apikey.filter.APIKeyAuthChannel}）在过滤链里把它换成调用身份。
 * 与 {@code auth} 的分工：auth 管人（登录/租户/RBAC），本域管机器调用凭据。
  */
package com.ragagent.apikey;
