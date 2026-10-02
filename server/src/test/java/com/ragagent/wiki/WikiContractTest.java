package com.ragagent.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.knowledge.mapper.KnowledgeBaseMapper;
import com.ragagent.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 阶段 4.2 Wiki 契约测试。
 *
 * <p><b>Wiki 端点的响应形态与知识库/MCP 都不同</b>，这是本测试的核心价值：
 * <ul>
 *   <li>页面 CRUD 返回**裸实体**（无 success/data 信封）</li>
 *   <li>列表是自定义分页结构 {@code {"pages":[...],"total":N,"page":N,"pageSize":N,"total_pages":N}}</li>
 *   <li>错误是**纯字符串** {@code {"error":"Wiki page not found"}}（不是 AppError 信封）</li>
 *   <li>403 走路由守卫：{@code {"error":"Forbidden: must own the resource or have the required role"}}</li>
 * </ul>
 *
 * golden 录自 Go dev server（KB 启用了 wiki_enabled），掩码 UUID 与时间戳。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WikiContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 18, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");
    private static final Pattern UUID_KEY_PATTERN = Pattern.compile(
            "\"(id|knowledge_base_id|parent_id|last_editor_id)\":"
                    + "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_ANY_PATTERN = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

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

    private String kbId;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "phase1viewer", "java-phase1-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "admin");
        insertMember("11111111-2222-3333-4444-555555555504", "viewer");

        // wiki_enabled 的 KB（对照 golden 录制时的 KB 配置）
        kbId = "35ed3096-d9ee-4561-96f0-ee38eb559122";
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(kbId);
        kb.setName("golden-wiki-kb");
        kb.setDescription("phase4 wiki golden");
        kb.setType("document");
        kb.setTenantId(10002L);
        kb.setCreatorId("11111111-2222-3333-4444-555555555501");
        com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy idx =
                com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy.defaultStrategy();
        idx.setWikiEnabled(true);
        idx.setGraphEnabled(false);
        kb.setIndexingStrategy(idx);
        kb.setCreatedAt(TS);
        kb.setUpdatedAt(TS);
        kbMapper.insert(kb);
    }

    private void insertUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(10002L);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        user.setCreatedAt(TS);
        user.setUpdatedAt(TS);
        userMapper.insert(user);
    }

    private void insertMember(String userId, String role) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(10002L);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(TS);
        memberMapper.insert(member);
    }

    // ── 页面 CRUD（裸实体，无信封） ─────────────────────────────────────────

    @Test
    void pageCrudLifecycle() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // 1. create → 裸实体，掩码比对（钉住字段序与键名）
        MvcResult created = mockMvc.perform(post(base + "/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"slug\":\"golden-page\",\"title\":\"Golden Page\",\"pageType\":\"summary\","
                                + "\"content\":\"# Golden\\n\\nContent with a [[other-page]] link.\","
                                + "\"summary\":\"short summary\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String createdBody = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(goldenChecked("wiki-page-create.json", createdBody), mask(createdBody),
                "create 应返回裸实体且与 golden 一致（掩码后）");
        assertFalse(createdBody.contains("\"success\""), "wiki 响应无 success/data 信封: " + createdBody);

        // 2. list → 自定义分页结构
        MvcResult list = mockMvc.perform(get(base + "/pages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String key : new String[]{"\"pages\"", "\"total\"", "\"page\"", "\"pageSize\"", "\"totalPages\""}) {
            assertTrue(listBody.contains(key), "list 应含分页键 " + key + ": " + listBody);
        }
        assertFalse(listBody.contains("\"data\""), "list 无 data 信封: " + listBody);

        // 3. get → 裸实体
        mockMvc.perform(get(base + "/pages/golden-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 4. 404 → 纯字符串错误（逐字节）
        MvcResult gb1 = mockMvc.perform(get(base + "/pages/no-such-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
            .andReturn();
        assertGolden(gb1, "wiki-page-not-found.json");

        // 5. update → 裸实体 + version 自增
        MvcResult updated = mockMvc.perform(put(base + "/pages/golden-page")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"Golden Page Renamed\","
                                + "\"content\":\"# Golden\\n\\nUpdated content with [[other-page]].\","
                                + "\"summary\":\"updated\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String updatedBody = updated.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(goldenChecked("wiki-page-update.json", updatedBody), mask(updatedBody),
                "update 应与 golden 一致（掩码后，含 version=2）");

        // 6. revisions → {revisions,total,current_version}
        MvcResult revs = mockMvc.perform(get(base + "/revisions/golden-page")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String revBody = revs.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String key : new String[]{"\"revisions\"", "\"total\"", "\"currentVersion\""}) {
            assertTrue(revBody.contains(key), "revisions 应含 " + key + ": " + revBody);
        }
    }

    // ── 文件夹 ──────────────────────────────────────────────────────────────

    @Test
    void folderEndpoints() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // 空列表 → 逐字节（{"parent_id":"","folders":[]}）
        MvcResult gb2 = mockMvc.perform(get(base + "/folders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
            .andReturn();
        assertGolden(gb2, "wiki-folders-empty.json");

        // 创建 → 裸实体
        mockMvc.perform(post(base + "/folders")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"Golden Folder\",\"parentId\":\"\"}"))
                .andExpect(status().isCreated());
    }

    // ── 聚合读端点（形态断言） ───────────────────────────────────────────────

    @Test
    void aggregateEndpoints() throws Exception {
        String token = login("java-phase1@weknora.test");
        String base = "/api/v1/knowledgebase/" + kbId + "/wiki";

        // index → {intro,version,groups:[{type,total,items}]}
        String idx = body(get(base + "/index"), token);
        for (String key : new String[]{"\"intro\"", "\"version\"", "\"groups\"", "\"type\"", "\"total\"", "\"items\""}) {
            assertTrue(idx.contains(key), "index 应含 " + key + ": " + idx);
        }

        // graph → {nodes:[{slug,title,page_type,link_count}],edges,meta:{mode,total}}
        String graph = body(get(base + "/graph"), token);
        for (String key : new String[]{"\"nodes\"", "\"slug\"", "\"linkCount\"", "\"meta\"", "\"mode\""}) {
            assertTrue(graph.contains(key), "graph 应含 " + key + ": " + graph);
        }

        // stats → {total_pages,pages_by_type,total_links,orphan_count,recent_updates}
        String stats = body(get(base + "/stats"), token);
        for (String key : new String[]{"\"totalPages\"", "\"pagesByType\"", "\"totalLinks\"",
                "\"orphanCount\"", "\"recentUpdates\""}) {
            assertTrue(stats.contains(key), "stats 应含 " + key + ": " + stats);
        }

        // lint → {knowledge_base_id,issues:[{type,severity,page_slug,description}]}
        String lint = body(get(base + "/lint"), token);
        assertTrue(lint.contains("\"issues\"") && lint.contains("\"knowledgeBaseId\""),
                "lint 形态: " + lint);

        // issues → 裸数组（无信封）
        String issues = body(get(base + "/issues"), token);
        assertTrue(issues.trim().startsWith("["), "issues 应为裸数组: " + issues);
    }

    // ── 权限（Go 的路由守卫文案） ────────────────────────────────────────────

    @Test
    void viewerCannotCreatePage() throws Exception {
        String token = login("java-phase1-viewer@weknora.test");
        MvcResult gb3 = mockMvc.perform(post("/api/v1/knowledgebase/" + kbId + "/wiki/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"slug\":\"v-page\",\"title\":\"V\"}"))
                .andExpect(status().isForbidden())
            .andReturn();
        assertGolden(gb3, "wiki-create-forbidden.json");
    }

    /**
     * 不存在/跨租户的 KB：**404 + AppError 信封**（Go 实测，不是 403）。
     * 与"资源存在但无权"区分开——后者才是守卫式 403。
     */
    @Test
    void unknownKbIsNotFound() throws Exception {
        String token = login("java-phase1@weknora.test");
        MvcResult r = mockMvc.perform(get("/api/v1/knowledgebase/00000000-0000-0000-0000-000000000099/wiki/pages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":1003") && body.contains("knowledge base not found"),
                "应为 AppError 信封 404: " + body);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                        String token) throws Exception {
        MvcResult r = mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), "login 响应应含 token: " + body);
        return m.group(1);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper GOLDEN_SEMANTIC_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** {@code -Dcontract.refresh=true} 时把掩码后的实际响应写回夹具（B3 重录用）。 */
    private static final boolean REFRESH_FIXTURES = Boolean.getBoolean("contract.refresh");

    private static String goldenChecked(String name, String actualBody) throws Exception {
        String masked = mask(actualBody);
        if (REFRESH_FIXTURES) {
            var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
            java.nio.file.Path file = java.nio.file.Path.of("src/test/resources/contracts", name);
            if (!java.nio.file.Files.exists(file)) {
                file = java.nio.file.Path.of("server/src/test/resources/contracts", name);
            }
            java.nio.file.Files.writeString(file, masked + "\n");
            return masked;
        }
        return mask(golden(name)).strip();
    }

    private static String golden(String name) throws Exception {
        // PR4 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.ragagent.support.ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }


    /** 掩码：UUID（含任意位置的裸 UUID，如 recent_updates 里的 id）与时间戳 */
    // ── 金片对比（B2 统一基建：语义归一 + strip + -Dcontract.refresh 重录） ──

    private static void assertGolden(org.springframework.test.web.servlet.MvcResult r,
            String name) throws Exception {
        com.ragagent.support.GoldenContract.assertEquals("src/test/resources/contracts",
                name, WikiContractTest::mask,
                r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = UUID_KEY_PATTERN.matcher(s).replaceAll("\"$1\":\"<id>\"");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        return UUID_ANY_PATTERN.matcher(out).replaceAll("<uuid>");
    }
}
