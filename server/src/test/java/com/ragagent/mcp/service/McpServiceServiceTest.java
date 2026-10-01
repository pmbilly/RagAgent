package com.ragagent.mcp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.ragagent.TestSchema;
import com.ragagent.common.error.BizException;
import com.ragagent.common.security.SsrfGuard;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.mapper.McpOAuthRepository;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.McpClientManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对照 Go internal/application/service/mcp_service_test.go。
 *
 * <p>Go 用内存 fake 仓储断言"仓储里存了什么"；Java 用真 H2 断言"落库后读回来是什么"，
 * 顺带把 MyBatis 侧的部分列更新语义也钉住（这正是最容易翻错的地方）。</p>
 */
@SpringBootTest
class McpServiceServiceTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private McpServiceMapper mcpServiceMapper;
    @Autowired
    private McpOAuthRepository oauthRepo;
    @Autowired
    private McpMetadataService metadataService;
    @Autowired
    private SsrfGuard ssrfGuard;

    private McpClientManager clientManager;
    private McpServiceService svc;

    @BeforeEach
    void setUp() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        // 出站 URL 校验对测试域名放行（对照 Go 测试的 utils.SetSSRFWhitelistFromRaw("127.0.0.1")）
        ssrfGuard.reloadWhitelist("example.com,127.0.0.1");
        clientManager = mock(McpClientManager.class);
        svc = new McpServiceService(mcpServiceMapper, oauthRepo, metadataService, ssrfGuard,
                Optional.of(clientManager), Optional.empty());
    }

    private String seed(String apiKey, String token) {
        McpService s = new McpService();
        s.setId("svc-test");
        s.setTenantId(1L);
        s.setName("test");
        s.setDescription("before");
        s.setEnabled(true);
        s.setTransportType("sse");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setApiKey(apiKey);
        auth.setToken(token);
        s.setAuthConfig(auth);
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);
        s.setCreatedAt(ts);
        s.setUpdatedAt(ts);
        mcpServiceMapper.insert(s);
        return s.getId();
    }

    private McpService stored(String id) {
        return mcpServiceMapper.getByIdForTenant(1L, id);
    }

    private static McpService update(String id, String name, String description, Boolean enabled) {
        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        if (name != null) {
            u.setName(name);
        }
        u.setDescription(description);
        u.setEnabled(enabled != null && enabled);
        return u;
    }

    // ── UpdateMCPService：标量字段的存在性语义 ───────────────────────────

    static Stream<Arguments> scalarFieldPresenceCases() {
        return Stream.of(
                Arguments.of("description only", "test", "after", true,
                        fields("description"), "test", "after", true),
                Arguments.of("name only", "renamed", "before", true,
                        fields("name"), "renamed", "before", true),
                Arguments.of("explicit empty description", "test", "", true,
                        fields("description"), "test", "", true),
                Arguments.of("explicit disable", "test", "before", false,
                        fields("enabled"), "test", "before", false));
    }

    private static Map<String, Boolean> fields(String... keys) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, true);
        }
        return m;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scalarFieldPresenceCases")
    void updateRespectsScalarFieldPresence(String name, String updateName, String updateDescription,
                                           boolean updateEnabled, Map<String, Boolean> updateFields,
                                           String wantName, String wantDescription,
                                           boolean wantEnabled) {
        String id = seed("stored-api", "stored-token");

        svc.updateMCPService(update(id, updateName, updateDescription, updateEnabled), updateFields);

        McpService got = stored(id);
        assertEquals(wantName, got.getName());
        assertEquals(wantDescription, got.getDescription());
        assertEquals(wantEnabled, got.isEnabled());
    }

    @Test
    void appliesNonScalarUpdateWithoutName() {
        String id = seed("stored-api", "stored-token");
        McpService before = stored(id);
        before.setUrl("https://example.com/before");
        mcpServiceMapper.updatePartial(before);

        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        u.setUrl("https://example.com/after");
        svc.updateMCPService(u, null);

        McpService got = stored(id);
        assertNotNull(got.getUrl());
        assertEquals("https://example.com/after", got.getUrl());
        assertEquals("test", got.getName(), "未提供的标量字段保持不变");
        assertEquals("before", got.getDescription());
        assertTrue(got.isEnabled());
    }

    @Test
    void doesNotTouchSecretsEvenIfPassed() {
        String id = seed("stored-api", "stored-token");

        McpService hostile = new McpService();
        hostile.setId(id);
        hostile.setTenantId(1L);
        hostile.setName("renamed");
        hostile.setEnabled(true);
        hostile.setTransportType("sse");
        // 敌对请求体：试图用主 PUT 覆盖两个秘密字段
        McpAuthConfig auth = new McpAuthConfig();
        auth.setApiKey("should-not-overwrite");
        auth.setToken("should-not-overwrite-either");
        hostile.setAuthConfig(auth);

        svc.updateMCPService(hostile, fields("name", "enabled"));

        McpService got = stored(id);
        assertEquals("stored-api", got.getAuthConfig().getApiKey(),
                "主 PUT 在任何情况下都不得覆盖已存的 APIKey");
        assertEquals("stored-token", got.getAuthConfig().getToken());
        assertEquals("renamed", got.getName(), "非秘密字段的更新照常生效");
    }

    @Test
    void customHeadersPreserveOnNil() {
        String id = seed("stored-api", "stored-token");
        McpService before = stored(id);
        before.getAuthConfig().setCustomHeaders(new LinkedHashMap<>(Map.of("X-Tenant", "acme")));
        mcpServiceMapper.updatePartial(before);

        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        u.setAuthConfig(new McpAuthConfig()); // CustomHeaders 为 nil → 保持
        svc.updateMCPService(u, null);

        assertEquals("acme", stored(id).getAuthConfig().getCustomHeaders().get("X-Tenant"),
                "请求里 CustomHeaders 为 nil 时必须保留既有自定义头");
    }

    @Test
    void customHeadersReplaceOnNonNil() {
        String id = seed("stored-api", "stored-token");
        McpService before = stored(id);
        before.getAuthConfig().setCustomHeaders(new LinkedHashMap<>(Map.of("X-Tenant", "acme")));
        mcpServiceMapper.updatePartial(before);

        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        McpAuthConfig auth = new McpAuthConfig();
        auth.setCustomHeaders(new LinkedHashMap<>(Map.of("X-Replaced", "yes")));
        u.setAuthConfig(auth);
        svc.updateMCPService(u, null);

        assertEquals(Map.of("X-Replaced", "yes"),
                stored(id).getAuthConfig().getCustomHeaders(),
                "非 nil CustomHeaders 必须整体替换既有 map");
    }

    @Test
    void disablingServiceClosesActiveConnection() {
        String id = seed("stored-api", "stored-token");
        svc.updateMCPService(update(id, null, "before", false), fields("enabled"));
        verify(clientManager, times(1)).closeClient(id);
    }

    @Test
    void urlChangeClosesActiveConnection() {
        String id = seed("stored-api", "stored-token");
        McpService before = stored(id);
        before.setUrl("https://example.com/before");
        mcpServiceMapper.updatePartial(before);

        McpService u = new McpService();
        u.setId(id);
        u.setTenantId(1L);
        u.setUrl("https://example.com/after");
        svc.updateMCPService(u, null);

        verify(clientManager, times(1)).closeClient(id);
    }

    // ── UpdateMCPCredentials ────────────────────────────────────────────

    @Test
    void updateCredentialsWritesApiKey() {
        String id = seed("", "");

        McpService got = svc.updateMCPCredentials(1L, id, "fresh-api-key", null);

        assertNotNull(got.getAuthConfig());
        assertEquals("fresh-api-key", got.getAuthConfig().getApiKey());
        assertEquals("", got.getAuthConfig().getToken(), "未触碰的字段保持原样");
        assertEquals("fresh-api-key", stored(id).getAuthConfig().getApiKey(), "已落库");
        verify(clientManager, times(1)).closeClient(id);
    }

    @Test
    void updateCredentialsNilPointerIsNoop() {
        String id = seed("stored-api", "stored-token");

        McpService got = svc.updateMCPCredentials(1L, id, null, null);

        assertEquals("stored-api", got.getAuthConfig().getApiKey());
        assertEquals("stored-token", got.getAuthConfig().getToken());
        verify(clientManager, never()).closeClient(id);
    }

    @Test
    void updateCredentialsEmptyStringIsNoop() {
        String id = seed("stored-api", "stored-token");

        McpService got = svc.updateMCPCredentials(1L, id, "", "");

        assertEquals("stored-api", got.getAuthConfig().getApiKey(),
                "空串按空操作处理；清空走 ClearMCPCredential");
        assertEquals("stored-token", got.getAuthConfig().getToken());
        verify(clientManager, never()).closeClient(id);
    }

    @Test
    void updateCredentialsSameValueIsNoop() {
        String id = seed("stored-api", "stored-token");

        svc.updateMCPCredentials(1L, id, "stored-api", "stored-token");

        verify(clientManager, never()).closeClient(id);
    }

    @Test
    void updateCredentialsReplacesExisting() {
        String id = seed("old-api", "old-token");

        McpService got = svc.updateMCPCredentials(1L, id, "new-api", "new-tok");

        assertEquals("new-api", got.getAuthConfig().getApiKey());
        assertEquals("new-tok", got.getAuthConfig().getToken());
    }

    @Test
    void updateCredentialsRejectsBuiltin() {
        String id = seed("stored-api", "");
        // is_builtin 不在 Update 的列集里（Go 亦然），只能直接改库
        markBuiltin(id);

        BizException e = assertThrows(BizException.class,
                () -> svc.updateMCPCredentials(1L, id, "anything", null));
        assertTrue(e.getMessage().toLowerCase().contains("builtin"));
        verify(clientManager, never()).closeClient(id);
    }

    @Test
    void updateCredentialsServiceNotFound() {
        assertThrows(BizException.class,
                () -> svc.updateMCPCredentials(1L, "nope", "x", null));
    }

    // ── ClearMCPCredential ──────────────────────────────────────────────

    @Test
    void clearCredentialClearsApiKey() {
        String id = seed("stored-api", "stored-token");

        svc.clearMCPCredential(1L, id, "apiKey");

        McpService got = stored(id);
        assertEquals("", got.getAuthConfig().getApiKey());
        assertEquals("stored-token", got.getAuthConfig().getToken(), "另一个字段不动");
        verify(clientManager, times(1)).closeClient(id);
    }

    @Test
    void clearCredentialClearsToken() {
        String id = seed("stored-api", "stored-token");

        svc.clearMCPCredential(1L, id, "token");

        McpService got = stored(id);
        assertEquals("stored-api", got.getAuthConfig().getApiKey());
        assertEquals("", got.getAuthConfig().getToken());
    }

    @Test
    void clearCredentialIdempotentOnEmpty() {
        String id = seed("stored-api", "");

        svc.clearMCPCredential(1L, id, "token");

        McpService got = stored(id);
        assertEquals("stored-api", got.getAuthConfig().getApiKey());
        assertEquals("", got.getAuthConfig().getToken());
        verify(clientManager, never()).closeClient(id);
    }

    @Test
    void clearCredentialUnknownFieldErrors() {
        String id = seed("stored-api", "");

        BizException e = assertThrows(BizException.class,
                () -> svc.clearMCPCredential(1L, id, "bogus"));
        assertTrue(e.getMessage().contains("unknown"));
    }

    @Test
    void clearCredentialRejectsBuiltin() {
        String id = seed("stored-api", "");
        markBuiltin(id);

        BizException e = assertThrows(BizException.class,
                () -> svc.clearMCPCredential(1L, id, "apiKey"));
        assertTrue(e.getMessage().toLowerCase().contains("builtin"));
    }

    private void markBuiltin(String id) {
        jdbc.update("UPDATE mcp_services SET is_builtin = TRUE WHERE id = ?", id);
    }

    // ── Get/List 返回未脱敏实体（脱敏是 DTO 的职责） ─────────────────────

    @Test
    void getReturnsRawCredentials() {
        String id = seed("real-api", "real-token");

        McpService got = svc.getMCPServiceByID(1L, id);

        assertNotNull(got.getAuthConfig());
        assertEquals("real-api", got.getAuthConfig().getApiKey(),
                "service 层返回未脱敏凭据；脱敏由 DTO 构造期保证");
        assertEquals("real-token", got.getAuthConfig().getToken());
    }

    @Test
    void listReturnsRawCredentials() {
        seed("real-api", "real-token");

        List<McpService> got = svc.listMCPServices(1L);

        assertEquals(1, got.size());
        assertEquals("real-api", got.get(0).getAuthConfig().getApiKey());
        assertEquals("real-token", got.get(0).getAuthConfig().getToken());
    }

    @Test
    void getByIdHidesOtherTenantsRows() {
        String id = seed("real-api", "real-token");
        assertThrows(BizException.class, () -> svc.getMCPServiceByID(2L, id));
    }

    @Test
    void emptyIdsShortCircuits() {
        assertEquals(0, svc.listMCPServicesByIDs(1L, List.of()).size());
        assertEquals(0, svc.listMCPServicesByIDs(1L, null).size());
    }

    @Test
    void createRejectsStdioAndDefaultsAdvancedConfig() {
        McpService stdio = new McpService();
        stdio.setTenantId(1L);
        stdio.setName("n");
        stdio.setTransportType("stdio");
        assertThrows(BizException.class, () -> svc.createMCPService(stdio));

        McpService ok = new McpService();
        ok.setTenantId(1L);
        ok.setName("n2");
        ok.setTransportType("sse");
        svc.createMCPService(ok);
        assertNotNull(ok.getAdvancedConfig());
        assertEquals(30, ok.getAdvancedConfig().getTimeout());
        assertEquals(3, ok.getAdvancedConfig().getRetryCount());
        assertEquals(1, ok.getAdvancedConfig().getRetryDelay());
        assertNotNull(ok.getId());
        assertFalse(ok.getId().isEmpty());
    }
}
