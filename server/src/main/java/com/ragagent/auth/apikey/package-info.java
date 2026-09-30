/**
 * API 密钥子域（{@code auth} 之下，机器调用凭据面）：租户级 API Key 的签发、校验与访问控制——路由策略（{@link com.ragagent.auth.apikey.domain.APIKeyRoutePolicies}）
 * 决定"这把 Key 能走哪些路由"，认证通道（{@link com.ragagent.auth.apikey.filter.APIKeyAuthChannel}）在过滤链里把它换成调用身份。
 * 与父包的分工：auth 管人（登录/租户/RBAC），本子域管机器调用凭据；
 * 2026-09-30 由顶层包 {@code apikey} 并入（原与 auth 互相成环，并入即消环）。
  */
package com.ragagent.auth.apikey;
