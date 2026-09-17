package com.ragagent.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.protocol.McpContext;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/oauth_principal_test.go。
 *
 * <p>三条：{@code dbTokenStore} 写的是 principal（不是 user）、
 * {@code managedTokenStore} 不自行刷新（对依赖库隐藏过期时间）、
 * 以及 OAuth 服务的连接缓存键按 principal 隔离。</p>
 */
class OAuthPrincipalTest {

    /** 对照 Go {@code TestDBTokenStoreUsesPrincipal}。 */
    @Test
    void dbTokenStoreUsesPrincipal() {
        FakeOAuthRepository repo = new FakeOAuthRepository();
        TenantContext.Principal principal =
                new TenantContext.Principal(McpPrincipal.API_EXTERNAL_USER, "7:external-42");
        DbTokenStore store = new DbTokenStore(repo, 7, principal, "svc-1");

        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
        store.saveToken(McpContext.none(),
                new OAuthToken("access", "refresh", "Bearer", expiresAt));

        McpOAuthToken row = repo.getTokenForPrincipal(7, principal, "svc-1");
        assertNotNull(row);
        assertEquals(McpPrincipal.API_EXTERNAL_USER, row.getPrincipalType());
        assertEquals("7:external-42", row.getPrincipalId());
        assertEquals(McpPrincipal.storageId(principal), row.getUserId());

        OAuthToken token = store.getToken(McpContext.none());
        assertEquals("access", token.accessToken());
        assertEquals("refresh", token.refreshToken());
        assertEquals(expiresAt.toInstant(), token.expiresAt().toInstant());
    }

    /** 同一服务的不同 principal 必须各存各的（token 不是按 user 隔离）。 */
    @Test
    void principalScopedTokensAreIsolated() {
        FakeOAuthRepository repo = new FakeOAuthRepository();
        TenantContext.Principal alice =
                new TenantContext.Principal(McpPrincipal.API_EXTERNAL_USER, "7:alice");
        TenantContext.Principal bob =
                new TenantContext.Principal(McpPrincipal.API_EXTERNAL_USER, "7:bob");

        new DbTokenStore(repo, 7, alice, "svc-1").saveToken(McpContext.none(),
                new OAuthToken("alice-token", "alice-refresh", "Bearer",
                        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));
        new DbTokenStore(repo, 7, bob, "svc-1").saveToken(McpContext.none(),
                new OAuthToken("bob-token", "bob-refresh", "Bearer",
                        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));

        assertEquals("alice-token", new DbTokenStore(repo, 7, alice, "svc-1")
                .getToken(McpContext.none()).accessToken());
        assertEquals("bob-token", new DbTokenStore(repo, 7, bob, "svc-1")
                .getToken(McpContext.none()).accessToken());
    }

    /**
     * 对照 Go {@code TestManagedTokenStoreLeavesRefreshToOAuthRuntime}：
     * 读出来的 token 必须<b>看不到</b>过期时间，而数据库里的真实过期时间必须还在
     * （运行期预检还要用它）。
     */
    @Test
    void managedTokenStoreLeavesRefreshToOAuthRuntime() {
        FakeOAuthRepository repo = new FakeOAuthRepository();
        TenantContext.Principal principal =
                new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1");
        ManagedTokenStore store = new ManagedTokenStore(repo, 7, principal, "svc-1");

        store.saveToken(McpContext.none(), new OAuthToken("access", "refresh", "Bearer",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));

        OAuthToken token = store.getToken(McpContext.none());
        assertNull(token.expiresAt(), "依赖库绝不能与 WeKnora 的协调刷新抢跑");

        McpOAuthToken row = repo.getTokenForPrincipal(7, principal, "svc-1");
        assertNotNull(row);
        assertNotNull(row.getExpiresAt(), "数据库必须保留真实过期时间供预检使用");
    }

    /** 未授权时抛 {@code ErrNoToken} 等价物（不能与仓储故障混淆）。 */
    @Test
    void missingTokenRaisesNoToken() {
        FakeOAuthRepository repo = new FakeOAuthRepository();
        DbTokenStore store = new DbTokenStore(repo, 7,
                new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1"), "svc-1");
        OAuthNoTokenException e = org.junit.jupiter.api.Assertions.assertThrows(
                OAuthNoTokenException.class, () -> store.getToken(McpContext.none()));
        assertEquals("no token available", e.getMessage());
        assertTrue(OAuthNoTokenException.isNoToken(new RuntimeException("wrapped", e)));
    }

    /**
     * 对照 Go {@code TestOAuthCacheKeyUsesPrincipalForOAuthServices}。
     *
     * <p>{@code cacheKey} 属于协议层（已翻译在 {@code McpClientManager}，包级私有），
     * 本包无法直接调用，故用反射对齐这条契约——它决定了 OAuth 服务"每个身份一条连接"。</p>
     */
    @Test
    void oauthCacheKeyUsesPrincipalForOAuthServices() throws Exception {
        McpService service = new McpService();
        service.setId("svc-1");
        McpAuthConfig authConfig = new McpAuthConfig();
        authConfig.setAuthType(McpAuthType.OAUTH);
        service.setAuthConfig(authConfig);

        TenantContext.Principal alice =
                new TenantContext.Principal(McpPrincipal.API_EXTERNAL_USER, "7:alice");
        TenantContext.Principal bob =
                new TenantContext.Principal(McpPrincipal.API_EXTERNAL_USER, "7:bob");

        assertNotEquals(cacheKey(service, alice), cacheKey(service, bob),
                "OAuth 服务必须按 principal 隔离连接");
        assertTrue(cacheKey(service, alice).contains(McpPrincipal.storageId(alice)));

        authConfig.setAuthType(McpAuthType.API_KEY);
        assertEquals("svc-1", cacheKey(service, alice));
        assertEquals(cacheKey(service, alice), cacheKey(service, bob),
                "非 OAuth 服务不按 principal 隔离");
    }

    /**
     * 反射调用 {@code com.ragagent.mcp.protocol.McpClientManager#cacheKey}
     * （包级私有；本包跨包无法直呼）。
     */
    private static String cacheKey(McpService service, TenantContext.Principal principal)
            throws Exception {
        Class<?> manager = Class.forName("com.ragagent.mcp.protocol.McpClientManager");
        Method method = manager.getDeclaredMethod("cacheKey", McpService.class,
                TenantContext.Principal.class);
        method.setAccessible(true);
        return (String) method.invoke(null, service, principal);
    }

    /** 对照 Go：非 OAuth 服务不会走 OAuth 装配（IsOAuth 判定）。 */
    @Test
    void nonOAuthServiceHasNoOAuthConfig() {
        McpService service = new McpService();
        assertFalse(service.getAuthConfig() != null && service.getAuthConfig().isOAuth());
        McpAuthConfig apiKey = new McpAuthConfig();
        apiKey.setAuthType(McpAuthType.API_KEY);
        service.setAuthConfig(apiKey);
        assertFalse(service.getAuthConfig().isOAuth());
    }
}
