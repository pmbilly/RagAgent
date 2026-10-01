package com.ragagent.mcp.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpAuthType;
import com.ragagent.mcp.domain.McpMetadataSummary;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpStdioConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * DTO 层的核心保证是**结构性的**：序列化后的响应体在任何情况下都不得出现
 * api_key / token，无论底层实体是什么状态。
 *
 * <p>对照 Go {@code internal/handler/dto/mcp_test.go} 的五条测试，逐条翻译。
 * Go 断言的是序列化后的 JSON（而不只是结构体形状），Java 侧同样如此——
 * 只有走一遍真实序列化才能发现"某个下游的反射式自定义序列化器又把密钥带回来"。</p>
 */
class McpServiceResponseTest {

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void asAdmin() {
        TenantContext.set(1L, null, "admin", false, "u-admin", false);
    }

    private static void asViewer() {
        TenantContext.set(1L, null, "viewer", false, "u-viewer", false);
    }

    // ── 一、OmitsSecrets ─────────────────────────────────────────────────

    @Test
    void omitsSecrets() throws Exception {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-1");
        svc.setName("svc");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setApiKey("sk-real-api-key-do-not-leak");
        auth.setToken("tok-real-bearer-token-do-not-leak");
        auth.setCustomHeaders(new LinkedHashMap<>(Map.of("X-Trace", "abc")));
        svc.setAuthConfig(auth);

        String s = JSON.writeValueAsString(McpServiceResponse.from(svc, true));

        assertFalse(s.contains("sk-real-api-key-do-not-leak"),
                "原始 api_key 绝不能出现在 McpServiceResponse 里");
        assertFalse(s.contains("tok-real-bearer-token-do-not-leak"),
                "原始 token 绝不能出现在 McpServiceResponse 里");

        // auth_config 子对象里不该再有 api_key / token 键
        JsonNode raw = JSON.readTree(s);
        if (raw.has("authConfig")) {
            String ac = raw.get("authConfig").toString();
            assertFalse(ac.contains("\"apiKey\""), "auth_config 不得含 api_key：" + ac);
            assertFalse(ac.contains("\"token\""), "auth_config 不得含 token：" + ac);
        }

        // credentials 是有意暴露的"是否已配置"布尔值（取代独立的 GET /credentials 端点）
        assertTrue(s.contains("\"credentials\""), s);
        assertTrue(s.contains("\"apiKey\":{\"configured\":true}"), s);
        assertTrue(s.contains("\"token\":{\"configured\":true}"), s);

        // CustomHeaders 是结构性元数据，**应该**透出
        assertTrue(s.contains("\"customHeaders\""), s);
        assertTrue(s.contains("\"X-Trace\""), s);
    }

    // ── 二、BuiltinStripsTenantConfig ────────────────────────────────────

    @Test
    void builtinStripsTenantConfig() throws Exception {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("builtin-1");
        svc.setIsBuiltin(true);
        svc.setUrl("https://tenant-private.example.com");
        svc.setHeaders(new LinkedHashMap<>(Map.of("X-Tenant-Secret", "shhh")));
        McpAuthConfig auth = new McpAuthConfig();
        auth.setApiKey("should-not-leak-via-builtin");
        svc.setAuthConfig(auth);

        McpServiceResponse resp = McpServiceResponse.from(svc, true);

        assertNull(resp.getUrl(), "内置服务不得泄漏按租户的 URL");
        assertNull(resp.getHeaders(), "内置服务不得泄漏按租户的 headers");
        assertNull(resp.getAuthConfig(), "内置服务不得泄漏 auth_config");
        assertNull(resp.getCredentials(), "内置服务不返回 credentials（它们没有按租户凭据）");

        String body = JSON.writeValueAsString(resp);
        assertFalse(body.contains("should-not-leak-via-builtin"), body);
        assertFalse(body.contains("X-Tenant-Secret"), body);
    }

    // ── 三、ViewerStripsIntegrationDetail ───────────────────────────────

