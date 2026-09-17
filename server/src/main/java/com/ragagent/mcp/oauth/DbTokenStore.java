package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.protocol.McpContext;

/**
 * 由 {@link OAuthRepository} 支撑的 token 存储，作用域钉死在一个
 * (tenant, principal, service) 三元组上（对照 Go internal/mcp/oauth_tokenstore.go:18-23 的
 * {@code dbTokenStore}）。
 *
 * <p>mcp-go 的 OAuth handler 在授权成功或刷新成功后会调 {@code SaveToken}；
 * 而运行期的 MCP 传输拿到的是 {@link ManagedTokenStore} 包装——
 * 这样刷新决策留在 WeKnora 自己协调的生命周期里，而不是落到依赖库手里。</p>
 */
public class DbTokenStore implements OAuthTokenStore {

    protected final OAuthRepository repo;
    protected final long tenantId;
    protected final TenantContext.Principal principal;
    protected final String serviceId;

    /** 对照 Go {@code newDBTokenStore}：构造期就归一化 principal。 */
    public DbTokenStore(OAuthRepository repo, long tenantId, TenantContext.Principal principal,
                        String serviceId) {
        this.repo = repo;
        this.tenantId = tenantId;
        this.principal = McpPrincipal.normalize(principal);
        this.serviceId = serviceId;
    }

    /**
     * 对照 Go {@code GetToken}：未授权抛 {@link OAuthNoTokenException}
     * （Go：{@code transport.ErrNoToken}）。
     */
    @Override
    public OAuthToken getToken(McpContext ctx) {
        // 对照 Go 的 `if err := ctx.Err(); err != nil { return nil, err }`
        ctx.throwIfCancelled();
        McpOAuthToken row = repo.getTokenForPrincipal(tenantId, principal, serviceId);
        if (row == null || isBlank(row.getAccessToken())) {
            throw new OAuthNoTokenException();
        }
        return new OAuthToken(row.getAccessToken(), row.getRefreshToken(), row.getTokenType(),
                row.getExpiresAt());
    }

    /**
     * 对照 Go {@code SaveToken}：access_token 缺失直接报错（不落一行空 token）；
     * token_type 缺省补 "Bearer"；只给 expires_in 时折算成绝对时刻。
     */
    @Override
    public void saveToken(McpContext ctx, OAuthToken token) {
        ctx.throwIfCancelled();
        if (token == null || isBlank(token.accessToken())) {
            throw OAuthProtocolException.of("OAuth token response did not contain an access_token");
        }
        if (isBlank(token.tokenType())) {
            token.setTokenType("Bearer");
        }
        OffsetDateTime expiresAt = token.expiresAt();
        if (expiresAt == null && token.expiresIn() > 0) {
            expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(token.expiresIn());
        }
        TenantContext.Principal normalized = McpPrincipal.normalize(principal);

        McpOAuthToken row = new McpOAuthToken();
        row.setTenantId(tenantId);
        row.setPrincipalType(normalized.type());
        row.setPrincipalId(normalized.id());
        row.setUserId(McpPrincipal.storageId(normalized));
        row.setServiceId(serviceId);
        row.setAccessToken(token.accessToken());
        row.setRefreshToken(token.refreshToken());
        row.setTokenType(token.tokenType());
        row.setExpiresAt(expiresAt);
        repo.saveTokenForPrincipal(row);
    }

    static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
