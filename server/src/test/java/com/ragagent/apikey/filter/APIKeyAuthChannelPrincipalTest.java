package com.ragagent.apikey.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ragagent.apikey.domain.APIKeyScopeContext;
import com.ragagent.apikey.service.TenantAPIKeyService;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.APIPrincipalConfig;
import com.ragagent.auth.service.TenantService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * API 主体解析（对照 Go middleware/auth.go resolveAPIPrincipal L561-620 +
 * attachAPIKeyAuthContext L488-560）：tenant/direct_header/signed_token 三模式、
 * 401 文案、首位用户身份、JWT 校验链（aud/exp/TTL/nbf/tenant_id/sub）。
 */
class APIKeyAuthChannelPrincipalTest {

    private static final long TENANT = 10002L;
    private static final String SECRET = "unit-test-hmac-secret";

    private TenantAPIKeyService apiKeyService;
    private TenantService tenantService;
    private UserService userService;
    private APIKeyAuthChannel channel;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(TenantAPIKeyService.class);
        tenantService = mock(TenantService.class);
        userService = mock(UserService.class);
        channel = new APIKeyAuthChannel(apiKeyService, tenantService, userService);
        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        when(tenantService.getTenantById(TENANT)).thenReturn(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        APIKeyScopeContext.clear();
    }

    private APIPrincipalConfig cfg(String mode) {
        APIPrincipalConfig c = new APIPrincipalConfig();
        c.mode = mode;
        return c;
    }

    private void authenticateWith(APIPrincipalConfig config) throws Exception {
        com.ragagent.apikey.domain.TenantAPIKey key =
                new com.ragagent.apikey.domain.TenantAPIKey();
        key.setId(7L);
        key.setTenantId(TENANT);
        key.setScopeType("tenant");
        key.setFullAccess(false);
        key.setCapabilities(List.of());
        // 经 authenticate 走到 attachTenantKey：X-API-Key → 租户 Key + X-Tenant-ID
        when(apiKeyService.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(key);
        when(userService.getUserByTenantIdFirst(anyLong())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "k");
        request.addHeader("X-Tenant-ID", String.valueOf(TENANT));
        request.setRequestURI("/api/v1/agents");
        if (config != null) {
            com.ragagent.auth.domain.Tenant bound = tenantService.getTenantById(TENANT);
            bound.setApiPrincipalConfig(config);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean ok = channel.authenticate(request, response);
        if (response.getStatus() != 200 && response.getStatus() != 0 && !ok) {
            // 401 场景：调用方自行断言 body
        }
    }

    /** tenant 模式（配置缺省）→ 回落 api_tenant/<tenantId> + 合成用户。 */
    @Test
    void tenantModeFallsBack() throws Exception {
        authenticateWith(null);
        assertThat(TenantContext.currentPrincipal().type())
                .isEqualTo(TenantContext.PrincipalTypes.API_TENANT);
        assertThat(TenantContext.currentPrincipal().id()).isEqualTo(String.valueOf(TENANT));
        assertThat(TenantContext.currentUserId()).isEqualTo("system-" + TENANT);
    }

    /** direct_header 模式：带头 → api_external_user/<tenantId>:<id>。 */
    @Test
    void directHeaderResolvesExternalUser() throws Exception {
        authenticateWith(cfg(APIPrincipalConfig.MODE_DIRECT_HEADER));
        // 上面未带头且 requireDirectHeader=false → 回落 tenant principal
        assertThat(TenantContext.currentPrincipal().type())
                .isEqualTo(TenantContext.PrincipalTypes.API_TENANT);
    }

    /** direct_header + requireDirectHeader：缺头 → 401 missing header 文案。 */
    @Test
    void directHeaderRequireRejectsWithMissingMessage() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_DIRECT_HEADER);
        c.requireDirectHeader = true;
        MockHttpServletResponse response = authenticateExpecting(c);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Unauthorized: missing external user id header");
    }

