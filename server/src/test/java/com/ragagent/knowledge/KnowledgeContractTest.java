package com.ragagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.ragagent.knowledge.domain.StorageBackend;
import com.ragagent.knowledge.mapper.StorageBackendMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 阶段 3 契约测试：KB CRUD + 文档 CRUD，对照 golden 逐字节比对。
 *
 * golden 录制序：probe-kb → golden-kb(录) → list(录) → get(录) → 404(录) → {}(不录)
 * → update(录) → pin(录) → move-targets(录)；文档序：upload(录) → list/get(结构断言，
 * 处理状态异步竞态) → 404(录) → manual(录) → folders(录) → bad-status(录) → update(录) → delete(录)。
 * 掩码：UUID、时间戳、knowledge_base_id、file_path、task_id。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final OffsetDateTime TS = OffsetDateTime.of(2026, 9, 17, 10, 0, 0, 123456000, ZoneOffset.ofHours(8));
    private static final String STORAGE_BACKEND_ID = "c730730a-70f5-4d86-a7e1-58972cf27567";
    private static final byte[] DOC_BYTES = "hello world phase3 golden document\n".getBytes(StandardCharsets.UTF_8);

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_KEY_PATTERN = Pattern.compile(
            "\"(id|knowledge_base_id|task_id)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("\"file_path\":\"[^\"]*\"");

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
    private StorageBackendMapper storageBackendMapper;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(10002L);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenant.setDefaultStorageBackendId(STORAGE_BACKEND_ID);
        tenantMapper.insert(tenant);

        StorageBackend backend = new StorageBackend();
        backend.setId(STORAGE_BACKEND_ID);
        backend.setTenantId(10002L);
        backend.setName("local-default");
        backend.setProvider("local");
        storageBackendMapper.insert(backend);

        insertUser("11111111-2222-3333-4444-555555555501", "phase1test", "java-phase1@weknora.test");
        insertUser("11111111-2222-3333-4444-555555555504", "phase1viewer", "java-phase1-viewer@weknora.test");
        insertMember("11111111-2222-3333-4444-555555555501", "owner");
        insertMember("11111111-2222-3333-4444-555555555504", "viewer");
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

    // ── KB CRUD（按录制序） ────────────────────────────────────────────────

    @Test
    void kbCrudLifecycle() throws Exception {
        String token = login("java-phase1@weknora.test");

        // 1. probe-kb（无 golden，作为 list/move-targets 的确定性地基）
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"probe-kb\"}"))
                .andExpect(status().isCreated());

        // 2. golden-kb → 201 掩码比对
        MvcResult created = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"golden-kb\",\"description\":\"phase3 golden\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(mask(golden("kb-create.json")),
                mask(created.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-create 应与 golden 一致（掩码后）");
        String kbId = extractUuid(created.getResponse().getContentAsString(StandardCharsets.UTF_8), "\"id\":\"");

        // 3. list → 掩码比对（probe-kb + golden-kb，插入序）
        MvcResult list = mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("kb-list.json")),
                mask(list.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-list 应与 golden 一致（掩码后）");

        // 4. get → 掩码比对
        MvcResult got = mockMvc.perform(get("/api/v1/knowledge-bases/" + kbId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("kb-get.json")),
                mask(got.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-get 应与 golden 一致（掩码后）");

        // 5. 404 → 静态 golden
        mockMvc.perform(get("/api/v1/knowledge-bases/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(goldenBytes("kb-not-found.json")));

        // 6. {} 空名 KB（不录 golden，作为 move-targets 第三行）
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated());

        // 7. update → 掩码比对
        MvcResult updated = mockMvc.perform(put("/api/v1/knowledge-bases/" + kbId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"id\":\"" + kbId + "\",\"name\":\"golden-kb-renamed\",\"description\":\"updated desc\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("kb-update.json")),
                mask(updated.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-update 应与 golden 一致（掩码后）");

        // 8. pin → 掩码比对
        MvcResult pinned = mockMvc.perform(put("/api/v1/knowledge-bases/" + kbId + "/pin")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("kb-pin.json")),
                mask(pinned.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-pin 应与 golden 一致（掩码后）");

        // 9. move-targets → 掩码比对（probe-kb + 空名 KB，原始实体序列化）
        MvcResult targets = mockMvc.perform(get("/api/v1/knowledge-bases/" + kbId + "/move-targets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("kb-move-targets.json")),
                mask(targets.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "kb-move-targets 应与 golden 一致（掩码后）");
    }

    @Test
    void kbCreateForbiddenForViewer() throws Exception {
        String token = login("java-phase1-viewer@weknora.test");
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"v-kb\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().bytes(goldenBytes("kb-create-forbidden-viewer.json")));
    }

    // ── 文档 CRUD ─────────────────────────────────────────────────────────

    @Test
    void knowledgeLifecycle() throws Exception {
        String token = login("java-phase1@weknora.test");
        // 建 KB（无存储后端的解析差异：种子已含默认后端）
        MvcResult kb = mockMvc.perform(post("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"golden-kb\",\"description\":\"phase3 golden\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String kbId = extractUuid(kb.getResponse().getContentAsString(StandardCharsets.UTF_8), "\"id\":\"");

        // 1. 上传文件 → 掩码比对（file_hash 静态：内容字节与录制一致）
        MockMultipartFile file = new MockMultipartFile(
                "file", "golden-doc.txt", "text/plain", DOC_BYTES);
        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/knowledge-bases/" + kbId + "/knowledge/file")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("doc-upload-file.json")),
                mask(uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "doc-upload 应与 golden 一致（掩码后）");
        String docId = extractUuid(uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8), "\"id\":\"");

        // 2. list/get：结构断言（处理状态由异步 worker 竞态决定）
        MvcResult list = mockMvc.perform(get("/api/v1/knowledge-bases/" + kbId + "/knowledge")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("page_size", "20"))
                .andExpect(status().isOk())
                .andReturn();
        String listBody = list.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(listBody.contains("\"total\":1") && listBody.contains("golden-doc.txt"), "list 应含 1 篇文档: " + listBody);

        MvcResult got = mockMvc.perform(get("/api/v1/knowledge/" + docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String gotBody = got.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(gotBody.contains("\"title\":\"golden-doc.txt\""), "get 应返回该文档: " + gotBody);

        // 3. 404 → 静态 golden
        mockMvc.perform(get("/api/v1/knowledge/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(goldenBytes("doc-not-found.json")));

        // 4. folders → 静态 golden（录制时仅上传文档 1 篇；Go 只排除 deleting，draft 计入）
        mockMvc.perform(get("/api/v1/knowledge-bases/" + kbId + "/knowledge/folders")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().bytes(goldenBytes("doc-folders.json")));

        // 5. manual draft → 掩码比对
        MvcResult manual = mockMvc.perform(post("/api/v1/knowledge-bases/" + kbId + "/knowledge/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"golden-manual\",\"content\":\"# Golden Manual\\n\\nhello manual knowledge\",\"status\":\"draft\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("doc-manual-create.json")),
                mask(manual.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "manual-create 应与 golden 一致（掩码后）");
        String manualId = extractUuid(manual.getResponse().getContentAsString(StandardCharsets.UTF_8), "\"id\":\"");

        // 6. manual 非法 status → 静态 golden（400）
        mockMvc.perform(post("/api/v1/knowledge-bases/" + kbId + "/knowledge/manual")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"content\":\"y\",\"status\":\"enabled\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().bytes(goldenBytes("doc-manual-bad-status.json")));

        // 7. update → 掩码比对
        MvcResult updated = mockMvc.perform(put("/api/v1/knowledge/" + manualId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"golden-manual-renamed\",\"description\":\"updated\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("doc-update.json")),
                mask(updated.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "doc-update 应与 golden 一致（掩码后）");

        // 8. delete → 掩码比对（task_id 动态）
        MvcResult deleted = mockMvc.perform(delete("/api/v1/knowledge/" + manualId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(mask(golden("doc-delete.json")),
                mask(deleted.getResponse().getContentAsString(StandardCharsets.UTF_8)),
                "doc-delete 应与 golden 一致（掩码后）");
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

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

    private static String golden(String name) throws Exception {
        // PR4 语义比较：键序/HTML 转义归一后返回（非 JSON 文本原样），断言侧不变
        var resource = new org.springframework.core.io.ClassPathResource("contracts/" + name);
        if (!resource.exists()) {
            resource = new org.springframework.core.io.ClassPathResource("contracts/" + name + ".json");
        }
        String text = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.ragagent.support.ContractJson.semantic(GOLDEN_SEMANTIC_MAPPER, text);
    }

    private static byte[] goldenBytes(String name) throws Exception {
        return new ClassPathResource("contracts/" + name).getInputStream().readAllBytes();
    }

    private static String extractUuid(String body, String keyPrefix) {
        java.util.regex.Matcher m = Pattern.compile(
                java.util.regex.Pattern.quote(keyPrefix)
                        + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})").matcher(body);
        assertTrue(m.find(), "响应应含 UUID (" + keyPrefix + "): " + body);
        return m.group(1);
    }

    /** 与 golden 比对前的统一掩码 */
    private static String mask(String s) {
        // PR4 语义比较入口：键序/转义归一后再掩码
        s = com.ragagent.support.ContractJson.semantic(s);
        String out = UUID_KEY_PATTERN.matcher(s).replaceAll("\"$1\":\"<id>\"");
        out = TS_PATTERN.matcher(out).replaceAll("\"<ts>\"");
        out = FILE_PATH_PATTERN.matcher(out).replaceAll("\"file_path\":\"<path>\"");
        return out;
    }
}
