package com.ragagent.mcp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.ragagent.TestSchema;
import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.mapper.McpOAuthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/repository/mcp_oauth_test.go。
 *
 * <p>覆盖三件事：principal 隔离、历史 (tenant,user,service) 兼容、refresh 租约单所有者。</p>
 */
@SpringBootTest
class McpOAuthRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private McpOAuthRepository repo;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private static OffsetDateTime inAnHour() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    }

    @Test
    void tokenForPrincipalIsolated() {
        TenantContext.Principal web = new TenantContext.Principal(McpPrincipal.WEB_USER, "u1");
        TenantContext.Principal api = new TenantContext.Principal(
                McpPrincipal.API_EXTERNAL_USER, apiExternalId());

        repo.saveTokenForPrincipal(token(7, web, "svc1", "web-token", "web-refresh"));
        repo.saveTokenForPrincipal(token(7, api, "svc1", "api-token", "api-refresh"));

        McpOAuthToken webToken = repo.getTokenForPrincipal(7, web, "svc1");
        assertNotNull(webToken);
        assertEquals("web-token", webToken.getAccessToken());

        McpOAuthToken apiToken = repo.getTokenForPrincipal(7, api, "svc1");
        assertNotNull(apiToken);
        assertEquals("api-token", apiToken.getAccessToken(),
                "同一服务的不同 principal 必须各存各的 token");
    }

    /** 对照 Go 测试里的 {@code PrincipalAPIExternalUser, "7:external-u1"} */
    private static String apiExternalId() {
        return "7:external-u1";
    }

    private static McpOAuthToken token(long tenantId, TenantContext.Principal principal,
                                       String serviceId, String access, String refresh) {
        McpOAuthToken t = new McpOAuthToken();
        t.setTenantId(tenantId);
        t.setUserId(McpPrincipal.storageId(principal));
        t.setPrincipalType(principal.type());
        t.setPrincipalId(principal.id());
        t.setServiceId(serviceId);
        t.setAccessToken(access);
        t.setRefreshToken(refresh);
        t.setTokenType("Bearer");
        t.setExpiresAt(inAnHour());
        return t;
    }

    @Test
    void legacyUserTokenUsesWebPrincipal() {
        McpOAuthToken legacy = new McpOAuthToken();
        legacy.setTenantId(7L);
        legacy.setUserId("u1");
        legacy.setServiceId("svc1");
        legacy.setAccessToken("legacy-token");
        legacy.setRefreshToken("legacy-refresh");
        legacy.setTokenType("Bearer");
        legacy.setExpiresAt(inAnHour());
        repo.saveToken(legacy);

        McpOAuthToken token = repo.getTokenForPrincipal(7,
                new TenantContext.Principal(McpPrincipal.WEB_USER, "u1"), "svc1");
        assertNotNull(token);
        assertEquals("legacy-token", token.getAccessToken());
    }

    @Test
    void refreshLeaseHasSingleOwner() {
        TenantContext.Principal principal = new TenantContext.Principal(McpPrincipal.WEB_USER, "u1");
        McpOAuthToken t = token(7, principal, "svc1", "access", "refresh");
        t.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        repo.saveTokenForPrincipal(t);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        assertTrue(repo.tryAcquireTokenRefreshLease(7, principal, "svc1", "lease-1",
                now.plusMinutes(1)), "过期租约可被抢占");
        assertFalse(repo.tryAcquireTokenRefreshLease(7, principal, "svc1", "lease-2",
                now.plusMinutes(1)), "已被他人持有期间不得再抢");

        // 非持有者释放不掉当前持有者的租约
        repo.releaseTokenRefreshLease(7, principal, "svc1", "lease-2");
        assertFalse(repo.tryAcquireTokenRefreshLease(7, principal, "svc1", "lease-2",
                now.plusMinutes(1)), "非持有者的释放必须是 no-op");

        repo.releaseTokenRefreshLease(7, principal, "svc1", "lease-1");
        assertTrue(repo.tryAcquireTokenRefreshLease(7, principal, "svc1", "lease-2",
                now.plusMinutes(1)), "持有者释放后可以重新抢占");
    }

    @Test
    void saveTokenForPrincipalRequiresPrincipal() {
        McpOAuthToken t = new McpOAuthToken();
        t.setTenantId(7L);
        t.setServiceId("svc1");
        t.setAccessToken("a");
        try {
            repo.saveTokenForPrincipal(t);
            org.junit.jupiter.api.Assertions.fail("缺 principal 必须被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("principal_type"));
        }
    }
}
