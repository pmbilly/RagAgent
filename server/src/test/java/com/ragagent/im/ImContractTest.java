package com.ragagent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
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
 * 波 4.3 im channels 契约测试：清单面 CRUD + toggle + 微信扫码绑定分支
 * （imc-* golden 逐条掩码比对；golden 来源：scripts/record-emb-golden.sh 实录）。
 *
 * <p>场景顺序严格复刻录制脚本：校验错误家族 → 三渠道 create → duplicate /
 * session_mode 校验 → 列表 → update（改名/绑 KB/停用）→ 换绑 agent / 重复 bot →
 * toggle ×2 → delete 家族 → qrcode/status 空体。</p>
 *
 * <p>掩码面：uuid / 时间戳。重复 bot 的 409 文案里的渠道 id 是动态 uuid → 被掩。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final String EMU = "b0000000-0000-0000-0000-000000000601";
    private static final String EMV = "b0000000-0000-0000-0000-000000000602";
    private static final String AG = "b1000000-0000-0000-0000-000000000601";
    private static final String AGP = "b1000000-0000-0000-0000-000000000602";
    private static final String KB_A = "b2000000-0000-0000-0000-000000000601";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern BARE_UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String owner;
    private String viewer;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10006, 'emb-batch-tenant', '', '', 'active'), "
                + "(10007, 'emb-foreign-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'embbatch', 'emb-batch@weknora.test', ?, 10006, true),"
                + "(?, 'embviewer', 'emb-batch-viewer@weknora.test', ?, 10006, true)",
                EMU, BCRYPT, EMV, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10006, 'owner', 'active'), (?, 10006, 'viewer', 'active')", EMU, EMV);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config) VALUES "
                + "(?, 'emb-agent', 'embed batch agent', '', FALSE, 10006, ?, '{}'), "
                + "(?, 'emb-agent-2', 'second agent', '', FALSE, 10006, ?, '{}')",
                AG, EMU, AGP, EMU);
        owner = "Bearer " + login("emb-batch@weknora.test");
        viewer = "Bearer " + login("emb-batch-viewer@weknora.test");
    }

    @Test
    void imChannelsFace() throws Exception {
        // ── 校验错误家族 ──
        assertGolden(postJson("/api/v1/agents/" + AG + "/im-channels", owner, "{}"),
                400, "imc-create-noplat.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/im-channels", owner,
                "{\"platform\":\"msn\",\"name\":\"x\"}"), 400, "imc-create-badplatform.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/im-channels", viewer,
                "{\"platform\":\"slack\"}"), 403, "imc-guard-viewer.json");

        // ── 三个渠道 ──
        MvcResult r = expect(200, postJson("/api/v1/agents/" + AG + "/im-channels", owner,
                "{\"platform\":\"telegram\",\"name\":\"tg-bot\",\"knowledgeBaseId\":\"\","
                        + "\"credentials\":{\"bot_token\":\"7654321:AAEmbtoken123\"},\"enabled\":true}"),
                "imc-create.json");
        String imc1 = jsonPath(r, "data.id");

        expect(200, postJson("/api/v1/agents/" + AG + "/im-channels", owner,
                "{\"platform\":\"wechat\",\"name\":\"wx-bot\","
                        + "\"credentials\":{\"ilink_bot_id\":\"wx-bot-1\"}}"), "imc-create-wechat.json");

        r = expect(200, postJson("/api/v1/agents/" + AGP + "/im-channels", owner,
                "{\"platform\":\"mattermost\",\"name\":\"mm-bot\","
                        + "\"credentials\":{\"outgoing_token\":\"mm-token-1\"}}"),
                "imc-create-mattermost.json");
        String imc3 = jsonPath(r, "data.id");

        assertGolden(postJson("/api/v1/agents/" + AG + "/im-channels", owner,
                "{\"platform\":\"telegram\",\"name\":\"tg-again\","
                        + "\"credentials\":{\"bot_token\":\"7654321:AAEmbtoken123\"}}"), 409,
                "imc-create-dup.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/im-channels", owner,
                "{\"platform\":\"slack\",\"name\":\"bad-mode\",\"session_mode\":\"party\"}"), 500,
                "imc-create-badsessionmode.json");
        r = expect(200, postJson("/api/v1/agents/" + AGP + "/im-channels", owner,
                "{\"platform\":\"telegram\",\"name\":\"tg-two\","
                        + "\"credentials\":{\"bot_token\":\"8888777:BBOtherToken\"}}"),
                "imc-create-tg2.json");
        String imc4 = jsonPath(r, "data.id");

        // ── 列表（Viewer）──
        assertGolden(get("/api/v1/agents/" + AG + "/im-channels", viewer), 200,
                "imc-list-by-agent.json");
        assertGolden(get("/api/v1/im-channels", viewer), 200, "imc-list-all.json");

        // ── update 家族 ──
        assertGolden(putJson("/api/v1/im-channels/" + imc1, owner,
                "{\"name\":\"tg-bot-renamed\",\"knowledgeBaseId\":\"" + KB_A + "\","
                        + "\"credentials\":{\"bot_token\":\"7654321:AAEmbtoken123\"},\"enabled\":false}"),
                200, "imc-update.json");
        assertGolden(putJson("/api/v1/im-channels/b9999999-0000-0000-0000-000000000001", owner,
                "{\"name\":\"x\"}"), 404, "imc-update-404.json");
        assertGolden(putJson("/api/v1/im-channels/" + imc1, owner,
                "{\"agentId\":\"ghost-agent\"}"), 400, "imc-update-badagent.json");
        assertGolden(putJson("/api/v1/im-channels/" + imc4, owner,
                "{\"credentials\":{\"bot_token\":\"7654321:AAEmbtoken123\"}}"), 409,
                "imc-update-dup.json");

        // ── toggle 家族 ──
        assertGolden(post("/api/v1/im-channels/" + imc1 + "/toggle", owner), 200, "imc-toggle.json");
        assertGolden(post("/api/v1/im-channels/" + imc1 + "/toggle", owner), 200, "imc-toggle-off.json");
        assertGolden(post("/api/v1/im-channels/b9999999-0000-0000-0000-000000000001/toggle", owner),
                500, "imc-toggle-404.json");

        // ── delete 家族（脚本删的是 mattermost 渠道 IMC3）──
        assertGolden(delete("/api/v1/im-channels/" + imc3, owner), 200, "imc-delete.json");
        assertGolden(delete("/api/v1/im-channels/b9999999-0000-0000-0000-000000000001", owner),
                500, "imc-delete-404.json");

        // ── 微信扫码（空体绑定分支；出站 iLink 是接缝不录）──
        assertGolden(postJson("/api/v1/wechat/qrcode/status", owner, null), 400,
                "imc-qrcode-status-nobody.json");
    }

    // ═══════════════════ 基建 ═══════════════════

    private String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(raw(result));
        assertTrue(m.find(), "login 响应应含 token: " + raw(result));
        return m.group(1);
    }

    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        expect(status, req, goldenName);
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder req, String goldenName)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        String actual = raw(r);
        String golden = golden(goldenName);
        assertEquals(mask(golden), mask(actual), goldenName);
        return r;
    }

    private static MockHttpServletRequestBuilder get(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(url).header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder post(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder postJson(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);
        return body == null ? b : b.content(body);
    }

    private static MockHttpServletRequestBuilder putJson(String url, String bearer, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(url).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder delete(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(url).header("Authorization", bearer);
    }

    private static String mask(String s) {
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        s = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        // 错误文案里的裸 uuid（如 duplicate-bot 的渠道 id）没有引号包着
        return BARE_UUID_PATTERN.matcher(s).replaceAll("<uuid>");
    }

    private static String jsonPath(MvcResult r, String path) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw(r));
        for (String seg : path.split("\\.")) {
            if (node.isArray() && seg.matches("\\d+")) {
                node = node.get(Integer.parseInt(seg));
            } else {
                node = node.get(seg);
            }
        }
        return node.asText();
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
