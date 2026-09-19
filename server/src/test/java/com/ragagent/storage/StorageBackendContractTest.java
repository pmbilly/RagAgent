package com.ragagent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * storage-backends 9 条的契约测试（波 2 第五批）。golden：
 * scripts/record-infra-config-golden.sh（39 个 sb-* 文件，Go 实录）。
 *
 * <p>种子复刻 dev PG 录制态：tenants.default_storage_backend_id 指向 System LOCAL
 * （source=env、legacy_alias=true）+ minio 种子行（immutable 用例）+ 固定 hex id。
 * local provider 的 Test 走 LOCAL_STORAGE_BASE_DIR 的 mkdir + 目录检查（确定性成功，
 * 且会在 /tmp 下建 sbgolden 目录——与 Go 录制时的副作用同构）。setdefault 中途会改
 * 租户默认，本测试按录制序在 setdefault-restore 处还原。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageBackendContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String OWNER_EMAIL = "sb-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "sb-contract-viewer@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";
    private static final String SYS_LOCAL = "ab000004-0000-0000-0000-000000000001";
    private static final String SB_MINIO = "be000002-0000-0000-0000-000000000001";

    private static final String API = "/api/v1";

    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([A-Za-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern UUID_BARE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

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
    private String viewer;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("sb-contract-tenant");
        tenant.setStatus("active");
        tenant.setDefaultStorageBackendId(SYS_LOCAL);
        tenantMapper.insert(tenant);

        seedUser(OWNER, "sbowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "sbviewer", VIEWER_EMAIL, "viewer");

        // System LOCAL（env source、legacy alias）——dev PG 既有行的同构投影
        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                + "status, legacy_alias) VALUES (?, ?, 'System LOCAL', 'local', '{}', 'env', 'active', TRUE)",
                SYS_LOCAL, TENANT);
        // minio 种子（immutable 用例；SSRF 会在 endpoint 校验前不可达——immutable 先拦）
        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                        + "status, legacy_alias) VALUES (?, ?, 'sb-golden-minio', 'minio', ?, 'user', 'active', FALSE)",
                SB_MINIO, TENANT,
                "{\"mode\":\"remote\",\"endpoint\":\"http://127.0.0.1:19314\",\"access_key_id\":\"sb-seed-ak\","
                        + "\"secret_access_key\":\"sb-seed-sk\",\"bucket_name\":\"sb-golden-bucket\",\"path_prefix\":\"pp\"}");

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
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

    private String login(String email) {
        try {
            MvcResult result = mockMvc.perform(post(API + "/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                    .andReturn();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString()).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 请求与断言辅助 ──────────────────────────────────────────────────

    private MvcResult perform(String method, String path, String auth, String body) throws Exception {
        var builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (body != null) {
            builder.contentType("application/json").content(body);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private void compareAndStatus(String golden, int expectedStatus, String method, String path,
            String auth, String body) throws Exception {
        MvcResult result = perform(method, path, auth, body);
        assertEquals(expectedStatus, result.getResponse().getStatus(),
                () -> golden + " status, body=" + safeBody(result));
        compare(golden, result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String safeBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private void compare(String golden, String actual) throws Exception {
        Path file = Path.of("src/test/resources/contracts", golden);
        if (!Files.exists(file)) {
            file = Path.of("server/src/test/resources/contracts", golden);
        }
        String expected = mask(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, mask(actual),
                () -> "golden mismatch: " + golden + "\nexpected: " + expected + "\nactual:   " + mask(actual));
    }

    private static String mask(String s) {
        s = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        s = UUID_BARE.matcher(s).replaceAll("<uuid>");
        s = TS_VALUE.matcher(s).replaceAll("\"<ts>\"");
        return s;
    }

    private String createBackend(String body) throws Exception {
        MvcResult result = perform("POST", API + "/storage-backends", owner, body);
        String bodyStr;
        try {
            bodyStr = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            bodyStr = "<unreadable>";
        }
        String diag = bodyStr;
        assertEquals(201, result.getResponse().getStatus(),
                () -> "seed create failed: " + diag);
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private String latestId(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM storage_backends WHERE name = ? AND deleted_at IS NULL", String.class, name);
    }

    // ── 1) 列表（System LOCAL + 默认 id）/ types / 404 / binding / 401 ──

    @Test
    void section1_listAndTypes() throws Exception {
        String base = API + "/storage-backends";
        compareAndStatus("sb-list.json", 200, "GET", base, owner, null);
        compareAndStatus("sb-types.json", 200, "GET", base + "/types", owner, null);
        compareAndStatus("sb-types-viewer.json", 200, "GET", base + "/types", viewer, null);
        compareAndStatus("sb-get-404.json", 404, "GET", base + "/" + UNKNOWN, owner, null);
        compareAndStatus("sb-create-empty.json", 400, "POST", base, owner, "{}");
        compareAndStatus("sb-list-noauth.json", 401, "GET", base, null, null);
    }

    // ── 2) 创建：local 成功 / 409 / 校验失败形态 / viewer 拒绝 ──────────

    @Test
    void section2_create() throws Exception {
        String base = API + "/storage-backends";
        createBackend("{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":"
                + "{\"path_prefix\":\"sbgolden\",\"access_key_id\":\"sb-ak\",\"secret_access_key\":\"sb-sk\"}}");
        createBackend("{\"name\":\"sb-golden-local2\",\"provider\":\"local\",\"config\":{}}");
        compareAndStatus("sb-create-dupname.json", 409, "POST", base, owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{}}");
        compareAndStatus("sb-create-badprovider.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"bogus\",\"config\":{}}");
        compareAndStatus("sb-create-badstatus.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"local\",\"config\":{},\"status\":\"bogus\"}");
        compareAndStatus("sb-create-path-traversal.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"../evil\"}}");
        // minio 单缺失字段（Go 的 map 迭代序随机——多缺失时错误不进契约）
        compareAndStatus("sb-create-minio-missing.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"minio\",\"config\":{\"endpoint\":\"http://minio.example.internal:9000\","
                        + "\"access_key_id\":\"k\",\"secret_access_key\":\"s\"}}");
        compareAndStatus("sb-create-oss-ssrf.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"provider\":\"oss\",\"config\":{\"endpoint\":\"http://127.0.0.1:9000\",\"region\":\"r\","
                        + "\"access_key_id\":\"k\",\"secret_access_key\":\"s\",\"bucket_name\":\"b\"}}");
        compareAndStatus("sb-get.json", 200, "GET",
                base + "/" + latestId("sb-golden-local"), owner, null);
        compareAndStatus("sb-viewer-create.json", 403, "POST", base, viewer,
                "{\"name\":\"x\",\"provider\":\"local\",\"config\":{}}");
    }

    // ── 3) 更新：env 只读 / immutable / *** 占位 / 停用 / 404 ──────────

    @Test
    void section3_update() throws Exception {
        String base = API + "/storage-backends";
        createBackend("{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":"
                + "{\"path_prefix\":\"sbgolden\",\"access_key_id\":\"sb-ak\",\"secret_access_key\":\"sb-sk\"}}");

        compareAndStatus("sb-update-readonly-env.json", 400, "PUT", base + "/" + SYS_LOCAL, owner,
                "{\"name\":\"x\",\"provider\":\"local\",\"config\":{}}");
        compareAndStatus("sb-update-immutable.json", 400, "PUT", base + "/" + SB_MINIO, owner,
                "{\"name\":\"sb-golden-minio\",\"provider\":\"minio\",\"config\":{\"mode\":\"remote\","
                        + "\"endpoint\":\"http://other.example.internal:9000\",\"bucket_name\":\"sb-golden-bucket\","
                        + "\"path_prefix\":\"pp\"}}");
        // *** 占位 → 保留存量密钥（响应仍显 ***）
        compareAndStatus("sb-update-placeholder.json", 200, "PUT", base + "/" + latestId("sb-golden-local"),
                owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\","
                        + "\"access_key_id\":\"***\",\"secret_access_key\":\"***\"}}");
        compareAndStatus("sb-get-after-placeholder.json", 200, "GET",
                base + "/" + latestId("sb-golden-local"), owner, null);
        compareAndStatus("sb-update-badstatus.json", 400, "PUT", base + "/" + latestId("sb-golden-local"),
                owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\"},"
                        + "\"status\":\"bogus\"}");
        compareAndStatus("sb-update-disable.json", 200, "PUT", base + "/" + latestId("sb-golden-local"),
                owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\"},"
                        + "\"status\":\"disabled\"}");
        compareAndStatus("sb-update-404.json", 404, "PUT", base + "/" + UNKNOWN, owner,
                "{\"name\":\"x\",\"provider\":\"local\",\"config\":{}}");
    }

    // ── 4) 默认语义 + test 端点 + 删除守卫 ─────────────────────────────

    @Test
    void section4_defaultTestDelete() throws Exception {
        String base = API + "/storage-backends";
        String local = createBackend("{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":"
                + "{\"path_prefix\":\"sbgolden\",\"access_key_id\":\"sb-ak\",\"secret_access_key\":\"sb-sk\"}}");
        String local2 = createBackend("{\"name\":\"sb-golden-local2\",\"provider\":\"local\",\"config\":{}}");

        // 录制序：setdefault-disabled 之前 local 行已被 section12 停用——本段复刻该前置
        perform("PUT", base + "/" + local, owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\"},"
                        + "\"status\":\"disabled\"}");

        compareAndStatus("sb-setdefault-disabled.json", 400, "PUT", base + "/" + local + "/default", owner, null);
        compareAndStatus("sb-setdefault.json", 200, "PUT", base + "/" + local2 + "/default", owner, null);
        compareAndStatus("sb-list-after-default.json", 200, "GET", base, owner, null);
        compareAndStatus("sb-delete-default.json", 400, "DELETE", base + "/" + local2, owner, null);
        compareAndStatus("sb-setdefault-restore.json", 200, "PUT", base + "/" + SYS_LOCAL + "/default", owner, null);
        compareAndStatus("sb-test-raw-local.json", 200, "POST", base + "/test", owner,
                "{\"name\":\"t\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\"}}");
        compareAndStatus("sb-test-raw-badprovider.json", 400, "POST", base + "/test", owner,
                "{\"name\":\"t\",\"provider\":\"bogus\",\"config\":{}}");
        compareAndStatus("sb-test-raw-minio-missing.json", 400, "POST", base + "/test", owner,
                "{\"name\":\"t\",\"provider\":\"minio\",\"config\":{\"endpoint\":\"http://minio.example.internal:9000\","
                        + "\"access_key_id\":\"k\",\"secret_access_key\":\"s\"}}");
        compareAndStatus("sb-test-raw-ssrf.json", 200, "POST", base + "/test", owner,
                "{\"name\":\"t\",\"provider\":\"s3\",\"config\":{\"endpoint\":\"http://127.0.0.1:9000\",\"region\":\"r\","
                        + "\"access_key_id\":\"k\",\"secret_access_key\":\"s\",\"bucket_name\":\"b\"}}");
        compareAndStatus("sb-test-raw-path-traversal.json", 400, "POST", base + "/test", owner,
                "{\"name\":\"t\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"../evil\"}}");
        compareAndStatus("sb-test-byid.json", 200, "POST", base + "/" + local2 + "/test", owner, null);
        compareAndStatus("sb-test-byid-404.json", 404, "POST", base + "/" + UNKNOWN + "/test", owner, null);
        // 重新启用后删除（默认已还原到 System LOCAL）
        compareAndStatus("sb-delete-enabled.json", 200, "PUT", base + "/" + local, owner,
                "{\"name\":\"sb-golden-local\",\"provider\":\"local\",\"config\":{\"path_prefix\":\"sbgolden\"},"
                        + "\"status\":\"active\"}");
        compareAndStatus("sb-delete.json", 200, "DELETE", base + "/" + local, owner, null);
        compareAndStatus("sb-delete-404.json", 404, "DELETE", base + "/" + UNKNOWN, owner, null);
        compareAndStatus("sb-delete-seed.json", 200, "DELETE", base + "/" + SB_MINIO, owner, null);
    }
}