    @Test
    void viewerStripsIntegrationDetail() throws Exception {
        asViewer();
        McpService svc = new McpService();
        svc.setId("svc-2");
        svc.setUrl("https://tenant-private.example.com");
        svc.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "Bearer secret")));
        svc.setEnvVars(new LinkedHashMap<>(Map.of("TOKEN", "secret")));
        McpStdioConfig stdio = new McpStdioConfig();
        stdio.setCommand("npx");
        stdio.setArgs(List.of("-y", "mcp-server"));
        svc.setStdioConfig(stdio);
        svc.setAdvancedConfig(com.ragagent.mcp.domain.McpAdvancedConfig.defaults());
        McpAuthConfig auth = new McpAuthConfig();
        auth.setCustomHeaders(new LinkedHashMap<>(Map.of("X-Auth", "secret")));
        svc.setAuthConfig(auth);

        McpServiceResponse resp = McpServiceResponse.from(svc, false);

        assertNull(resp.getUrl());
        assertNull(resp.getHeaders());
        assertNull(resp.getEnvVars());
        assertNull(resp.getStdioConfig());
        assertNull(resp.getAdvancedConfig());
        assertNotNull(resp.getAuthConfig());
        assertNull(resp.getAuthConfig().customHeaders());
    }

    /** 反向对照：Admin 应看到完整集成细节（Viewer 被剥离的那些字段） */
    @Test
    void adminKeepsIntegrationDetail() {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-3");
        svc.setUrl("https://tenant-private.example.com");
        svc.setAdvancedConfig(com.ragagent.mcp.domain.McpAdvancedConfig.defaults());
        McpAuthConfig auth = new McpAuthConfig();
        auth.setCustomHeaders(new LinkedHashMap<>(Map.of("X-Auth", "abc")));
        svc.setAuthConfig(auth);

        McpServiceResponse resp = McpServiceResponse.from(svc, true);

        assertEquals("https://tenant-private.example.com", resp.getUrl());
        assertNotNull(resp.getAdvancedConfig());
        assertEquals("abc", resp.getAuthConfig().customHeaders().get("X-Auth"));
    }

    /** 内置服务在 Admin 视角下 advanced_config 会被保留——照抄 Go 的剥离清单，别"顺手修好" */
    @Test
    void builtinKeepsAdvancedConfigForAdmin() {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("builtin-2");
        svc.setIsBuiltin(true);
        svc.setAdvancedConfig(com.ragagent.mcp.domain.McpAdvancedConfig.defaults());

        McpServiceResponse resp = McpServiceResponse.from(svc, true);

        assertNotNull(resp.getAdvancedConfig(),
                "Go 的内置剥离清单不含 AdvancedConfig，此处必须保持一致");
    }

    // ── 四、NilSafe ─────────────────────────────────────────────────────

    @Test
    void nilSafe() {
        asAdmin();
        assertNull(McpServiceResponse.from(null, true));
        assertEquals(0, McpServiceResponse.listOf(null, true).size());
    }

    // ── 五、AttachMCPCatalogs ───────────────────────────────────────────

    @Test
    void attachCatalogsMarksStaleWithoutCopyingTools() throws Exception {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-1");
        svc.setTransportType("sse");
        List<McpService> services = List.of(svc);
        List<McpServiceResponse> resp = McpServiceResponse.listOf(services, true);

        McpMetadataSummary summary = new McpMetadataSummary();
        summary.setServiceId("svc-1");
        summary.setToolCount(3);
        summary.setConfigFingerprint("old");
        summary.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        McpServiceResponse.attachCatalogs(resp, services, Map.of("svc-1", summary));

        assertNotNull(resp.get(0).getCatalog(), "目录摘要应挂到列表卡片上");
        assertEquals(3, resp.get(0).getCatalog().toolCount());
        assertTrue(resp.get(0).getCatalog().stale(), "指纹不一致 → stale");

        String body = JSON.writeValueAsString(resp.get(0));
        assertFalse(body.contains("\"tools\""), "列表卡片不得带工具本体：" + body);
        assertTrue(body.contains("\"toolCount\":3"), body);
    }

    // ── 补充：字段名与 OAuth 非秘密配置的回显 ────────────────────────────

    @Test
    void oauthNonSecretConfigIsEchoedBack() throws Exception {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-4");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setAuthType(McpAuthType.OAUTH);
        auth.setScopes(List.of("read", "write"));
        auth.setAuthServerMetadataUrl("https://auth.example.com/.well-known/oauth-authorization-server");
        auth.setApiKey("leak-me-not");
        svc.setAuthConfig(auth);

        String body = JSON.writeValueAsString(McpServiceResponse.from(svc, true));

        assertTrue(body.contains("\"authType\":\"oauth\""), body);
        assertTrue(body.contains("\"scopes\":[\"read\",\"write\"]"), body);
        assertTrue(body.contains("oauth-authorization-server"), body);
        assertFalse(body.contains("leak-me-not"), body);
    }

    /** Go 的 omitempty：NONE 的空串 auth_type 不输出 */
    @Test
    void emptyAuthTypeIsOmitted() {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-5");
        McpAuthConfig auth = new McpAuthConfig();
        auth.setAuthType(McpAuthType.NONE);
        svc.setAuthConfig(auth);

        McpServiceResponse resp = McpServiceResponse.from(svc, true);

        assertNull(resp.getAuthConfig().authType(), "空串 auth_type 必须省略（Go omitempty）");
    }

    /** 字段序 = Go struct 声明序（usage_instructions 在首位，别按字母序） */
    @Test
    void fieldOrderMatchesGoStruct() throws Exception {
        asAdmin();
        McpService svc = new McpService();
        svc.setId("svc-6");
        // 时间戳非空才会出现（类级 NON_NULL 是授权剥离的实现，空值一并省略）
        svc.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        svc.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        String body = JSON.writeValueAsString(McpServiceResponse.from(svc, true));

        int usage = body.indexOf("\"usageInstructions\"");
        int id = body.indexOf("\"id\"");
        int builtin = body.indexOf("\"builtin\"");
        int created = body.indexOf("\"createdAt\"");
        assertTrue(usage >= 0 && usage < id, "usageInstructions 必须排在 id 之前：" + body);
        assertTrue(id < builtin && builtin < created, body);
    }

    /** credentials 映射的键序（apiKey < token 插入序）；§14.9n M1 起无信封 */
    @Test
    void credentialsResponseFieldOrder() throws Exception {
        String body = JSON.writeValueAsString(CredentialsResponse.of(true, false));
        assertEquals("{\"fields\":{\"apiKey\":{\"configured\":true},\"token\":{\"configured\":false}}}",
                body);
    }
}
