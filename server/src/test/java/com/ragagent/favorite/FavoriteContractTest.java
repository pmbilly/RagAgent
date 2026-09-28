package com.ragagent.favorite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 2 终扫批契约测试：用户收藏 3 端点（对照 golden 逐字节比对）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-fav-cprev-golden.sh，21 条 fav-*.json）。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：
 * 空列表 → add ×2 + 重复 add → 列表回读 → remove 真实行 + 幽灵行 →
 * 列表回读 → 400 家族 → 鉴权家族。
 *
 * H2 种子镜像录制身份：租户 10002 + owner 11111111-…-5501（java-phase1@weknora.test
 * / Passw0rd!）——收藏表无外键，resource_id 用固定假 id，两侧行集可逐字节对齐。
 *
 * 唯一动态值：created_at（服务器时钟），两侧同掩码；空列表形态（"data":[] 非 null）
 * 由 fav-list-empty-kb 静态钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FavoriteContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");

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

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(OWNER);
        user.setUsername("phase1test");
        user.setEmail(OWNER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(OWNER);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        memberMapper.insert(member);
    }

    // ── 1) 空列表（钉 GORM Find 空结果 → "data":[]）+ 缺 type 参数 ─────────

    @Test
    void emptyListThenNoType() throws Exception {
        String owner = "Bearer " + login();

        assertGolden(get("/api/v1/user/favorites?type=kb").header("Authorization", owner),
                200, "fav-list-empty-kb.json");
        assertGolden(get("/api/v1/user/favorites?type=agent").header("Authorization", owner),
                200, "fav-list-empty-agent.json");
        // type 缺失 → 空串不命中白名单 → 400 信封
        assertGolden(get("/api/v1/user/favorites").header("Authorization", owner),
                400, "fav-list-notype.json");
    }

    // ── 2) add 成功 ×2 + 幂等重复（行集固定，重复 add 不产生新行） ──────────

    @Test
    void addThenList() throws Exception {
        String owner = "Bearer " + login();

        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"kb\",\"id\":\"fav-kb-fixed-0001\"}"), 200, "fav-add-kb.json");
        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"agent\",\"id\":\"fav-agent-fixed-0001\"}"), 200, "fav-add-agent.json");
        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"kb\",\"id\":\"fav-kb-fixed-0001\"}"), 200, "fav-add-dup.json");

        assertMasked(get("/api/v1/user/favorites?type=kb").header("Authorization", owner),
                200, "fav-list-kb-after.json");
        assertMasked(get("/api/v1/user/favorites?type=agent").header("Authorization", owner),
                200, "fav-list-agent-after.json");
    }

    // ── 3) remove 真实行 + 幽灵行（都 200 {"success":true}） ────────────────

    @Test
    void removeRealAndGhost() throws Exception {
        String owner = "Bearer " + login();

        // 前置：建 kb+agent 两行（同录制脚本前缀）
        mockMvc.perform(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"kb\",\"id\":\"fav-kb-fixed-0001\"}")).andReturn();
        mockMvc.perform(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"agent\",\"id\":\"fav-agent-fixed-0001\"}")).andReturn();

        assertGolden(delete("/api/v1/user/favorites/agent/fav-agent-fixed-0001")
                .header("Authorization", owner), 200, "fav-remove-agent.json");
        assertGolden(delete("/api/v1/user/favorites/agent/ghost-id-000")
                .header("Authorization", owner), 200, "fav-remove-ghost.json");
        assertGolden(get("/api/v1/user/favorites?type=agent").header("Authorization", owner),
                200, "fav-list-agent-after-rm.json");
    }

    // ── 4) 400 家族（类型白名单 / 空 id / binding 文案进 details） ──────────

    @Test
    void badRequestFamily() throws Exception {
        String owner = "Bearer " + login();

        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"wiki\",\"id\":\"x\"}"), 400, "fav-add-invalid-type.json");
        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"kb\"}"), 400, "fav-add-missing-id.json");
        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "{\"type\":\"kb\",\"id\":\"   \"}"), 400, "fav-add-blank-id.json");
        assertGolden(json(post("/api/v1/user/favorites").header("Authorization", owner),
                "not-json"), 400, "fav-add-bad-body.json");
        assertGolden(post("/api/v1/user/favorites").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON), 400, "fav-add-empty-body.json");
        assertGolden(get("/api/v1/user/favorites?type=wiki").header("Authorization", owner),
                400, "fav-list-invalid-type.json");
        assertGolden(delete("/api/v1/user/favorites/wiki/x").header("Authorization", owner),
                400, "fav-remove-invalid-type.json");
    }

    // ── 5) 鉴权家族（401 两态） ─────────────────────────────────────────────

    @Test
    void authFamily() throws Exception {
        assertGolden(get("/api/v1/user/favorites?type=kb"), 401, "fav-noauth.json");
        assertGolden(get("/api/v1/user/favorites?type=kb")
                .header("Authorization", "Bearer garbage.token.here"), 401, "fav-badtoken.json");
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    /** 静态 golden：状态码 + 逐字节。 */
    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    /** 动态 golden：created_at 两侧同掩码后逐字节。 */
    private void assertMasked(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        assertEquals(mask(golden(goldenName)), mask(raw(r)), goldenName);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String mask(String s) {
        return TS_PATTERN.matcher(s).replaceAll("\"<ts>\"");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String raw(MvcResult r) throws Exception {
        // PR4 语义比较：与 golden 同侧归一（非 JSON 文本原样）
        return com.ragagent.support.ContractJson.semantic(RAW_SEMANTIC_MAPPER,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper GOLDEN_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String golden(String name) throws Exception {
        // PR4 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.ragagent.support.ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }
}
