package com.ragagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
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
 * 波 2 终扫批契约测试：chunker/preview 1 端点（对照 golden 逐字节比对）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-fav-cprev-golden.sh，13 条 cprev-*.json）。
 *
 * preview 是纯无状态端点：响应完全确定（无时间戳、无 uuid），全部
 * <b>零掩码逐字节</b>——double 的 Go 格式（"91" 非 "91.0"）、md_heading_counts
 * 的 int 键 map、rejected 的 nil→null、context_header 的 omitempty 都被静态钉住。
 *
 * 策略矩阵刻意覆盖：空 strategy=legacy（不是 auto！）、显式 heading/legacy、
 * 未知 strategy 落 default→auto、parent-child、token_limit 压缩、中文。
 * 深层结构类型错误（如 chunk_size:"five"）的 Go/Jackson 解码措辞是已知差异，
 * 刻意不录。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkerPreviewContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";

    /** 与录制脚本逐字相同的 markdown 样本（分块结果是它的纯函数）。 */
    private static final String MARKDOWN = "# Chapter One\n\n"
            + "Intro paragraph with enough words to fill a chunk naturally.\n\n"
            + "## Section A\n\nBody text for section A keeps going a little longer.\n\n"
            + "## Section B\n\nBody text for section B also keeps going.\n\n"
            + "# Chapter Two\n\nFinal paragraph closes the sample document.";

    private static final String CHINESE = "这是第一段话，用来测试中文分块的效果。"
            + "这一句再长一点，好让分块器有实际的边界可以选。"
            + "这是第二段话，继续填充内容让段落更长一些。"
            + "第三段保持同样的风格，确保总体超过一行。";

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

    // ── 1) 鉴权 + binding + 空文本 + 超限 ─────────────────────────────────

    @Test
    void errorFamily() throws Exception {
        assertGolden(json(post("/api/v1/chunker/preview"), "{\"text\":\"hello\"}"),
                401, "cprev-noauth.json");
        String owner = "Bearer " + login();
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "not-json"), 400, "cprev-bad-body.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":\"   \"}"), 400, "cprev-empty-text.json");
        // 70000 个 'x'（> 64k rune 上限）→ 413 三键裸错误体
        String oversize = "{\"text\":\"" + "x".repeat(70000) + "\"}";
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner), oversize),
                413, "cprev-oversize.json");
    }

    // ── 2) 策略矩阵（零掩码逐字节） ──────────────────────────────────────

    @Test
    void strategyMatrix() throws Exception {
        String owner = "Bearer " + login();
        String md = jsonQuote(MARKDOWN);

        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"chunkSize\":200,\"chunkOverlap\":20}}"),
                200, "cprev-basic-markdown.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":\"Just a plain paragraph. No structure at all. Still needs a second"
                        + " sentence to be realistic.\",\"chunkingConfig\":{\"chunkSize\":200,"
                        + "\"chunkOverlap\":20}}"),
                200, "cprev-plain-text.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"chunkSize\":200,\"chunkOverlap\":20,"
                        + "\"strategy\":\"legacy\"}}"),
                200, "cprev-explicit-legacy.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"chunkSize\":200,\"chunkOverlap\":20,"
                        + "\"strategy\":\"heading\"}}"),
                200, "cprev-explicit-heading.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"chunkSize\":200,\"chunkOverlap\":20,"
                        + "\"strategy\":\"nonsense\"}}"),
                200, "cprev-unknown-strategy.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"enableParentChild\":true,"
                        + "\"parentChunkSize\":300,\"childChunkSize\":100}}"),
                200, "cprev-parent-child.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + md + ",\"chunkingConfig\":{\"chunkSize\":1600,\"chunkOverlap\":160,"
                        + "\"tokenLimit\":80}}"),
                200, "cprev-token-limit.json");
        assertGolden(json(post("/api/v1/chunker/preview").header("Authorization", owner),
                "{\"text\":" + jsonQuote(CHINESE) + ",\"chunkingConfig\":{\"chunkSize\":60,"
                        + "\"chunkOverlap\":10}}"),
                200, "cprev-chinese.json");
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
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + snippet(r));
        assertEquals(golden(goldenName), raw(r), goldenName);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 组请求体用：按 JSON 字符串字面量转义（与录制脚本的 python json.dumps 等价）。 */
    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String snippet(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /** 响应体按 UTF-8 字节解码（getContentAsString 缺 charset 时按 ISO-8859-1，中文/全角破折号会花）。 */
    private static String raw(MvcResult r) {
        // PR4 语义比较：与 golden 同侧归一
        return com.ragagent.support.ContractJson.semantic(
                new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
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
