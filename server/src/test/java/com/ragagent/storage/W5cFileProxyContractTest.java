package com.ragagent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ragagent.TestSchema;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.domain.UserPreferences;
import com.ragagent.auth.mapper.TenantMapper;
import com.ragagent.auth.mapper.TenantMemberMapper;
import com.ragagent.auth.mapper.UserMapper;
import com.ragagent.knowledge.storage.LocalStorageService;

/**
 * 文件代理面 8 条路由的契约测试（收尾批 W5c）。golden：w5c-*（76 个，
 * scripts/record-w5c-golden.sh 的 Go 实录；种子态见脚本头注释——双端共享
 * dev PG + 同一 LOCAL_STORAGE_BASE_DIR）。
 *
 * <h2>本测试的覆盖边界</h2>
 * <ul>
 *   <li>二进制 golden（*.bin + *.bin.headers 双锚）与 HEAD 形态逐字节比对；</li>
 *   <li>presigned 的<b>有效签名 200 分支</b>依赖 SYSTEM_AES_KEY（≥16 字节）——
 *       测试 JVM 无该 env（签名恒拒 → 403，与 Go 的 key=nil 分支同形），
 *       该分支由真 PG A/B（ab-w5c.sh，双端同 key）字节级覆盖；本测试比对
 *       403 形态 + 无 key 时的"有效签名也 403"恒等行为；</li>
 *   <li>头部比对归一化容器噪音：status line reason-phrase（gin vs Tomcat）、
 *       Date / X-Request-Id / Vary / Keep-Alive / Connection（g5/bs 批先例）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5cFileProxyContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    /** dev PG 的 System LOCAL 行 id——w5c-prev-* golden 的 storage:// 前缀逐字节依赖它 */
    private static final String SYS_LOCAL = "c730730a-70f5-4d86-a7e1-58972cf27567";
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String VIEWER_EMAIL = "java-phase1-viewer@weknora.test";

    private static final String KB_MAIN = "5c000000-0000-0000-0000-000000000000";
    private static final String KB_NOBIND = "5c000000-0000-0000-0000-000000000099";
    private static final String KNOW_ID = "5c000001-0000-0000-0000-000000000001";
    private static final String RES_ID = "5c00000a-0000-0000-0000-00000000000a";
    private static final String RES_HANDLE = "w5cresourcehandle00001";
    private static final String SES_ID = "5c000004-0000-0000-0000-000000000004";
    private static final String MSG_REF = "5c000002-0000-0000-0000-000000000002";
    private static final String MSG_NOREF = "5c000002-0000-0000-0000-000000000003";
    private static final String MSG_RESREF = "5c000002-0000-0000-0000-000000000004";
    private static final String MSG_USER = "5c000002-0000-0000-0000-000000000005";

    private static final String TXT = "local://10002/exports/w5c-seed.txt";
    // ⚠️ MockMvc 的 query 参数不做百分号解码，直接用解码后的值
    // （Go 的 c.Query 拿到的也是解码值，两侧 handler 输入一致）。
    private static final String TXT_ENC = TXT;
    private static final String PNG_ENC = "local://10002/exports/w5c-image.png";

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
    private LocalStorageService localStorage;

    private String owner;
    private String viewer;

    // ── 种子（复刻 record-w5c-golden.sh 的录制态）────────────────────────────

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("w5c-tenant");
        tenant.setStatus("active");
        tenant.setDefaultStorageBackendId(SYS_LOCAL);
        tenantMapper.insert(tenant);

        seedUser(OWNER, "w5cowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "w5cviewer", VIEWER_EMAIL, "viewer");

        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                + "status, legacy_alias) VALUES (?, ?, 'System LOCAL', 'local', '{}', 'env', 'active', TRUE)",
                SYS_LOCAL, TENANT);

        jdbc.update("INSERT INTO resources (id, handle, tenant_id, storage_backend_id, provider, "
                        + "physical_path, location_hash, kind, mime_type, original_name, size, "
                        + "content_hash, lifecycle, state) "
                        + "VALUES (?, ?, ?, NULL, 'local', ?, ?, 'file', 'text/plain; charset=utf-8', "
                        + "'w5c-resource.txt', 41, '', 'persistent', 'active')",
                RES_ID, RES_HANDLE, TENANT, TXT,
                "20d209b9d6270a14ec6e3dae7367b8326f6c68fb824e91fe9d91ee7ee5aa5ca1");
        jdbc.update("INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, "
                        + "expires_at, revoked_at) VALUES (?, ?, ?, 'read', "
                        + "TIMESTAMP WITH TIME ZONE '2030-01-01 00:00:00+00', NULL)",
                "5c00000b-0000-0000-0000-00000000000b",
                sha256("w5cgranttoken0000000001"), RES_ID);
        jdbc.update("INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, "
                        + "expires_at, revoked_at) VALUES (?, ?, ?, 'read', "
                        + "TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00', NULL)",
                "5c00000b-0000-0000-0000-00000000000e",
                sha256("w5cexpiredtoken000000001"), RES_ID);
        jdbc.update("INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, "
                        + "expires_at, revoked_at) VALUES (?, ?, ?, 'read', "
                        + "TIMESTAMP WITH TIME ZONE '2030-01-01 00:00:00+00', "
                        + "TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00')",
                "5c00000b-0000-0000-0000-00000000000f",
                sha256("w5crevokedtoken000000001"), RES_ID);

        for (String kb : new String[] {KB_MAIN, KB_NOBIND}) {
            jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, description, creator_id, "
                    + "embedding_model_id, summary_model_id) VALUES (?, ?, ?, 'w5c', ?, '', '')",
                    kb, kb.equals(KB_MAIN) ? "w5c KB" : "w5c KB unbound", TENANT, OWNER);
        }
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source) "
                + "VALUES (?, ?, ?, 'document', 'w5c doc', 'file')", KNOW_ID, TENANT, KB_MAIN);
        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, "
                + "relation) VALUES (?, ?, ?, 'knowledge', ?, 'source_file')",
                "5c00000c-0000-0000-0000-00000000000c", RES_ID, TENANT, KNOW_ID);

        jdbc.update("INSERT INTO sessions (id, tenant_id, user_id, title) VALUES (?, ?, ?, 'w5c')",
                SES_ID, TENANT, OWNER);
        insertMessage(MSG_REF, "图片见 local://10002/exports/w5c-image.png", 10002);
        insertMessage(MSG_NOREF, "没有引用的回复", 0);
        insertMessage(MSG_RESREF, "引用 resource://" + RES_HANDLE + " 图片", 0);
        insertMessage(MSG_USER, "用户提问 " + TXT, 10002);

        seedFile("w5c-seed.txt", "w5c seed payload line1\nline2 bytes 12345\n");
        seedFile("w5c-image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n',
                0, 0, 0, 0x0d, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
                0x1f, 0x15, (byte) 0xc4, (byte) 0x89, '\n'});
        seedFile("w5c-page.html", "<html><body>w5c active content</body></html>\n");
        seedFile("w5c-report.pdf", "%PDF-1.4 w5c pdf bytes\n%%EOF\n");
        seedFile("w5c-blob.xyz", "w5c unknown ext bytes\n");
        seedFile("w5c-数据.txt", "w5c CJK filename content\n");

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
    }

    private void insertMessage(String id, String content, long agentTenantId) {
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, "
                + "is_completed, agent_tenant_id) VALUES (?, ?, ?, 'assistant', ?, TRUE, ?)",
                id, "5c000003-0000-0000-0000-00000000000" + id.substring(id.length() - 1), SES_ID,
                content, agentTenantId);
    }

    private void seedUser(String id, String name, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
        TenantMember member = new TenantMember();
        member.setUserId(id);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        memberMapper.insert(member);
    }

    private void seedFile(String name, String content) throws Exception {
        seedFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private void seedFile(String name, byte[] content) throws Exception {
        Path dir = localStorage.baseDir().resolve("10002/exports");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), content);
    }

    private static String sha256(String s) {
        try {
            byte[] sum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sum) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String login(String email) {
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                    .andReturn();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString()).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 请求与比对辅助 ──────────────────────────────────────────────────────

    private MvcResult call(String method, String path, String auth) {
        return call(method, path, auth, new String[0]);
    }

    /** extraHeaders：{@code "Name: value"} 行数组。 */
    private MvcResult call(String method, String path, String auth, String... extraHeaders) {
        try {
            var builder = switch (method) {
                case "GET" -> get(path);
                case "HEAD" -> head(path);
                default -> throw new IllegalArgumentException(method);
            };
            if (auth != null && !auth.isEmpty()) {
                builder.header("Authorization", auth);
            }
            for (String header : extraHeaders) {
                int colon = header.indexOf(':');
                builder.header(header.substring(0, colon).trim(),
                        header.substring(colon + 1).trim());
            }
            return mockMvc.perform(builder).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path golden(String name) {
        Path file = Path.of("src/test/resources/contracts", name);
        return Files.exists(file) ? file : Path.of("server/src/test/resources/contracts", name);
    }

    private String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String readGolden(String name) throws Exception {
        return Files.readString(golden(name), StandardCharsets.UTF_8);
    }

    /** JSON 场景：状态 + 体逐字节。 */
    private void compareJson(String goldenName, int expectedStatus, String method, String path,
            String auth, String... extraHeaders) throws Exception {
        MvcResult r = call(method, path, auth, extraHeaders);
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status, body=" + raw(r));
        assertEquals(readGolden(goldenName), raw(r),
                () -> "golden mismatch: " + goldenName);
    }

    /** 二进制场景：体逐字节 + 归一化头部逐行。 */
    private void compareBinary(String goldenName, int expectedStatus, String method, String path,
            String auth, String... extraHeaders) throws Exception {
        MvcResult r = call(method, path, auth, extraHeaders);
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status, body=" + raw(r));
        byte[] expected = Files.readAllBytes(golden(goldenName + ".bin"));
        byte[] actual = r.getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(Arrays.equals(expected, actual),
                () -> goldenName + " body bytes");
        String expectedHeaders = normHeaders(readGolden(goldenName + ".bin.headers"));
        String actualHeaders = normHeaders(headerLines(r));
        assertEquals(expectedHeaders, actualHeaders, () -> goldenName + " headers");
    }

    /** HEAD / 纯头部场景：状态 + 归一化头部 + 空体。 */
    private void compareHead(String goldenName, int expectedStatus, String method, String path,
            String auth, String... extraHeaders) throws Exception {
        MvcResult r = call(method, path, auth, extraHeaders);
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status");
        String expectedHeaders = normHeaders(readGolden(goldenName));
        String actualHeaders = normHeaders(headerLines(r));
        assertEquals(expectedHeaders, actualHeaders, () -> goldenName + " headers");
    }

    private static String headerLines(MvcResult r) {
        StringBuilder sb = new StringBuilder();
        for (String name : r.getResponse().getHeaderNames()) {
            for (String value : r.getResponse().getHeaders(name)) {
                sb.append(name).append(": ").append(value).append('\n');
            }
        }
        return sb.toString();
    }

    private static String normHeaders(String raw) {
        String[] lines = raw.split("\r?\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String l = line.trim();
            String lower = l.toLowerCase(java.util.Locale.ROOT);
            if (l.isEmpty() || lower.startsWith("http/") || lower.startsWith("date:")
                    || lower.startsWith("x-request-id") || lower.startsWith("vary:")
                    || lower.startsWith("keep-alive:") || lower.startsWith("connection:")) {
                continue;
            }
            kept.add(l);
        }
        String[] arr = kept.toArray(new String[0]);
        Arrays.sort(arr);
        return String.join("\n", arr);
    }

    private String createApiKey(String body) {
        try {
            MvcResult r = mockMvc.perform(post("/api/v1/tenants/10002/api-keys")
                            .header("Authorization", owner)
                            .contentType("application/json").content(body))
                    .andReturn();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(r.getResponse().getContentAsString()).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ══════════════ 1. GET /files ══════════════════════════

    @Test
    void filesFamily() throws Exception {
        compareJson("w5c-files-noauth.json", 401, "GET", "/files?file_path=" + TXT_ENC, null);
        compareJson("w5c-files-missing-param.json", 400, "GET", "/files", owner);
        compareJson("w5c-files-traversal.json", 400, "GET",
                "/files?file_path=local://../../etc/passwd", owner);
        compareHead("w5c-files-head.hdr", 404, "HEAD", "/files?file_path=" + TXT_ENC, owner);
        compareJson("w5c-files-crosstenant.json", 403, "GET",
                "/files?file_path=local://77/exports/x.png", owner);
        compareBinary("w5c-files-txt", 200, "GET", "/files?file_path=" + TXT_ENC, owner);
        compareBinary("w5c-files-png", 200, "GET", "/files?file_path=" + PNG_ENC, owner);
        compareBinary("w5c-files-html", 200, "GET",
                "/files?file_path=local://10002/exports/w5c-page.html", owner);
        compareBinary("w5c-files-pdf", 200, "GET",
                "/files?file_path=local://10002/exports/w5c-report.pdf", owner);
        // CJK 文件名直接放查询串（MockMvc 不解码 → 与 Go 的解码后输入一致）
        compareBinary("w5c-files-cjk", 200, "GET",
                "/files?file_path=local://10002/exports/w5c-数据.txt", owner);
        compareBinary("w5c-files-unknownext", 200, "GET",
                "/files?file_path=local://10002/exports/w5c-blob.xyz", owner);
    }

    @Test
    void filesMissingAndWrapper() throws Exception {
        // 对象缺失 → 404 无体
        MvcResult r = call("GET", "/files?file_path=local://10002/exports/no-such.png", owner);
        assertEquals(404, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
        // storage:// 包装 id 不匹配 → 404（backend scoped mismatch）
        r = call("GET", "/files?file_path=storage://00000000-0000-0000-0000-000000000009"
                + "/local://10002/exports/w5c-seed.txt", owner);
        assertEquals(404, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
        // 有效 storage:// 包装 → 200
        compareBinary("w5c-files-storage-ok", 200, "GET",
                "/files?file_path=storage://c730730a-70f5-4d86-a7e1-58972cf27567"
                        + "/local://10002/exports/w5c-seed.txt", owner);
    }

    /** /files 的 API-Key 守卫：KB 受限拒绝、full-access 与 retrieve 直通。 */
    @Test
    void filesApiKeyGuard() throws Exception {
        String full = createApiKey("{\"name\":\"w5c-full\",\"fullAccess\":true}");
        String retr = createApiKey("{\"name\":\"w5c-retrieve\",\"capabilities\":[\"retrieve\"]}");
        String kbre = createApiKey("{\"name\":\"w5c-kbrestricted\",\"capabilities\":[\"retrieve\"],"
                + "\"knowledgeBaseIds\":[\"" + KB_MAIN + "\"]}");
        compareJson("w5c-files-key-kbrestricted.json", 403, "GET",
                "/files?file_path=" + TXT, null, "X-API-Key: " + kbre);
        compareBinary("w5c-files-key-full", 200, "GET", "/files?file_path=" + TXT,
                null, "X-API-Key: " + full);
        compareBinary("w5c-files-key-retrieve", 200, "GET", "/files?file_path=" + TXT,
                null, "X-API-Key: " + retr);
    }

    // ══════════════ 2. Range / 条件请求 ══════════════════════════

    @Test
    void rangeRequests() throws Exception {
        compareBinary("w5c-range-simple", 206, "GET", "/files?file_path=" + TXT_ENC,
                owner, "Range: bytes=4-11");
        compareBinary("w5c-range-suffix", 206, "GET", "/files?file_path=" + TXT_ENC,
                owner, "Range: bytes=-6");
        compareJson("w5c-range-nooverlap.json", 416, "GET", "/files?file_path=" + TXT_ENC,
                owner, "Range: bytes=100-");
        compareBinary("w5c-ifnonematch", 200, "GET", "/files?file_path=" + TXT_ENC,
                owner, "If-None-Match: \"x\"");
    }

    // ══════════════ 3. presigned ══════════════════════════

    @Test
    void presignedErrorShapes() throws Exception {
        compareJson("w5c-pres-missing-params.json", 400, "GET", "/api/v1/files/presigned", null);
        compareJson("w5c-pres-bad-tenant.json", 400, "GET",
                "/api/v1/files/presigned?file_path=x&tenant_id=abc&expires=9999999999&sig=ab", null);
        compareJson("w5c-pres-traversal.json", 400, "GET",
                "/api/v1/files/presigned?file_path=local://../x&tenant_id=10002&expires=9999999999&sig=ab",
                null);
        compareJson("w5c-pres-badsig.json", 403, "GET",
                "/api/v1/files/presigned?file_path=" + TXT_ENC + "&tenant_id=10002&expires=9999999999&sig=deadbeef",
                null);
    }

    // ══════════════ 4. presigned-preview ══════════════════════════

    @Test
    void presignedPreview() throws Exception {
        compareJson("w5c-prev-ok.json", 200, "GET",
                "/api/v1/files/presigned-preview?file_path=" + TXT, owner);
        compareJson("w5c-prev-cjk.json", 200, "GET",
                "/api/v1/files/presigned-preview?file_path=local://10002/exports/w5c-数据.txt", owner);
        compareJson("w5c-prev-minio.json", 200, "GET",
                "/api/v1/files/presigned-preview?file_path=minio://bucket/x.png", owner);
        compareJson("w5c-prev-missing-param.json", 400, "GET",
                "/api/v1/files/presigned-preview", owner);
        compareJson("w5c-prev-unauth.json", 401, "GET",
                "/api/v1/files/presigned-preview?file_path=x", null);
        compareJson("w5c-prev-viewer.json", 403, "GET",
                "/api/v1/files/presigned-preview?file_path=x", viewer);
        compareJson("w5c-prev-backend-missing.json", 400, "GET",
                "/api/v1/files/presigned-preview?file_path=storage://00000000-0000-0000-0000-000000000009"
                        + "/local://10002/x",
                owner);
        compareHead("w5c-prev-head.hdr", 404, "HEAD",
                "/api/v1/files/presigned-preview?file_path=x", owner);
        // API-Key 主体显式拒绝（DenyAPIKeyPrincipal，403 文案独立于门禁）
        String full = createApiKey("{\"name\":\"w5c-full\",\"fullAccess\":true}");
        compareJson("w5c-prev-apikey.json", 403, "GET",
                "/api/v1/files/presigned-preview?file_path=" + TXT, null, "X-API-Key: " + full);
    }

    // ══════════════ 5. /r/:token ══════════════════════════

    @Test
    void resourceGrants() throws Exception {
        compareBinary("w5c-grant-ok", 200, "GET", "/r/w5cgranttoken0000000001", null);
        compareHead("w5c-grant-head.hdr", 200, "HEAD", "/r/w5cgranttoken0000000001", null);
        MvcResult r = call("GET", "/r/unknown00000000000000000", null);
        assertEquals(404, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
        r = call("GET", "/r/w5cexpiredtoken000000001", null);
        assertEquals(404, r.getResponse().getStatus());
        r = call("GET", "/r/w5crevokedtoken000000001", null);
        assertEquals(404, r.getResponse().getStatus());
    }

    // ══════════════ 6. KB-scoped files ══════════════════════════

    @Test
    void kbScopedFiles() throws Exception {
        compareBinary("w5c-kbfiles-ok", 200, "GET",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files?file_path=" + TXT_ENC, owner);
        compareBinary("w5c-kbfiles-viewer", 200, "GET",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files?file_path=" + TXT_ENC, viewer);
        compareJson("w5c-kbfiles-unbound.json", 403, "GET",
                "/api/v1/knowledge-bases/" + KB_NOBIND + "/files?file_path=" + TXT_ENC, owner);
        compareJson("w5c-kbfiles-outside-exports.json", 403, "GET",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files?file_path=local://10002/private/secret.txt",
                owner);
        compareJson("w5c-kbfiles-missing-kb.json", 404, "GET",
                "/api/v1/knowledge-bases/00000000-4444-0000-0000-000000000001/files?file_path=" + TXT_ENC,
                owner);
        compareJson("w5c-kbfiles-missing-param.json", 400, "GET",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files", owner);
        compareHead("w5c-kbfiles-head.hdr", 404, "HEAD",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files?file_path=" + TXT_ENC, owner);
        String kbre = createApiKey("{\"name\":\"w5c-kbrestricted\",\"capabilities\":[\"retrieve\"],"
                + "\"knowledgeBaseIds\":[\"" + KB_MAIN + "\"]}");
        compareJson("w5c-kbfiles-key-kbrestricted.json", 403, "GET",
                "/api/v1/knowledge-bases/" + KB_MAIN + "/files?file_path=" + TXT,
                null, "X-API-Key: " + kbre);
    }

    // ══════════════ 7. message-scoped files ══════════════════════════

    @Test
    void messageScopedFiles() throws Exception {
        compareBinary("w5c-msgfiles-ok", 200, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_REF + "/files?file_path=" + PNG_ENC,
                owner);
        compareBinary("w5c-msgfiles-user", 200, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_USER + "/files?file_path=" + TXT_ENC,
                owner);
        compareBinary("w5c-msgfiles-resource", 200, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_RESREF
                        + "/files?file_path=resource://" + RES_HANDLE, owner);
        compareJson("w5c-msgfiles-notreferenced.json", 403, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_NOREF + "/files?file_path=" + PNG_ENC,
                owner);
        compareJson("w5c-msgfiles-zerotenant.json", 403, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_NOREF + "/files?file_path=" + TXT_ENC,
                owner);
        compareJson("w5c-msgfiles-missing-param.json", 400, "GET",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_REF + "/files", owner);
        compareHead("w5c-msgfiles-head.hdr", 404, "HEAD",
                "/api/v1/sessions/" + SES_ID + "/messages/" + MSG_REF + "/files?file_path=" + PNG_ENC,
                owner);
        // 消息缺失 / 会话缺失 → 404 无体
        MvcResult r = call("GET", "/api/v1/sessions/" + SES_ID
                + "/messages/00000000-0000-0000-0000-000000000099/files?file_path=" + PNG_ENC, owner);
        assertEquals(404, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
        r = call("GET", "/api/v1/sessions/00000000-0000-0000-0000-000000000098/messages/"
                + MSG_REF + "/files?file_path=" + PNG_ENC, owner);
        assertEquals(404, r.getResponse().getStatus());
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
    }
}
