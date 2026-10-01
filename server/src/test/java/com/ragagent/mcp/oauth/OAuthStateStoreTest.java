package com.ragagent.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpPrincipal;
import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/mcp/oauth_state_test.go。
 *
 * <p>核心两条语义（Go 注释原文精神）：
 * <ol>
 *   <li><b>进入回调 ≠ 授权完成</b>：{@code Take} 消费 state 只说明回调开始了，
 *       必须等 code 交换并落库 token 之后 {@code completeAttempt} 才置完成——
 *       否则同一服务上早已存在的旧 token 会让新弹窗"秒绿"；</li>
 *   <li><b>attempt 按 principal + service 隔离</b>：别的用户/别的服务拿同一个
 *       attemptID 来问，必须报不匹配而不是回 true。</li>
 * </ol>
 *
 * <p>两种后端（内存 / Redis）都跑同一组用例——Go 的 Lite 与多副本分支在 Java 侧是
 * {@code redis == null} 与否，行为必须一致。</p>
 */
class OAuthStateStoreTest {

    private static final long TENANT_ID = 7L;
    private static final TenantContext.Principal PRINCIPAL =
            new TenantContext.Principal(McpPrincipal.WEB_USER, "user-1");

    private static OAuthState state() {
        return new OAuthState(TENANT_ID, McpPrincipal.storageId(PRINCIPAL),
                OAuthState.Principal.of(PRINCIPAL), "service-1", "verifier", "client-1",
                "https://app.example.com/api/v1/mcp-oauth/callback", "/mcp-settings");
    }

    // ── 1. attempt 只在 code 交换后完成 ─────────────────────────────────

    /** 对照 Go {@code TestOAuthAttemptCompletesOnlyAfterCallbackExchange}。 */
    @Test
    void attemptCompletesOnlyAfterCallbackExchangeInMemory() {
        attemptCompletesOnlyAfterCallbackExchange(new OAuthStateStore(null));
    }

    /** 同一语义的 Redis 分支（GETDEL 的原子消费 + attempt 独立存活）。 */
    @Test
    void attemptCompletesOnlyAfterCallbackExchangeOnRedis() {
        FakeOAuthStateRedis redis = new FakeOAuthStateRedis();
        OAuthStateStore store = new OAuthStateStore(redis);
        attemptCompletesOnlyAfterCallbackExchange(store);
        assertEquals(OAuthStateStore.STATE_TTL, redis.lastTtl, "TTL 必须是 10 分钟");
        // 成对写入、各自独立：state 被 GETDEL 消费掉了，attempt 仍然在
        assertFalse(redis.contains(store.key("attempt-1")), "state 已被单次消费");
        assertTrue(redis.contains(store.attemptKey("attempt-1")), "attempt 必须独立存活");
    }

    private void attemptCompletesOnlyAfterCallbackExchange(OAuthStateStore store) {
        store.put("attempt-1", state());

        assertFalse(store.attempt("attempt-1").completed());

        // 消费 OAuth state == 提供方回调开始了；在 code 交换 + token 落库之前绝不能算成功
        OAuthState taken = store.take("attempt-1");
        assertEquals("service-1", taken.serviceId());
        assertFalse(store.attempt("attempt-1").completed());

        store.completeAttempt("attempt-1");
        OAuthAttempt attempt = store.attempt("attempt-1");
        assertTrue(attempt.completed());
        assertEquals(TENANT_ID, attempt.tenantId());
        assertEquals("web_user", attempt.principalOrNull().type());
        assertEquals("user-1", attempt.principalOrNull().id());
        assertEquals("service-1", attempt.serviceId());
    }

    // ── 2. state 单次使用 ──────────────────────────────────────────────

    /** 对照 Go {@code Take} 的"取出即删"：第二次必须报"不存在或已过期"。 */
    @Test
    void takeIsSingleUseInMemory() {
        takeIsSingleUse(new OAuthStateStore(null));
    }

    @Test
    void takeIsSingleUseOnRedis() {
        takeIsSingleUse(new OAuthStateStore(new FakeOAuthStateRedis()));
    }

    private void takeIsSingleUse(OAuthStateStore store) {
        store.put("s1", state());
        store.take("s1");
        assertThrows(OAuthStateNotFoundException.class, () -> store.take("s1"));
        assertThrows(OAuthStateNotFoundException.class,
                () -> store.attempt("unknown"), "未写过的 attempt 也必须报不存在");
    }

    /** 从未写过的 state 取用必须报错（对照 Go 的 {@code "oauth state not found or expired"}）。 */
    @Test
    void unknownStateIsNotFound() {
        OAuthStateStore store = new OAuthStateStore(null);
        OAuthStateNotFoundException e = assertThrows(
                OAuthStateNotFoundException.class, () -> store.take("nope"));
        assertEquals("oauth state not found or expired", e.getMessage());
    }

    // ── 3. 按 principal + service 隔离（经由 OAuthManager） ──────────────

