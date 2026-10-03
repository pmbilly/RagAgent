package com.ragagent.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 四个 API Key 管理端点的 HTTP 契约测试——对照 Go
 * internal/handler/tenant.go 的 List/Create/Update/DeleteAPIKey（L660-782）。
 *
 * <p>没有 golden 文件（这四条端点尚未在 Go dev server 上录制），
 * 因此这里用**逐字段 + 键序**的结构化断言钉住契约：
 * {@code createdAt} 之后的 {@code token} 位置、以及 {@code DELETE} 的 204（§2.1：无信封）。</p>
 *
 * <p>注意：本测试**不**覆盖"API Key 主体被门禁拒绝"（那需要 WebConfig 注册
 * {@code APIKeyGateInterceptor}，属主会话接线范围）；该语义由
 * {@code APIKeyRouteAuthorizerTest.tenantApiKeyManagementPathsStayDefaultDeny} 在
 * 授权层钉住。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantAPIKeyControllerTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 17, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));
    private static final long TENANT_ID = 42100L;
    private static final long OTHER_TENANT_ID = 42200L;
    private static final String LOGIN_EMAIL = "apikey-test@weknora.test";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private TenantMemberMapper memberMapper;
    @Autowired
    private KnowledgeBaseMapper kbMapper;

    private String token;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        insertTenant(TENANT_ID, "apikey-test-tenant");
        insertTenant(OTHER_TENANT_ID, "apikey-other-tenant");

        User user = new User();
        user.setId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001");
        user.setUsername("apikeytest");
        user.setEmail(LOGIN_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT_ID);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(user.getId());
        member.setTenantId(TENANT_ID);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(TS);
        memberMapper.insert(member);

        insertKb("kb-1", TENANT_ID);
        insertKb("kb-2", TENANT_ID);
        insertKb("kb-foreign", OTHER_TENANT_ID);

        token = login(LOGIN_EMAIL);
    }

    private void insertTenant(long id, String name) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setName(name);
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
    }

    private void insertKb(String id, long tenantId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setTenantId(tenantId);
        kb.setName("kb-" + id);
        kb.setType("document");
        kb.setCreatedAt(TS);
        kb.setUpdatedAt(TS);
        kbMapper.insert(kb);
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("login 响应应含 token: %s", body).isTrue();
        return m.group(1);
    }

    // ── 创建 ──

    @Test
    void createScopedKeyReturns201WithOneTimeToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"integration\",\"fullAccess\":false,"
                                + "\"capabilities\":[\"retrieve\",\"chat\",\"retrieve\"]}"))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(keyOrder(body)).startsWith("id", "scopeType");

        JsonNode data = MAPPER.readTree(body);
        // 字段声明序 + 末尾 token；lastUsedAt / expiresAt 显式 null（恒在）
        assertThat(fieldNames(data)).containsExactly(
                "id", "scopeType", "name", "apiKey", "fullAccess",
                "knowledgeBaseIds", "capabilities", "lastUsedAt", "expiresAt",
                "createdAt", "token");

        assertThat(data.get("scopeType").asText()).isEqualTo("tenant");
        assertThat(data.get("name").asText()).isEqualTo("integration");
        assertThat(data.get("fullAccess").asBoolean()).isFalse();
        // scoped Key 未指定 KB → 空数组（不是 null）
        assertThat(data.get("knowledgeBaseIds").isArray()).isTrue();
        assertThat(data.get("knowledgeBaseIds")).isEmpty();
        // 能力去重且顺序保留首次出现
        assertThat(toList(data.get("capabilities"))).containsExactly("retrieve", "chat");
        assertThat(data.get("createdAt").isTextual()).isTrue();

        // 明文 token 只在创建时返回一次，且与 api_key 一致
        String tokenPlain = data.get("token").asText();
        assertThat(tokenPlain).startsWith("sk-");
        assertThat(data.get("apiKey").asText()).isEqualTo(tokenPlain);
        assertThat(data.get("id").asLong()).isPositive();
    }

    @Test
    void createFullAccessKeyEmitsNullKbListAndEmptyCapabilities() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"owner\",\"fullAccess\":true,"
                                + "\"knowledgeBaseIds\":[\"kb-1\"],\"capabilities\":[\"retrieve\"]}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(data.get("fullAccess").asBoolean()).isTrue();
        // ★ 刻意的不对称：full-access 时 knowledgeBaseIds 是 **null**，
        //   而 capabilities 经 NormalizeAPIKeyCapabilities 变成 **[]**
        assertThat(data.get("knowledgeBaseIds").isNull()).isTrue();
        assertThat(data.get("capabilities").isArray()).isTrue();
        assertThat(data.get("capabilities")).isEmpty();
    }

    @Test
    void createRejectsScopedKeyWithoutCapabilities() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"no-caps\",\"fullAccess\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,"
                                + "\"message\":\"capabilities are required for scoped API keys\","
                                + "\"details\":null}}"));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"   \",\"fullAccess\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,\"message\":\"name is required\","
                                + "\"details\":null}}"));
    }

    @Test
    void createRejectsUnknownCapability() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":false,\"capabilities\":[\"chat\",\"bogus\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,"
                                + "\"message\":\"capabilities contains an unknown capability\","
                                + "\"details\":null}}"));
    }

    @Test
    void createRejectsPastExpiry() throws Exception {
        long past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toEpochSecond();
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":true,\"expiresAtUnix\":" + past + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,"
                                + "\"message\":\"expiresAtUnix must be in the future\"}}"));
    }

    @Test
    void createAcceptsFutureExpiryAndEmitsExpiresAt() throws Exception {
        long future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0).toEpochSecond();
        MvcResult result = mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"ttl\",\"fullAccess\":true,\"expiresAtUnix\":" + future + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString());
        // 建 Key 时 lastUsedAt 恒为 nil → omitempty 省略；expiresAt 有值 → 出现在
        // createdAt 之前（Go struct 声明序）
        assertThat(fieldNames(data)).containsExactly(
                "id", "scopeType", "name", "apiKey", "fullAccess",
                "knowledgeBaseIds", "capabilities", "lastUsedAt", "expiresAt", "createdAt", "token");
        assertThat(data.get("expiresAt").asText()).startsWith("20");
    }

    @Test
    void createRejectsForeignKnowledgeBaseWith403() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":false,\"capabilities\":[\"retrieve\"],"
                                + "\"knowledgeBaseIds\":[\"kb-foreign\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1002,"
                                + "\"message\":\"knowledgeBaseIds contains a knowledge base outside this workspace\","
                                + "\"details\":null}}"));
    }

    @Test
    void createRejectsUnknownKnowledgeBaseWith400() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":false,\"capabilities\":[\"retrieve\"],"
                                + "\"knowledgeBaseIds\":[\"kb-nope\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,"
                                + "\"message\":\"knowledgeBaseIds contains an unknown knowledge base\","
                                + "\"details\":null}}"));
    }

    @Test
    void createWithInvalidWorkspaceIdIs400() throws Exception {
        // 文案与 code 来自 **PathTenantMatch 中间件**，不是 handler：
        // Go 的 tenantByID 组挂着 g.PathTenantMatch()，它在 handler 之前就拒掉了非法 id，
        // 所以 handler 里的 "Invalid workspace ID"（code 1000）是**不可达的死代码**。
        mockMvc.perform(post("/api/v1/tenants/not-a-number/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,"
                                + "\"message\":\"workspace id must be a positive integer\"}}"));
    }

    @Test
    void createWithEmptyBodyIs400WithStandardDetails() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1010,\"message\":\"Invalid request data\","
                                + "\"details\":\"No content to map due to end-of-input\"}}"));
    }

    // ── 列表 ──

    @Test
    void listReturnsBareArrayWithGoFieldOrder() throws Exception {
        String createdId = createKey("listed", "retrieve");

        MvcResult result = mockMvc.perform(get("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode data = MAPPER.readTree(body);
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(1);
        JsonNode first = data.get(0);
        // 列表项没有 token 键（只有创建响应才有）
        assertThat(fieldNames(first)).containsExactly(
                "id", "scopeType", "name", "apiKey", "fullAccess",
                "knowledgeBaseIds", "capabilities", "lastUsedAt", "expiresAt", "createdAt");
        assertThat(String.valueOf(first.get("id").asLong())).isEqualTo(createdId);
        assertThat(first.get("name").asText()).isEqualTo("listed");
    }

    @Test
    void listReturnsEmptyArrayWhenNoKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }

    // ── 更新 ──

    @Test
    void updateChangesConfiguration() throws Exception {
        String keyId = createKey("before", "retrieve");

        MvcResult result = mockMvc.perform(put("/api/v1/tenants/" + TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\" after \",\"fullAccess\":false,"
                                + "\"capabilities\":[\"chat\",\"chat\"],\"knowledgeBaseIds\":[\"kb-1\"]}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(data)).containsExactly(
                "id", "scopeType", "name", "apiKey", "fullAccess",
                "knowledgeBaseIds", "capabilities", "lastUsedAt", "expiresAt", "createdAt");
        assertThat(data.get("name").asText()).isEqualTo("after"); // trim
        assertThat(toList(data.get("capabilities"))).containsExactly("chat");
        assertThat(toList(data.get("knowledgeBaseIds"))).containsExactly("kb-1");
    }

    @Test
    void updateToFullAccessClearsScope() throws Exception {
        String keyId = createKey("before", "retrieve");
        MvcResult result = mockMvc.perform(put("/api/v1/tenants/" + TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"full\",\"fullAccess\":true,"
                                + "\"capabilities\":[\"retrieve\"],\"knowledgeBaseIds\":[\"kb-1\"]}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(data.get("fullAccess").asBoolean()).isTrue();
        assertThat(data.get("knowledgeBaseIds").isNull()).isTrue();
        assertThat(data.get("capabilities")).isEmpty();
    }

    /** 对照 Go：更新时**不校验** expiresAt 是否在未来（只有创建才校验）。 */
    @Test
    void updateAcceptsPastExpiry() throws Exception {
        String keyId = createKey("before", "retrieve");
        long past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toEpochSecond();
        mockMvc.perform(put("/api/v1/tenants/" + TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"past\",\"fullAccess\":false,"
                                + "\"capabilities\":[\"retrieve\"],\"expiresAtUnix\":" + past + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateUnknownKeyIs404() throws Exception {
        mockMvc.perform(put("/api/v1/tenants/" + TENANT_ID + "/api-keys/999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1003,\"message\":\"API key not found\","
                                + "\"details\":null}}"));
    }

    @Test
    void updateInvalidKeyIdIs400() throws Exception {
        mockMvc.perform(put("/api/v1/tenants/" + TENANT_ID + "/api-keys/0")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"fullAccess\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1000,\"message\":\"Invalid API key ID\"}}"));
    }

    // ── 删除 ──

    @Test
    void deleteRevokesAndReturnsBareSuccess() throws Exception {
        String keyId = createKey("doomed", "retrieve");

        MvcResult result = mockMvc.perform(delete("/api/v1/tenants/" + TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEmpty();

        // 撤销后从列表消失
        MvcResult list = mockMvc.perform(get("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(MAPPER.readTree(list.getResponse().getContentAsString())).isEmpty();
    }

    @Test
    void deleteUnknownKeyIs404() throws Exception {
        mockMvc.perform(delete("/api/v1/tenants/" + TENANT_ID + "/api-keys/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(
                        "{\"error\":{\"code\":1003,\"message\":\"API key not found\","
                                + "\"details\":null}}"));
    }

    @Test
    void deleteWithInvalidKeyIdIs400() throws Exception {
        mockMvc.perform(delete("/api/v1/tenants/" + TENANT_ID + "/api-keys/abc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** 跨租户：另一个租户的 Key 在本租户端点上不可见、不可改、不可删。 */
    @Test
    void keysAreTenantScoped() throws Exception {
        String keyId = createKey("mine", "retrieve");
        // 用另一个租户的 id 操作同一把 Key → **PathTenantMatch 中间件先拒**（403），
        // 走不到 service 层的租户边界（RowsAffected=0 → 404）。这与 Go 的路由分层一致：
        // tenantByID 组挂着 g.PathTenantMatch()，它比 handler/service 都靠前。
        mockMvc.perform(put("/api/v1/tenants/" + OTHER_TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"stolen\",\"fullAccess\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/tenants/" + OTHER_TENANT_ID + "/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // 原租户的 Key 仍然在
        MvcResult list = mockMvc.perform(get("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(MAPPER.readTree(list.getResponse().getContentAsString())).hasSize(1);
    }

    // ── 辅助 ──

    /** 建一把 scoped Key，返回其 id。 */
    private String createKey(String name, String capability) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tenants/" + TENANT_ID + "/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"fullAccess\":false,"
                                + "\"capabilities\":[\"" + capability + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** 顶层 JSON 键序（契约：gin.H 是 map → 字母序）。 */
    private static List<String> keyOrder(String json) throws Exception {
        return fieldNames(MAPPER.readTree(json));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
