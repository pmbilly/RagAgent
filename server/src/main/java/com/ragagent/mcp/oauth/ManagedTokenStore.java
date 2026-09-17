package com.ragagent.mcp.oauth;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.protocol.McpContext;

/**
 * 对 mcp-go 传输<b>隐藏本地过期时间</b>的 token 存储（对照 Go
 * internal/mcp/oauth_tokenstore.go:30-47 的 {@code managedTokenStore}）。
 *
 * <p><b>保真要点（Go 注释原文精神）</b>：WeKnora 在每次操作前自己检查落库的
 * {@code ExpiresAt} 并执行<b>协调过的</b>刷新（跨实例刷新租约）。如果让依赖库也自动刷新，
 * 就会绕过租约、并把"刷新临时失败"塌缩成一个笼统的 "authorization required" 错误。</p>
 *
 * <p>实现上只做一件事：读出来的 token 把 {@code expiresAt} 抹成 {@code null}
 * （Go：{@code token.ExpiresAt = time.Time{}}）。<b>数据库里的真实过期时间不变</b>——
 * 预检（{@code ensureFresh}）还要靠它。</p>
 *
 * <p>本类<b>不自行刷新</b>：刷新交给 {@link OAuthRuntime}。</p>
 */
public class ManagedTokenStore extends DbTokenStore {

    public ManagedTokenStore(OAuthRepository repo, long tenantId, TenantContext.Principal principal,
                             String serviceId) {
        super(repo, tenantId, principal, serviceId);
    }

    @Override
    public OAuthToken getToken(McpContext ctx) {
        OAuthToken token = super.getToken(ctx);
        // 对照 Go：token.ExpiresAt = time.Time{} —— 让依赖库永远看不到"已过期"
        token.setExpiresAt(null);
        return token;
    }
}
