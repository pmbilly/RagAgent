package com.ragagent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.TestSchema;
import com.ragagent.knowledge.storage.LocalStorageService;

/**
 * 收尾批 W5d 契约测试：embed QA 委托 / 文件代理（w5d-emb-*）。golden 来源：Go
 * dev server 实录（scripts/record-w5d-golden.sh）。沙箱终端族（w5d-term-*）
 * 随沙箱裁剪退役。
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5dTerminalEmbedContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10008L;
    private static final String WU = "b0000000-0000-0000-0000-000000000801";
    private static final String SES_A = "b5000000-0000-0000-0000-000000000801";
    private static final String SES_B = "b5000000-0000-0000-0000-000000000802";
    private static final String GHOST = "b5999999-0000-0000-0000-000000000801";
    private static final String AG8 = "b1000000-0000-0000-0000-000000000801";
    private static final String CID8 = "b4000000-0000-0000-0000-000000000801";
    private static final String ESID8 = "b5000000-0000-0000-0000-000000000803";
    private static final String PTOKEN = "w5dpublishtoken0000000001";
    private static final String AG8_CONFIG = "{\"kb_selection_mode\":\"all\","
            + "\"web_search_enabled\":false,\"image_upload_enabled\":false}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LocalStorageService localStorage;

    private String owner;
    private String esig;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10008, 'w5d-batch-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) "
                + "VALUES (?, 'w5dbatch', 'w5d-batch@weknora.test', ?, 10008, true)", WU, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10008, 'owner', 'active')", WU);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, "
                + "tenant_id, created_by, config) VALUES "
                + "(?, 'w5d-agent', 'w5d embed agent', '', FALSE, 10008, ?, ?)", AG8, WU, AG8_CONFIG);
        jdbc.update("INSERT INTO sessions (id, tenant_id, title, description, user_id) VALUES "
                + "(?, 10008, 'w5d-term', '', ?), (?, 10008, 'w5d-term-b', '', ?)",
                SES_A, WU, SES_B, WU);
        jdbc.update("INSERT INTO embed_channels (id, tenant_id, agent_id, name, enabled, "
                + "publish_token, allowed_origins, allow_web_search, allow_file_upload) VALUES "
                + "(?, 10008, ?, 'w5d-emb', TRUE, ?, '[\"https://a.example.com\"]', TRUE, FALSE)",
                CID8, AG8, PTOKEN);
        jdbc.update("INSERT INTO sessions (id, tenant_id, title, description, user_id) VALUES "
                + "(?, 10008, '', 'embed_channel:" + CID8 + "', '')", ESID8);

        Path dir = localStorage.baseDir().resolve("10008/exports");
        Files.createDirectories(dir);
        Files.write(dir.resolve("w5d-embed-seed.txt"), "w5d embed seed\n".getBytes(StandardCharsets.UTF_8));

        owner = "Bearer " + login("w5d-batch@weknora.test");
        esig = sign(PTOKEN, CID8, ESID8);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private String login(String email) {
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                    .andReturn();
            return new ObjectMapper()
                    .readTree(result.getResponse().getContentAsString()).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** X-Embed-Session 签名 = HMAC-SHA256(publish_token, "<cid>|<sid>") base64url 无填充。 */
    private static String sign(String key, String cid, String sid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((cid + "|" + sid).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path golden(String name) {
        Path file = Path.of("src/test/resources/contracts", name);
        return Files.exists(file) ? file : Path.of("server/src/test/resources/contracts", name);
    }

    private String readGolden(String name) throws Exception {
        return Files.readString(golden(name), StandardCharsets.UTF_8);
    }

    private String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcResult call(String method, String path, String... headers) {
        try {
            var builder = "POST".equals(method) ? post(path) : get(path);
            for (String header : headers) {
                int colon = header.indexOf(':');
                builder.header(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
            return mockMvc.perform(builder).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult callJson(String path, String body, String... headers) {
        try {
            var builder = post(path).contentType("application/json");
            if (body != null) {
                builder.content(body);
            }
            for (String header : headers) {
                int colon = header.indexOf(':');
                builder.header(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
            return mockMvc.perform(builder).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON 场景：状态 + 体逐字节（mask 为 null 时原样）。 */
    private void compareJson(String goldenName, int expectedStatus, MvcResult r,
            java.util.function.UnaryOperator<String> mask) throws Exception {
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status, body=" + raw(r));
        String expected = readGolden(goldenName);
        String actual = raw(r);
        if (mask != null) {
            expected = mask.apply(expected);
            actual = mask.apply(actual);
        }
        assertEquals(expected, actual, () -> "golden mismatch: " + goldenName);
    }

    private void compareJson(String goldenName, int expectedStatus, MvcResult r) throws Exception {
        compareJson(goldenName, expectedStatus, r, null);
    }

    private String[] embedHeaders() {
        return new String[] {"Authorization: Embed " + PTOKEN, "Origin: https://a.example.com"};
    }

    private String[] embedSessionHeaders() {
        return new String[] {"Authorization: Embed " + PTOKEN, "Origin: https://a.example.com",
                "X-Embed-Session: " + esig};
    }

    // ══════════════ 一、embed QA 委托 + 文件代理 ══════════════

    @Test
    void embedChatDelegation() throws Exception {
        compareJson("w5d-emb-chat-404.json", 404,
                callJson("/api/v1/embed/" + CID8 + "/knowledge-chat/" + GHOST, "{}",
                        "Authorization: Embed " + PTOKEN, "Origin: https://a.example.com",
                        "X-Embed-Session: x"));
        compareJson("w5d-emb-chat-bad-sig.json", 403,
                callJson("/api/v1/embed/" + CID8 + "/knowledge-chat/" + ESID8, "{}",
                        "Authorization: Embed " + PTOKEN, "Origin: https://a.example.com",
                        "X-Embed-Session: bogus"));
        compareJson("w5d-emb-chat-invalid-json.json", 400,
                callJson("/api/v1/embed/" + CID8 + "/knowledge-chat/" + ESID8, "not-json",
                        embedSessionHeaders()));
        compareJson("w5d-emb-chat-scalar.json", 400,
                callJson("/api/v1/embed/" + CID8 + "/knowledge-chat/" + ESID8, "123",
                        embedSessionHeaders()));
        // 委托后确定性错误：patch 成功 → KnowledgeQA/AgentQA 的 validator 文案（Go 字段名）
        compareJson("w5d-emb-chat-kb-empty.json", 400,
                callJson("/api/v1/embed/" + CID8 + "/knowledge-chat/" + ESID8, "{}",
                        embedSessionHeaders()));
        compareJson("w5d-emb-chat-agent-empty.json", 400,
                callJson("/api/v1/embed/" + CID8 + "/agent-chat/" + ESID8, "{}",
                        embedSessionHeaders()));
    }

    @Test
    void embedFiles() throws Exception {
        compareJson("w5d-emb-files-no-path.json", 400,
                call("GET", "/api/v1/embed/" + CID8 + "/files", embedHeaders()));
        compareJson("w5d-emb-files-dotdot.json", 400,
                call("GET", "/api/v1/embed/" + CID8 + "/files?file_path=local://10008/exports/../x",
                        embedHeaders()));
        compareJson("w5d-emb-files-cross-tenant.json", 403,
                call("GET", "/api/v1/embed/" + CID8 + "/files?file_path=local://77/exports/x.png",
                        embedHeaders()));

        // 404 空体（Go c.Status(404)；plainStatus 提交空响应，Tomcat 阀门不补默认体）
        MvcResult missing = call("GET",
                "/api/v1/embed/" + CID8 + "/files?file_path=local://10008/exports/no-such.png",
                embedHeaders());
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals(readGolden("w5d-emb-files-missing.json"), raw(missing),
                "files-missing body must be empty");

        // 200 种子文件：体逐字节 + 关键头
        MvcResult ok = call("GET",
                "/api/v1/embed/" + CID8 + "/files?file_path=local://10008/exports/w5d-embed-seed.txt",
                embedHeaders());
        assertEquals(200, ok.getResponse().getStatus());
        assertEquals("w5d embed seed\n", raw(ok), "files-ok body");
        assertEquals("public, max-age=86400", ok.getResponse().getHeader("Cache-Control"));
        assertEquals("inline; filename=w5d-embed-seed.txt",
                ok.getResponse().getHeader("Content-Disposition"));
        assertEquals("nosniff", ok.getResponse().getHeader("X-Content-Type-Options"));
    }

    // 头比对辅助（保留给后续头部场景；当前场景断言关键头即可）
    @SuppressWarnings("unused")
    private static String normHeaders(String raw) {
        String[] lines = raw.split("\r?\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String l = line.trim();
            String lower = l.toLowerCase(java.util.Locale.ROOT);
            if (l.isEmpty() || lower.startsWith("http/") || lower.startsWith("date:")
                    || lower.startsWith("x-request-id") || lower.startsWith("vary:")
                    || lower.startsWith("access-control-") || lower.startsWith("keep-alive:")
                    || lower.startsWith("connection:")) {
                continue;
            }
            kept.add(l);
        }
        String[] arr = kept.toArray(new String[0]);
        Arrays.sort(arr);
        return String.join("\n", arr);
    }
}
