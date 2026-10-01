package com.ragagent.vectorstore;

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
 * vector-stores 9 条的契约测试（波 2 第五批）。golden：
 * scripts/record-infra-config-golden.sh（28 个 vs-* 文件，Go 实录）。
 *
 * <p>种子：ES 向量库一行（固定 hex id，connection_config 指向**环回死端口 19214**——
 * test-by-id 的连接拒绝分支确定性）。本部署 RETRIEVE_DRIVER 未配置 → env stores 恒空
 * （部署状态，两个 list golden 都含该种子行）。test-by-id 对 19214 真拨号（环回、
 * 无外部依赖；若本机恰有服务监听 19214 该用例会假红——端口刻意生僻）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class VectorStoreContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String OWNER_EMAIL = "vs-contract-owner@weknora.test";
    private static final String VIEWER_EMAIL = "vs-contract-viewer@weknora.test";
    private static final String UNKNOWN = "11111111-2222-3333-4444-999999999999";
    private static final String VS_ES = "be000001-0000-0000-0000-000000000001";

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
        tenant.setName("vs-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(OWNER, "vsowner", OWNER_EMAIL, "owner");
        seedUser(VIEWER, "vsviewer", VIEWER_EMAIL, "viewer");

        jdbc.update("INSERT INTO vector_stores (id, tenant_id, name, engine_type, connection_config, "
                        + "index_config) VALUES (?, ?, 'vs-golden-es', 'elasticsearch', ?, ?)",
                VS_ES, TENANT,
                "{\"addr\":\"http://127.0.0.1:19214\",\"username\":\"elastic\",\"password\":\"vs-seed-secret\"}",
                "{\"index_name\":\"vs-golden-idx\"}");

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

    /** {@code -Dcontract.refresh=true} 时把掩码后的实际响应写回夹具（换锚批重录用）。 */
    private static final boolean REFRESH_FIXTURES = Boolean.getBoolean("contract.refresh");

    private void compare(String golden, String actual) throws Exception {
        Path file = Path.of("src/test/resources/contracts", golden);
        if (!Files.exists(file)) {
            file = Path.of("server/src/test/resources/contracts", golden);
        }
        if (REFRESH_FIXTURES) {
            Files.writeString(file, mask(actual) + "\n");
            return;
        }
        String expected = mask(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)).strip();
        assertEquals(expected, mask(actual).strip(),
                () -> "golden mismatch: " + golden + "\nexpected: " + expected + "\nactual:   " + mask(actual));
    }

    private static String mask(String s) {
        s = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        s = UUID_BARE.matcher(s).replaceAll("<uuid>");
        s = TS_VALUE.matcher(s).replaceAll("\"<ts>\"");
        return s;
    }

    // ── 1) 静态元数据 + env 形态 + 404 ─────────────────────────────────

    @Test
    void section1_typesAndEnvForms() throws Exception {
        String base = API + "/vector-stores";
        compareAndStatus("vs-types.json", 200, "GET", base + "/types", owner, null);
        compareAndStatus("vs-list-empty.json", 200, "GET", base, owner, null);
        compareAndStatus("vs-get-env-404.json", 404, "GET", base + "/__env_postgres__", owner, null);
        compareAndStatus("vs-put-env-readonly.json", 400, "PUT", base + "/__env_bogus__", owner,
                "{\"name\":\"x\"}");
        compareAndStatus("vs-delete-env-readonly.json", 400, "DELETE", base + "/__env_bogus__", owner, null);
        compareAndStatus("vs-test-env-404.json", 404, "POST", base + "/__env_bogus__/test", owner, null);
        compareAndStatus("vs-get-404.json", 404, "GET", base + "/" + UNKNOWN, owner, null);
        compareAndStatus("vs-update-404.json", 404, "PUT", base + "/" + UNKNOWN, owner, "{\"name\":\"x\"}");
        compareAndStatus("vs-test-byid-404.json", 404, "POST", base + "/" + UNKNOWN + "/test", owner, null);
        compareAndStatus("vs-list-noauth.json", 401, "GET", base, null, null);
    }

    // ── 2) 创建：binding / 引擎白名单 / SSRF（顺序钉住） / 角色门 ───────

    @Test
    void section2_createFailures() throws Exception {
        String base = API + "/vector-stores";
        compareAndStatus("vs-create-empty.json", 400, "POST", base, owner, "{}");
        compareAndStatus("vs-create-sqlite.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"engineType\":\"sqlite\",\"connectionConfig\":{}}");
        compareAndStatus("vs-create-postgres.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"engineType\":\"postgres\",\"connectionConfig\":{\"use_default_connection\":true}}");
        compareAndStatus("vs-create-qdrant-missing-host.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"engineType\":\"qdrant\",\"connectionConfig\":{}}");
        compareAndStatus("vs-create-es-ssrf.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"engineType\":\"elasticsearch\",\"connectionConfig\":{\"addr\":\"http://127.0.0.1:19200\"}}");
        // SSRF（2.1）先于 index 校验（2.5）——顺序由本 golden 钉住
        compareAndStatus("vs-create-badindex-ssrf-first.json", 400, "POST", base, owner,
                "{\"name\":\"x\",\"engineType\":\"elasticsearch\",\"connectionConfig\":{\"addr\":\"http://127.0.0.1:19200\"},"
                        + "\"indexConfig\":{\"index_name\":\"bad name!\"}}");
        compareAndStatus("vs-viewer-create.json", 403, "POST", base, viewer,
                "{\"name\":\"x\",\"engineType\":\"qdrant\",\"connectionConfig\":{\"host\":\"h\"}}");
    }

    // ── 3) 种子行 CRUD + test-by-id 连接拒绝 + raw test + 删除 ─────────

    @Test
    void section3_seededCrudAndTest() throws Exception {
        String base = API + "/vector-stores";
        compareAndStatus("vs-get.json", 200, "GET", base + "/" + VS_ES, owner, null);
        compareAndStatus("vs-list-withdb.json", 200, "GET", base, owner, null);
        compareAndStatus("vs-test-byid-connrefused.json", 200, "POST", base + "/" + VS_ES + "/test", owner, null);
        compareAndStatus("vs-put.json", 200, "PUT", base + "/" + VS_ES, owner,
                "{\"name\":\"vs-golden-es-renamed\"}");
        compareAndStatus("vs-put-badjson.json", 400, "PUT", base + "/" + VS_ES, owner, "");
        compareAndStatus("vs-test-raw-postgres.json", 200, "POST", base + "/test", owner,
                "{\"engineType\":\"postgres\",\"connectionConfig\":{\"use_default_connection\":true}}");
        compareAndStatus("vs-test-raw-es-missing-addr.json", 200, "POST", base + "/test", owner,
                "{\"engineType\":\"elasticsearch\",\"connectionConfig\":{}}");
        compareAndStatus("vs-test-raw-es-ssrf.json", 200, "POST", base + "/test", owner,
                "{\"engineType\":\"elasticsearch\",\"connectionConfig\":{\"addr\":\"http://127.0.0.1:19200\"}}");
        compareAndStatus("vs-test-viewer-denied.json", 403, "POST", base + "/test", viewer,
                "{\"engineType\":\"elasticsearch\",\"connectionConfig\":{\"addr\":\"http://127.0.0.1:19200\"}}");
        compareAndStatus("vs-delete.json", 204, "DELETE", base + "/" + VS_ES, owner, null);
        compareAndStatus("vs-get-after-delete.json", 404, "GET", base + "/" + VS_ES, owner, null);
    }
}
