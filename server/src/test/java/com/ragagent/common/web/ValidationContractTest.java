package com.ragagent.common.web;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 全局参数校验异常的 400 契约：信封形态 {@code {success:false, error:{code:1000,
 * message, details}}}，message 保持中文类别文案，details 为字段级中文说明（多条
 * "\n" 连接）。探针端点仅存在于 test 源码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ValidationContractTest.ProbeController.class)
class ValidationContractTest {

    private static final long TENANT = 10002L;
    private static final String EMAIL = "validation-contract@weknora.test";
    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";

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
    private final ObjectMapper m = new ObjectMapper();
    private String owner;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("validation-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);
        User user = new User();
        user.setId("validation-owner");
        user.setUsername("vowner");
        user.setEmail(EMAIL);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
        TenantMember member = new TenantMember();
        member.setUserId("validation-owner");
        member.setTenantId(TENANT);
        member.setRole("owner");
        member.setStatus("active");
        memberMapper.insert(member);
        try {
            MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + EMAIL + "\",\"password\":\"Passw0rd!\"}"))
                    .andReturn();
            owner = "Bearer " + m.readTree(r.getResponse().getContentAsString()).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void cleanup() {
        TestSchema.resetData(jdbc);
    }

    @RestController
    @RequestMapping("/test-only/validation")
    static class ProbeController {
        @PostMapping("/body")
        String body(@RequestBody @Valid SampleRequest req) {
            return "ok";
        }

        @GetMapping("/page")
        String page(@Valid @ModelAttribute PageParams params) {
            return "ok";
        }

        record SampleRequest(@NotBlank String url, @Min(1) @Max(100) Integer page_size) {
        }
    }

    private JsonNode errorOf(MvcResult r) throws Exception {
        JsonNode body = m.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        return body.path("error");
    }

    @Test
    void blankFieldYieldsFieldLevelDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"\",\"page_size\":5}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode err = errorOf(r);
        assertThat(err.path("code").asInt()).isEqualTo(ErrorCode.BAD_REQUEST.value());
        assertThat(err.path("message").asText()).isEqualTo("请求参数不合法");
        assertThat(err.path("details").asText()).isEqualTo("url: 不能为空");
    }

    @Test
    void outOfRangeYieldsRangeDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x\",\"page_size\":0}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(r).path("details").asText()).isEqualTo("page_size: 必须不小于 1");
    }

    @Test
    void emptyBodyYieldsEmptyBodyDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(r).path("details").asText()).isEqualTo("请求体不能为空");
    }

    @Test
    void malformedJsonYieldsMalformedDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": 1.5")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode err = errorOf(r);
        assertThat(err.path("code").asInt()).isEqualTo(ErrorCode.BAD_REQUEST.value());
        boolean malformed = "请求体格式不正确".equals(err.path("details").asText())
                || err.path("details").asText().startsWith("url:");
        assertThat(malformed).isTrue();
    }

    @Test
    void wrongFieldTypeYieldsFieldDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x\",\"page_size\":\"abc\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(r).path("details").asText()).isEqualTo("page_size: 类型不正确");
    }

    @Test
    void literalNullBodyYieldsEmptyBodyDetails() throws Exception {
        MvcResult r = mockMvc.perform(post("/test-only/validation/body").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("null")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(r).path("details").asText()).isEqualTo("请求体不能为空");
    }

    @Test
    void pageParamTypeMismatchYieldsPaginationMessage() throws Exception {
        MvcResult r = mockMvc.perform(get("/test-only/validation/page?page=abc").header("Authorization", owner)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode err = errorOf(r);
        assertThat(err.path("message").asText()).isEqualTo("分页参数不合法");
        assertThat(err.path("details").asText()).isEqualTo("page: 类型不正确");
    }

    @Test
    void pageOutOfRangeYieldsPaginationMessage() throws Exception {
        MvcResult r = mockMvc.perform(get("/test-only/validation/page?page=0").header("Authorization", owner)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorOf(r).path("message").asText()).isEqualTo("分页参数不合法");
    }
}
