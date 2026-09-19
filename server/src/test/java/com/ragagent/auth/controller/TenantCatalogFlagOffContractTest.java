package com.ragagent.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 跨空间守卫的 flag-off 分支（对照 CT_FLAG_OFF=1 录制的 2 条 golden）：
 * WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=false 时 GET /tenants/all 与
 * /tenants/search 恒 403「Cross-workspace access is disabled」（code 1002，
 * 字节静态，与调用者身份无关——录制用普通 owner token）。
 */
@SpringBootTest(properties = "weknora.tenant.enable-cross-tenant-access=false")
@AutoConfigureMockMvc
class TenantCatalogFlagOffContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

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

    private String owner;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        User user = new User();
        user.setId(OWNER_ID);
        user.setUsername("phase1test");
        user.setEmail(OWNER_EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);

        TenantMember member = new TenantMember();
        member.setUserId(OWNER_ID);
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.parse("2026-09-01T10:00:00Z"));
        memberMapper.insert(member);

        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).as("login 响应应含 token").isTrue();
        owner = "Bearer " + m.group(1);
    }

    @Test
    void flagOffBlocksCrossTenantCatalog() throws Exception {
        assertGolden(get("/api/v1/tenants/all"), "ct-all-disabled.json");
        assertGolden(get("/api/v1/tenants/search"), "ct-search-disabled.json");
    }

    private void assertGolden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                              String goldenName) throws Exception {
        MvcResult r = mockMvc.perform(req.header("Authorization", owner)).andReturn();
        assertEquals(403, r.getResponse().getStatus(), goldenName + " 状态码不符: "
                + r.getResponse().getContentAsString());
        String golden = new String(new ClassPathResource("contracts/" + goldenName)
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(golden, r.getResponse().getContentAsString(), goldenName);
    }
}
