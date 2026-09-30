/**
 * 租户身份与配置（跨域共享的最底层类型，供 {@code common} 与各业务域共同依赖）。
 *
 * <p>{@link com.ragagent.common.tenant.TenantRole}：租户角色与能力等级（rank 决定最小角色校验）；
 * {@link com.ragagent.common.tenant.TenantProperties}：{@code weknora.tenant} 配置项（RBAC 开关、自助建租户等）。</p>
 *
 * <p>两者 2026-09-30 分别由 {@code auth/domain} 与 {@code config} 下沉至此：从前 {@code common} 反过来依赖
 * {@code auth}/{@code config} 构成环，下沉后依赖方向变为"域 → 平台"单向。</p>
 */
package com.ragagent.common.tenant;