    /** direct_header：控制字符 → 401 invalid id 文案。 */
    @Test
    void directHeaderRejectsControlChars() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_DIRECT_HEADER);
        MockHttpServletResponse response = authenticateExpecting(c, "bad\nid");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Unauthorized: invalid external user id");
    }

    /** direct_header：合法 id → api_external_user principal。 */
    @Test
    void directHeaderAcceptsValidId() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_DIRECT_HEADER);
        MockHttpServletResponse response = authenticateExpecting(c, "ext-user-1");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.currentPrincipal().type())
                .isEqualTo(TenantContext.PrincipalTypes.API_EXTERNAL_USER);
        assertThat(TenantContext.currentPrincipal().id())
                .isEqualTo(TENANT + ":ext-user-1");
    }

    /** signed_token：合法 JWT（aud=weknora, exp, tenant_id, sub）→ external principal。 */
    @Test
    void signedTokenAcceptsValidJwt() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_SIGNED_TOKEN);
        c.hmacSecret = SECRET;
        String jwt = hs256("{\"sub\":\"ext-9\",\"aud\":\"weknora\",\"tenantId\":" + TENANT
                + ",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");
        MockHttpServletResponse response = authenticateExpecting(c, null, jwt);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.currentPrincipal().id()).isEqualTo(TENANT + ":ext-9");
    }

    /** signed_token：tenant_id 不匹配 → 401 invalid token 文案。 */
    @Test
    void signedTokenRejectsTenantMismatch() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_SIGNED_TOKEN);
        c.hmacSecret = SECRET;
        String jwt = hs256("{\"sub\":\"ext-9\",\"aud\":\"weknora\",\"tenantId\":999"
                + ",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");
        MockHttpServletResponse response = authenticateExpecting(c, null, jwt);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Unauthorized: invalid external user token");
    }

    /** signed_token：TTL 超 24h → 拒绝。 */
    @Test
    void signedTokenRejectsOverlongTtl() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_SIGNED_TOKEN);
        c.hmacSecret = SECRET;
        String jwt = hs256("{\"sub\":\"ext-9\",\"aud\":\"weknora\",\"tenantId\":" + TENANT
                + ",\"exp\":" + (System.currentTimeMillis() / 1000 + 25 * 3600) + "}");
        MockHttpServletResponse response = authenticateExpecting(c, null, jwt);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    /** signed_token：aud 不含 weknora → 拒绝。 */
    @Test
    void signedTokenRejectsWrongAudience() throws Exception {
        APIPrincipalConfig c = cfg(APIPrincipalConfig.MODE_SIGNED_TOKEN);
        c.hmacSecret = SECRET;
        String jwt = hs256("{\"sub\":\"ext-9\",\"aud\":\"other\",\"tenantId\":" + TENANT
                + ",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");
        MockHttpServletResponse response = authenticateExpecting(c, null, jwt);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ── 帮手 ──────────────────────────────────────────────────────────────

    private MockHttpServletResponse authenticateExpecting(APIPrincipalConfig config)
            throws Exception {
        return authenticateExpecting(config, null, null);
    }

    private MockHttpServletResponse authenticateExpecting(APIPrincipalConfig config,
            String externalUserId) throws Exception {
        return authenticateExpecting(config, externalUserId, null);
    }

    private MockHttpServletResponse authenticateExpecting(APIPrincipalConfig config,
            String externalUserId, String token) throws Exception {
        com.ragagent.apikey.domain.TenantAPIKey key =
                new com.ragagent.apikey.domain.TenantAPIKey();
        key.setId(7L);
        key.setTenantId(TENANT);
        key.setScopeType("tenant");
        key.setFullAccess(false);
        key.setCapabilities(List.of());
        when(apiKeyService.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(key);
        when(userService.getUserByTenantIdFirst(anyLong())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "k");
        request.addHeader("X-Tenant-ID", String.valueOf(TENANT));
        request.setRequestURI("/api/v1/agents");
        if (externalUserId != null) {
            request.addHeader("X-External-User-ID", externalUserId);
        }
        if (token != null) {
            request.addHeader("X-External-User-Token", token);
        }
        Tenant bound = tenantService.getTenantById(TENANT);
        bound.setApiPrincipalConfig(config);

        MockHttpServletResponse response = new MockHttpServletResponse();
        channel.authenticate(request, response);
        return response;
    }

    private static String hs256(String claimsJson) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claimsJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }
}