    /** 对照 Go {@code TestAuthorizationAttemptStatusIsScopedToPrincipalAndService}。 */
    @Test
    void authorizationAttemptStatusIsScopedToPrincipalAndService() {
        OAuthManager manager = new OAuthManager(null, null, new OAuthStateStore(null));

        manager.states().put("attempt-1", state());
        manager.states().completeAttempt("attempt-1");

        assertTrue(manager.isAuthorizationAttemptComplete(
                TENANT_ID, PRINCIPAL, "service-1", "attempt-1"));

        OAuthProtocolException otherUser = assertThrows(OAuthProtocolException.class, () ->
                manager.isAuthorizationAttemptComplete(TENANT_ID,
                        new TenantContext.Principal(McpPrincipal.WEB_USER, "user-2"),
                        "service-1", "attempt-1"));
        assertTrue(otherUser.getMessage().contains("does not match"), otherUser.getMessage());

        OAuthProtocolException otherService = assertThrows(OAuthProtocolException.class, () ->
                manager.isAuthorizationAttemptComplete(TENANT_ID, PRINCIPAL, "service-2", "attempt-1"));
        assertTrue(otherService.getMessage().contains("does not match"), otherService.getMessage());

        OAuthProtocolException otherTenant = assertThrows(OAuthProtocolException.class, () ->
                manager.isAuthorizationAttemptComplete(8L, PRINCIPAL, "service-1", "attempt-1"));
        assertTrue(otherTenant.getMessage().contains("does not match"), otherTenant.getMessage());
    }

    /** attempt 在 Take 之后仍可查（这是它与 PKCE state 分开存储的全部理由）。 */
    @Test
    void attemptOutlivesStateOnRedis() {
        FakeOAuthStateRedis redis = new FakeOAuthStateRedis();
        OAuthStateStore store = new OAuthStateStore(redis);
        store.put("s1", state());
        store.take("s1");
        assertFalse(redis.contains(store.key("s1")), "state 被消费掉了");
        assertTrue(redis.contains(store.attemptKey("s1")), "attempt 必须还在");
        assertFalse(store.attempt("s1").completed());
    }

    // ── 4. §14.9p M5：blob 键名换锚 + 部署窗口兼容读 ─────────────────────

    /**
     * 写出去的 blob 是新键名（反证：旧下划线键一条不留）。
     *
     * <p>这两个记录只有这一种序列化出口（Redis/内存同一份 JSON），所以直接看字符串即可。
     */
    @Test
    void writesBlobsWithCamelCaseKeys() {
        FakeOAuthStateRedis redis = new FakeOAuthStateRedis();
        OAuthStateStore store = new OAuthStateStore(redis);
        store.put("s2", state());

        String stateBlob = redis.get(store.key("s2"));
        assertTrue(stateBlob.contains("\"serviceId\":\"service-1\""), stateBlob);
        assertTrue(stateBlob.contains("\"codeVerifier\":\"verifier\""), stateBlob);
        assertTrue(stateBlob.contains("\"frontendRedirect\":\"/mcp-settings\""), stateBlob);
        assertFalse(stateBlob.contains("service_id"), stateBlob);
        assertFalse(stateBlob.contains("code_verifier"), stateBlob);

        String attemptBlob = redis.get(store.attemptKey("s2"));
        assertTrue(attemptBlob.contains("\"serviceId\":\"service-1\""), attemptBlob);
        assertFalse(attemptBlob.contains("service_id"), attemptBlob);
    }

    /**
     * 部署窗口的兼容读：Redis 里可能还躺着旧（下划线）键名的 blob，必须按旧键读出来——
     * 不能静默变成 tenantId=0/serviceId="" 的空壳（{@code ignoreUnknown} 会吞掉旧键）。
     */
    @Test
    void readsLegacySnakeCaseBlobsDuringDeployWindow() {
        FakeOAuthStateRedis redis = new FakeOAuthStateRedis();
        OAuthStateStore store = new OAuthStateStore(redis);
        redis.set(store.key("legacy-1"),
                "{\"tenant_id\":7,\"user_id\":\"user-1\",\"principal\":{\"type\":\"web_user\","
                        + "\"id\":\"user-1\"},\"service_id\":\"service-1\",\"code_verifier\":\"verifier\","
                        + "\"client_id\":\"client-1\","
                        + "\"redirect_uri\":\"https://app.example.com/api/v1/mcp-oauth/callback\","
                        + "\"frontend_redirect\":\"/mcp-settings\"}",
                OAuthStateStore.STATE_TTL);
        redis.set(store.attemptKey("legacy-1"),
                "{\"tenant_id\":7,\"principal\":{\"type\":\"web_user\",\"id\":\"user-1\"},"
                        + "\"service_id\":\"service-1\",\"completed\":true}",
                OAuthStateStore.STATE_TTL);

        OAuthAttempt attempt = store.attempt("legacy-1");
        assertEquals(TENANT_ID, attempt.tenantId());
        assertEquals("service-1", attempt.serviceId());
        assertEquals("user-1", attempt.principalOrNull().id());
        assertTrue(attempt.completed());

        OAuthState taken = store.take("legacy-1");
        assertEquals(TENANT_ID, taken.tenantId());
        assertEquals("service-1", taken.serviceId());
        assertEquals("verifier", taken.codeVerifier());
        assertEquals("client-1", taken.clientId());
        assertEquals("/mcp-settings", taken.frontendRedirect());
    }
}
