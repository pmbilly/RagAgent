package com.ragagent.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.ragagent.common.context.TenantContext;

/**
 * 一次授权流程的<b>非秘密</b>、可鉴权状态。
 *
 * <p><b>键名</b>：同 {@link OAuthState}——只进 Redis/内存，键名＝组件名。</p>
 *
 * <p><b>为什么要与 {@link OAuthState} 分开存</b>：{@link OAuthStateStore#take}
 * 会在 code 交换<b>完成之前</b>就消费掉 PKCE state，而发起方（前端弹窗）还需要区分
 * "本次回调完成了" 与 "同一服务上早已存在的旧 token"。attempt 记录按 state 独立存在，
 * 且只在 {@link OAuthStateStore#completeAttempt} 后才置 {@code completed=true}。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthAttempt(
        long tenantId,
        OAuthState.Principal principal,
        String serviceId,
        boolean completed) {

    public OAuthAttempt {
        serviceId = serviceId == null ? "" : serviceId;
    }

    public TenantContext.Principal principalOrNull() {
        return principal == null ? null : principal.toContextPrincipal();
    }

    public OAuthAttempt completedCopy() {
        return new OAuthAttempt(tenantId, principal, serviceId, true);
    }
}
