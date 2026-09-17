package com.ragagent.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.protocol.McpAuthorizationRequiredException;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpServiceUrls;
import com.ragagent.common.security.SsrfGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/oauth_lifecycle_test.go。
 *
 * <p>覆盖：过期刷新、永久失败删 token、临时失败保留 token、<b>刷新令牌轮换串行化</b>、
 * 401 只重试一次（经由 {@code isAuthorizationFailure} + 强制刷新语义）、过期行不算已授权。</p>
 *
 * <p><b>与 Go 测试的差异</b>：Go 的 fixture 直接把 token 塞进 fake 仓储；
 * Java 同样用内存 fake（{@link FakeOAuthRepository}），HTTP 侧用手写
 * {@link OAuthServerStub} 替代 {@code httptest.NewServer}。
 * SSRF 白名单按项目既有做法用 {@link SsrfGuard#reloadWhitelist} 放开 127.0.0.1
 * （Go 测试直接拿 {@code server.Client()} 绕过 SSRF）。</p>
 */
class OAuthLifecycleTest {

    private static final long TENANT_ID = 7L;
    private static final String SERVICE_ID = "svc-1";
    private static final TenantContext.Principal PRINCIPAL =
            new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1");

    private OAuthServerStub server;
    private FakeOAuthRepository repo;
    private OAuthRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        SsrfGuard guard = new SsrfGuard();
        guard.reloadWhitelist("127.0.0.1");
        McpServiceUrls.setSsrfGuard(guard);

        server = new OAuthServerStub();
        repo = new FakeOAuthRepository();
        repo.seed(PRINCIPAL, expiredRow());
        runtime = newRuntime(server.url("/metadata"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        McpServiceUrls.setSsrfGuard(new SsrfGuard());
    }

    // ── fixture ────────────────────────────────────────────────────────

    /** 对照 Go {@code newOAuthLifecycleFixture} 的初始行：已过期、带旧 refresh token。 */
    private static McpOAuthToken expiredRow() {
        McpOAuthToken row = new McpOAuthToken();
        row.setTenantId(TENANT_ID);
        row.setPrincipalType(PRINCIPAL.type());
        row.setPrincipalId(PRINCIPAL.id());
        row.setUserId(McpPrincipal.storageId(PRINCIPAL));
        row.setServiceId(SERVICE_ID);
        row.setAccessToken("old-access");
        row.setRefreshToken("old-refresh");
        row.setTokenType("Bearer");
        row.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        return row;
    }

    private OAuthRuntime newRuntime(String metadataUrl) {
        OAuthConfig cfg = new OAuthConfig()
                .clientId("client-1")
                .authServerMetadataUrl(metadataUrl)
                .tokenStore(new DbTokenStore(repo, TENANT_ID, PRINCIPAL, SERVICE_ID));
        return new OAuthRuntime(repo, TENANT_ID, PRINCIPAL, SERVICE_ID, server.url(), cfg);
    }

    // ── 用例 ───────────────────────────────────────────────────────────

    /** 对照 Go {@code TestOAuthRuntimeRefreshesExpiredToken}。 */
    @Test
    void refreshesExpiredToken() {
        server.tokenStatus = 200;
        server.tokenBody = Map.of(
                "access_token", "new-access",
                "refresh_token", "rotated-refresh",
                "token_type", "Bearer",
                "expires_in", 3600);

        runtime.ensureFresh(McpContext.none(), false, null);

        assertEquals(1, server.tokenRequests(), "恰好一次刷新请求");
        assertEquals("refresh_token", server.lastTokenForm().get("grant_type"));
        assertEquals("old-refresh", server.lastTokenForm().get("refresh_token"));

        McpOAuthToken row = repo.peek(PRINCIPAL, TENANT_ID, SERVICE_ID);
        assertNotNull(row);
        assertEquals("new-access", row.getAccessToken());
        assertEquals("rotated-refresh", row.getRefreshToken());
        assertTrue(row.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /** 对照 Go {@code TestOAuthRuntimeDeletesPermanentlyInvalidRefreshToken}。 */
    @Test
    void deletesPermanentlyInvalidRefreshToken() {
        server.tokenStatus = 400;
        server.tokenBody = Map.of("error", "invalid_grant",
                "error_description", "refresh token expired");

        OAuthReauthorizationRequiredException err = assertThrows(
                OAuthReauthorizationRequiredException.class,
                () -> runtime.ensureFresh(McpContext.none(), false, null));

        assertEquals("the refresh token or OAuth client is no longer valid", err.reason());
        assertEquals(1, server.tokenRequests());
        assertNull(repo.peek(PRINCIPAL, TENANT_ID, SERVICE_ID), "永久失败的 token 必须被删除");
    }

    /** 对照 Go {@code TestOAuthRuntimePreservesTokenOnTemporaryRefreshFailure}。 */
    @Test
    void preservesTokenOnTemporaryRefreshFailure() {
        server.tokenStatus = 503;
        server.tokenBody = Map.of("error", "temporarily_unavailable");

        assertThrows(OAuthRefreshTemporaryException.class,
                () -> runtime.ensureFresh(McpContext.none(), false, null));

        assertEquals(1, server.tokenRequests());
        McpOAuthToken row = repo.peek(PRINCIPAL, TENANT_ID, SERVICE_ID);
        assertNotNull(row, "临时失败的 token 必须保留");
        assertEquals("old-refresh", row.getRefreshToken());
    }

    /** 对照 Go {@code TestOAuthRuntimeSerializesRotatingRefreshToken}（12 个并发调用者）。 */
    @Test
    void serializesRotatingRefreshToken() throws Exception {
        server.tokenStatus = 200;
        server.tokenBody = Map.of(
                "access_token", "new-access",
                "refresh_token", "rotated-refresh",
                "token_type", "Bearer",
                "expires_in", 3600);

        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        runtime.ensureFresh(McpContext.none(), false, null);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();

            for (Future<Throwable> f : futures) {
                Throwable failure = f.get();
                assertNull(failure, () -> "并发保鲜不应失败：" + failure);
            }
        }

        assertEquals(1, server.tokenRequests(), "轮换型 refresh token 只能被消费一次");
        assertEquals("rotated-refresh",
                repo.peek(PRINCIPAL, TENANT_ID, SERVICE_ID).getRefreshToken());
    }

    /**
     * 对照 Go {@code TestOAuthRuntimeDoesNotExpireNonRefreshableTokenEarly}：
     * 没有 refresh token 的行，{@code skew} 不得缩短它的寿命。
     */
    @Test
    void doesNotExpireNonRefreshableTokenEarly() {
        FakeOAuthRepository fresh = new FakeOAuthRepository();
        McpOAuthToken row = new McpOAuthToken();
        row.setTenantId(TENANT_ID);
        row.setPrincipalType(PRINCIPAL.type());
        row.setPrincipalId(PRINCIPAL.id());
        row.setServiceId(SERVICE_ID);
        row.setAccessToken("access");
        // 10 秒后过期 —— 落在 30s 的 skew 窗口内，但还没真的过期
        row.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10));
        fresh.seed(PRINCIPAL, row);

        OAuthConfig cfg = new OAuthConfig().clientId("client-1");
        OAuthRuntime noRefreshRuntime = new OAuthRuntime(
                fresh, TENANT_ID, PRINCIPAL, SERVICE_ID, server.url(), cfg);

        noRefreshRuntime.ensureFresh(McpContext.none(), false, null);

        assertNotNull(fresh.peek(PRINCIPAL, TENANT_ID, SERVICE_ID),
                "不可刷新的行必须保留（skew 不得提前判死）");
        assertEquals(0, server.tokenRequests(), "没有任何 refresh 请求");
    }

    /** 对照 Go {@code TestTokenStatusDoesNotTreatExpiredRowAsAuthorized}。 */
    @Test
    void tokenStatusDoesNotTreatExpiredRowAsAuthorized() {
        OAuthToken expired = new OAuthToken("stale-access", "refresh", "Bearer",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));

        OAuthAuthorizationStatus status =
                OAuthAuthorizationStatus.of(expired, OffsetDateTime.now(ZoneOffset.UTC));
        assertFalse(status.authorized());
        assertEquals(OAuthAuthorizationStatus.STATE_REFRESHABLE, status.state());
        assertTrue(status.refreshAvailable());

        expired.setRefreshToken("");
        status = OAuthAuthorizationStatus.of(expired, OffsetDateTime.now(ZoneOffset.UTC));
        assertFalse(status.authorized());
        assertEquals(OAuthAuthorizationStatus.STATE_REAUTH_NEEDED, status.state());
    }

    /** 对照 Go：没有 token 行时保鲜直接要重新授权，且不碰上游。 */
    @Test
    void missingTokenRowRequiresReauthorization() {
        FakeOAuthRepository empty = new FakeOAuthRepository();
        OAuthRuntime emptyRuntime = new OAuthRuntime(
                empty, TENANT_ID, PRINCIPAL, SERVICE_ID, server.url(),
                new OAuthConfig().clientId("client-1"));

        OAuthReauthorizationRequiredException err = assertThrows(
                OAuthReauthorizationRequiredException.class,
                () -> emptyRuntime.ensureFresh(McpContext.none(), false, null));
        assertEquals("no token is stored", err.reason());
        assertEquals(0, server.tokenRequests());
    }

    /**
     * 对照 Go {@code TestOAuthCallRefreshesAndRetriesResource401Once} /
     * {@code TestOAuthCallDoesNotRetryMoreThanOnce} 的判定侧。
     *
     * <p>Java 的"只重试一次"由 {@code DefaultMcpClient.oauthCall} 的结构保证
     * （协议层已翻译，测试在 {@code McpClientProtocolTest}）。本用例钉住它依赖的
     * 两个运行时判定：<b>哪些异常算授权失败</b>，以及<b>强制刷新确实会刷新</b>——
     * 后者是"重试一次能成功"的前提。</p>
     */
    @Test
    void authorizationFailureDetectionAndForcedRefresh() {
        // 1. 只有授权类异常才算"该刷新了"
        assertTrue(runtime.isAuthorizationFailure(new McpAuthorizationRequiredException("")));
        assertTrue(runtime.isAuthorizationFailure(new OAuthAuthorizationRequiredException(runtime.handler())));
        assertTrue(runtime.isAuthorizationFailure(
                new OAuthReauthorizationRequiredException("no token is stored")));
        assertFalse(runtime.isAuthorizationFailure(
                new IllegalStateException("transport error: connection reset")));
        assertFalse(runtime.isAuthorizationFailure(null));

        // 2. 强制刷新：token 还有一小时寿命也会刷（这就是 401 后的那一次重试）
        McpOAuthToken fresh = FakeOAuthRepository.cloneToken(expiredRow());
        fresh.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
        repo.seed(PRINCIPAL, fresh);

        server.tokenStatus = 200;
        server.tokenBody = Map.of(
                "access_token", "new-access",
                "refresh_token", "rotated-refresh",
                "token_type", "Bearer",
                "expires_in", 3600);

        runtime.ensureFresh(McpContext.none(), false, null);
        assertEquals(0, server.tokenRequests(), "距过期还早，不该刷新");

        runtime.ensureFresh(McpContext.none(), true, null);
        assertEquals(1, server.tokenRequests(), "强制刷新必须真的去刷新");

        // 3. 触发异常里携带的 handler 会被用来刷新（对照 Go client.GetOAuthHandler）
        repo.seed(PRINCIPAL, expiredRow());
        runtime.ensureFresh(McpContext.none(), true,
                new OAuthAuthorizationRequiredException(runtime.handler()));
        assertEquals(2, server.tokenRequests());
    }
}
