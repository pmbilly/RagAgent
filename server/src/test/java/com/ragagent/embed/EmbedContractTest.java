package com.ragagent.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 4.3 embed 契约测试：管理面（emb-mgmt-*）+ 公开面（emb-pub-*），golden 逐条
 * 掩码比对。golden 来源：Go dev server 实录（scripts/record-emb-golden.sh）。
 *
 * <p>场景顺序严格复刻录制脚本（同请求序列有状态依赖）：
 * 管理面 → 渠道标记回填（SQL）→ 公开面。禁用态/推荐问题开关的状态转换
 * （create quirk：default:true 走 DB 默认；update 不带 allowed_origins 清空 allowlist）
 * 都发生在请求序列中间，不可拆分重放。</p>
 *
 * <p>掩码面：uuid / 时间戳 / publish token（em_…）/ session token（ems_…）/ sig。</p>
 *
 * <p><b>刻意不重放</b>：emb-pub-load-badvisitor（Go 的 400 Bad Request 是 HTTP 服务器
 * 对畸形头行的裸拒绝，属容器层，MockMvc 不经容器解析；A/B 侧对真实栈验证）、
 * mcp-oauth-resolutions ×2 与 tool-approvals 的 gate 依赖分支（录制脚本本就未录）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = EmbedContractTest.TokenStoreTestConfig.class)
class EmbedContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!

    // 固定种子 id（对照录制脚本，两侧同 id）
    private static final String EMU = "b0000000-0000-0000-0000-000000000601";
    private static final String EMV = "b0000000-0000-0000-0000-000000000602";
    private static final String FMO = "b0000000-0000-0000-0000-000000000701";
    private static final String AG = "b1000000-0000-0000-0000-000000000601";
    private static final String AGP = "b1000000-0000-0000-0000-000000000602";
    private static final String AGF = "b1000000-0000-0000-0000-000000000701";
    private static final String KB_A = "b2000000-0000-0000-0000-000000000601";
    private static final String KB_B = "b2000000-0000-0000-0000-000000000602";
    private static final String KN_A = "b3000000-0000-0000-0000-000000000601";
    private static final String KN_B = "b3000000-0000-0000-0000-000000000602";
    private static final String KN_F = "b3000000-0000-0000-0000-000000000701";
    private static final String CH_A = "b4000000-0000-0000-0000-000000000601";
    private static final String CH_B = "b4000000-0000-0000-0000-000000000602";
    private static final String CH_F = "b4000000-0000-0000-0000-000000000701";
    private static final String SES_MAIN = "b5000000-0000-0000-0000-000000000601";
    private static final String SES_OTHER = "b5000000-0000-0000-0000-000000000602";
    private static final String SES_OFF = "b5000000-0000-0000-0000-000000000603";
    private static final String MSG_DONE = "b6000000-0000-0000-0000-000000000601";
    private static final String SSET = "b7000000-0000-0000-0000-000000000601";

    private static final String AG_CONFIG = "{\"kb_selection_mode\":\"selected\",\"knowledge_bases\":"
            + "[\"" + KB_A + "\"],\"web_search_enabled\":true,\"image_upload_enabled\":false,"
            + "\"question_suggestions\":{\"starters\":{\"enabled\":true,\"mode\":\"curated\","
            + "\"items\":[\"怎么 绑定 手机？\",\"如何 重置 密码？\"]}}}";
    private static final String AGP_CONFIG =
            "{\"kb_selection_mode\":\"all\",\"web_search_enabled\":false,\"image_upload_enabled\":false}";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern BARE_UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern PUBLISH_TOKEN_PATTERN = Pattern.compile("em_[A-Za-z0-9_-]{20,}");
    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile("ems_[A-Za-z0-9_-]{20,}");
    private static final Pattern SIG_PATTERN = Pattern.compile("(\"sig\":\")[A-Za-z0-9_-]{20,}\"");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String owner;
    private String viewer;

    // ═══════════════════ 测试内替换的 token store（无 Redis 的 H2 环境） ═══════════════════

    @TestConfiguration
    static class TokenStoreTestConfig {
        @Bean
        @Primary
        com.ragagent.embed.service.EmbedTokenStore memoryTokenStore() {
            return new com.ragagent.embed.service.EmbedTokenStore() {
                private final Map<String, String> store = new ConcurrentHashMap<>();

                @Override
                public void put(String token, String channelId, Duration ttl) {
                    store.put(token, channelId);
                }

                @Override
                public String get(String token) {
                    return store.get(token);
                }
            };
        }
    }

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10006, 'emb-batch-tenant', '', '', 'active'), "
                + "(10007, 'emb-foreign-tenant', '', '', 'active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'embbatch', 'emb-batch@weknora.test', ?, 10006, true),"
                + "(?, 'embviewer', 'emb-batch-viewer@weknora.test', ?, 10006, true),"
                + "(?, 'embforeign', 'emb-foreign@weknora.test', ?, 10007, true)",
                EMU, BCRYPT, EMV, BCRYPT, FMO, BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?, 10006, 'owner', 'active'), (?, 10006, 'viewer', 'active'), "
                + "(?, 10007, 'owner', 'active')", EMU, EMV, FMO);
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config) VALUES "
                + "(?, 'emb-agent', 'embed batch agent', '', FALSE, 10006, ?, ?), "
                + "(?, 'emb-agent-2', 'second agent', '', FALSE, 10006, ?, ?), "
                + "(?, 'foreign-agent', 'other tenant agent', '', FALSE, 10007, ?, '{}')",
                AG, EMU, AG_CONFIG, AGP, EMU, AGP_CONFIG, AGF, FMO);
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, "
                + "chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES "
                + "(?, 'emb-kb-a', 10006, 'document', '', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'), "
                + "(?, 'emb-kb-b', 10006, 'document', '', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                KB_A, EMU, KB_B, EMU);
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, "
                + "description, source, parse_status, enable_status) VALUES "
                + "(?, 10006, ?, 'document', 'kn-a', '', 'manual', 'completed', 'enabled'), "
                + "(?, 10006, ?, 'document', 'kn-b', '', 'manual', 'completed', 'enabled'), "
                + "(?, 10007, 'b2000000-0000-0000-0000-000000000701', 'document', 'kn-f', '', "
                + "'manual', 'completed', 'enabled')",
                KN_A, KB_A, KN_B, KB_B, KN_F);
        jdbc.update("INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, "
                + "content, chunk_type, is_enabled, flags, status, chunk_index, start_at, end_at, "
                + "metadata, created_at, updated_at) VALUES "
                + "(?, 990001, 10006, ?, ?, '允许读取的正文内容', 'text', TRUE, 1, 0, 0, 0, 8, '{}', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'), "
                + "(?, 990002, 10006, ?, ?, '未选中知识库的正文', 'text', TRUE, 1, 0, 0, 0, 9, '{}', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'), "
                + "(?, 990003, 10007, ?, 'b2000000-0000-0000-0000-000000000701', '隔壁租户的正文', "
                + "'text', TRUE, 1, 0, 0, 0, 9, '{}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                CH_A, KN_A, KB_A, CH_B, KN_B, KB_B, CH_F, KN_F);
        jdbc.update("INSERT INTO sessions (id, tenant_id, title, description, user_id, created_at, "
                + "updated_at) VALUES "
                + "(?, 10006, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'), "
                + "(?, 10006, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'), "
                + "(?, 10006, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                SES_MAIN, SES_OTHER, SES_OFF);
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, is_completed, "
                + "agent_duration_ms, channel, rendered_content, agent_id, agent_tenant_id, model_id, "
                + "execution_context, knowledge_references, mentioned_items, images, attachments, "
                + "artifacts, created_at, updated_at) VALUES "
                + "(?, 'b6f00000-0000-0000-0000-000000000601', ?, 'assistant', '已完成回答', TRUE, 0, "
                + "'', '', '', 0, '', '{}', '[]', '[]', '[]', '[]', '[]', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                MSG_DONE, SES_MAIN);
        owner = "Bearer " + login("emb-batch@weknora.test");
        viewer = "Bearer " + login("emb-batch-viewer@weknora.test");
    }

    // ═══════════════════ 一、管理面 ═══════════════════

    @Test
    void managementFace() throws Exception {
        // 校验错误家族（顺序对照脚本）
        assertGolden(postJson("/api/v1/agents/" + AG + "/embed-channels", owner,
                "{\"name\":\"no origins\"}"), 400, "emb-mgmt-create-missing-origin.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/embed-channels", owner,
                "{\"name\":\"bad\",\"allowed_origins\":[\"not a url\"]}"), 400,
                "emb-mgmt-create-bad-origin.json");
        assertGolden(postJson("/api/v1/agents/ghost-agent/embed-channels", owner,
                "{\"name\":\"x\",\"allowed_origins\":[\"https://a.example.com\"]}"), 500,
                "emb-mgmt-create-bad-agent.json");
        assertGolden(postJson("/api/v1/agents/" + AGF + "/embed-channels", owner,
                "{\"name\":\"x\",\"allowed_origins\":[\"https://a.example.com\"]}"), 500,
                "emb-mgmt-create-cross-agent.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/embed-channels", owner,
                "{\"name\":\"x\",\"allowed_origins\":[\"https://a.example.com\"],"
                        + "\"launcher_icon\":\"data:text/html;base64,PGI+\"}"), 400,
                "emb-mgmt-create-bad-icon.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/embed-channels", viewer,
                "{\"name\":\"x\",\"allowed_origins\":[\"https://a.example.com\"]}"), 403,
                "emb-mgmt-guard-viewer.json");
        assertGolden(postJson("/api/v1/agents/" + AG + "/embed-channels", null,
                "{\"name\":\"x\",\"allowed_origins\":[\"https://a.example.com\"]}"), 401,
                "emb-mgmt-noauth.json");

        // 三个 create（完整/minimal/disabled）
        MvcResult r = expect(201, postJson("/api/v1/agents/" + AG + "/embed-channels", owner,
                "{\"name\":\"emb-main\",\"allowed_origins\":[\"https://a.example.com\","
                        + "\"*.b.example.com\"],\"welcome_message\":\"你好\",\"rate_limit_per_minute\":500,"
                        + "\"rate_limit_per_day\":9000,\"primary_color\":\"#0052d9\","
                        + "\"page_title\":\"帮助中心\",\"header_title_mode\":\"session\","
                        + "\"show_thinking\":true,\"widget_position\":\"bottom-left\","
                        + "\"allow_web_search\":true,\"allow_file_upload\":true,"
                        + "\"default_locale\":\"zh-CN\",\"webhook_url\":\"https://hook.example.com/embed\","
                        + "\"webhook_secret\":\"sec123\",\"launcher_icon\":\"\"}"),
                "emb-mgmt-create.json");
        String cid = jsonPath(r, "data.id");
        String ptoken = jsonPath(r, "data.publish_token");

        r = expect(201, postJson("/api/v1/agents/" + AGP + "/embed-channels", owner,
                "{\"name\":\"emb-min\",\"allowed_origins\":[\"*\"]}"),
                "emb-mgmt-create-minimal.json");
        String cidMin = jsonPath(r, "data.id");
        ptokenMin = jsonPath(r, "data.publish_token");

        r = expect(201, postJson("/api/v1/agents/" + AGP + "/embed-channels", owner,
                "{\"name\":\"emb-off\",\"allowed_origins\":[\"https://c.example.com\"],"
                        + "\"enabled\":false,\"show_suggested_questions\":false}"),
                "emb-mgmt-create-disabled.json");
        String cidOff = jsonPath(r, "data.id");
        ptokenOff = jsonPath(r, "data.publish_token");

        // ⚠️ golden 钉死的 quirk：create 的 enabled:false / show_suggested_questions:false
        // 走 DB 默认 true；禁用态只能经 Update 达成；update 不带 allowed_origins → null 覆写
        assertGolden(putJson("/api/v1/embed-channels/" + cidOff, owner,
                "{\"enabled\":false,\"show_suggested_questions\":false}"), 200,
                "emb-mgmt-update-disable.json");
        assertGolden(putJson("/api/v1/embed-channels/" + cidMin, owner,
                "{\"show_suggested_questions\":false,\"allowed_origins\":[\"*\"]}"), 200,
                "emb-mgmt-update-sugg-off.json");

        // 渠道标记回填（对照脚本的 SQL UPDATE）
        jdbc.update("UPDATE sessions SET description = ?, user_id = ? WHERE id = ?",
                "embed_channel:" + cid, "embed_session:10006:" + cid + ":" + SES_MAIN, SES_MAIN);
        jdbc.update("UPDATE sessions SET description = ?, user_id = ? WHERE id = ?",
                "embed_channel:" + cidMin, "embed_session:10006:" + cidMin + ":" + SES_OTHER, SES_OTHER);
        jdbc.update("UPDATE sessions SET description = ?, user_id = ? WHERE id = ?",
                "embed_channel:" + cidOff, "embed_session:10006:" + cidOff + ":" + SES_OFF, SES_OFF);

        String sigMain = sig(ptoken, cid, SES_MAIN);
        sigOther = sig(ptokenMin, cidMin, SES_OTHER);

        // 列表 / 详情
        assertGolden(get("/api/v1/agents/" + AG + "/embed-channels", viewer), 200,
                "emb-mgmt-list-by-agent.json");
        assertGolden(get("/api/v1/embed-channels", viewer), 200, "emb-mgmt-list-all.json");
        assertGolden(get("/api/v1/embed-channels/" + cid, viewer), 200, "emb-mgmt-get.json");
        assertGolden(get("/api/v1/embed-channels/b9999999-0000-0000-0000-000000000001", viewer),
                404, "emb-mgmt-get-404.json");

        // update 家族
        assertGolden(putJson("/api/v1/embed-channels/" + cid, owner,
                "{\"name\":\"emb-main-renamed\",\"welcome_message\":\"欢迎回来\","
                        + "\"allowed_origins\":[\"https://a.example.com\",\"*.b.example.com\","
                        + "\"https://c.example.com\"],\"primary_color\":\"#1177ee\","
                        + "\"show_thinking\":false}"), 200, "emb-mgmt-update.json");
        assertGolden(putJson("/api/v1/embed-channels/" + cid, owner,
                "{\"webhook_url\":\"ftp://hook.example.com/x\"}"), 400,
                "emb-mgmt-update-bad-webhook.json");
        assertGolden(putJson("/api/v1/embed-channels/b9999999-0000-0000-0000-000000000001", owner,
                "{\"name\":\"x\"}"), 404, "emb-mgmt-update-404.json");

        // rotate → 后续公开面用新 token
        r = expect(200, post("/api/v1/embed-channels/" + cid + "/rotate-token", owner, null),
                "emb-mgmt-rotate.json");
        ptoken = jsonPath(r, "data.publish_token");
        sigMain = sig(ptoken, cid, SES_MAIN);

        // preview / stats
        assertGolden(post("/api/v1/embed-channels/" + cid + "/preview-session", viewer, null),
                200, "emb-mgmt-preview.json");
        assertGolden(post("/api/v1/embed-channels/" + cidOff + "/preview-session", viewer, null),
                403, "emb-mgmt-preview-disabled.json");
        assertGolden(post("/api/v1/embed-channels/b9999999-0000-0000-0000-000000000001/preview-session",
                viewer, null), 404, "emb-mgmt-preview-404.json");
        assertGolden(get("/api/v1/embed-channels/" + cid + "/stats", viewer), 200,
                "emb-mgmt-stats.json");
        assertGolden(get("/api/v1/embed-channels/b9999999-0000-0000-0000-000000000001/stats", viewer),
                404, "emb-mgmt-stats-404.json");

        // 公开面（在 rotate 之后的新 token 下）
        publicFace(cid, ptoken, cidMin, cidOff, sigMain);

        // delete → get-deleted
        assertGolden(delete("/api/v1/embed-channels/" + cidMin, owner), 200, "emb-mgmt-delete.json");
        assertGolden(get("/api/v1/embed-channels/" + cidMin, viewer), 404, "emb-mgmt-get-deleted.json");
    }

    // ═══════════════════ 二、公开面 ═══════════════════

    private void publicFace(String cid, String ptoken, String cidMin, String cidOff,
                            String sigMain) throws Exception {
        String ea = "Embed " + ptoken;
        String origin = "https://a.example.com";

        assertGolden(get("/api/v1/embed/" + cid + "/config", null).header("Origin", origin),
                401, "emb-pub-noauth.json");
        assertGolden(get("/api/v1/embed/" + cid + "/config", null)
                .header("Authorization", "Embed em_wrongtoken").header("Origin", origin),
                401, "emb-pub-badtoken.json");
        assertGolden(get("/api/v1/embed/" + cid + "/config", null)
                .header("Authorization", ea).header("Origin", "https://evil.example"),
                403, "emb-pub-badorigin.json");
        assertGolden(post("/api/v1/embed/" + cidOff + "/exchange", null, null)
                .header("Authorization", "Embed " + ptokenOff)
                .header("Origin", "https://c.example.com"), 403, "emb-pub-disabled.json");
        assertGolden(get("/api/v1/embed/" + cid + "/config", null)
                .header("Authorization", ea).header("Origin", origin), 200, "emb-pub-config.json");

        MvcResult r = expect(200, post("/api/v1/embed/" + cid + "/exchange", null, null)
                .header("Authorization", ea).header("Origin", origin), "emb-pub-exchange.json");
        String stoken = jsonPath(r, "data.session_token");
        assertGolden(post("/api/v1/embed/" + cid + "/exchange", null, null)
                .header("Authorization", "Embed " + stoken).header("Origin", origin), 403,
                "emb-pub-exchange-session-token.json");
        assertGolden(post("/api/v1/embed/" + cid + "/exchange", null, null)
                .header("Authorization", "Bearer " + ptoken).header("Origin", origin), 401,
                "emb-pub-exchange-bearer.json");

        assertGolden(get("/api/v1/embed/" + cidMin + "/suggested-questions", null)
                .header("Authorization", "Embed " + ptokenMin).header("Origin", origin), 200,
                "emb-pub-suggested-off.json");
        assertGolden(get("/api/v1/embed/" + cid + "/suggested-questions", null)
                .header("Authorization", ea).header("Origin", origin), 200, "emb-pub-suggested.json");
        assertGolden(get("/api/v1/embed/" + cid + "/suggested-questions", null)
                .param("limit", "99").header("Authorization", ea).header("Origin", origin), 200,
                "emb-pub-suggested-limit.json");

        assertGolden(get("/api/v1/embed/" + cid + "/chunks/b4999999-0000-0000-0000-000000000001",
                null).header("Authorization", ea).header("Origin", origin), 404, "emb-pub-chunk-404.json");
        assertGolden(get("/api/v1/embed/" + cid + "/chunks/" + CH_A, null)
                .header("Authorization", ea).header("Origin", origin), 200, "emb-pub-chunk-ok.json");
        assertGolden(get("/api/v1/embed/" + cid + "/chunks/" + CH_B, null)
                .header("Authorization", ea).header("Origin", origin), 403, "emb-pub-chunk-forbidden.json");
        assertGolden(get("/api/v1/embed/" + cid + "/chunks/" + CH_F, null)
                .header("Authorization", ea).header("Origin", origin), 403,
                "emb-pub-chunk-cross-tenant.json");

        assertGolden(post("/api/v1/embed/" + cid + "/sessions", null, null)
                .header("Authorization", ea).header("Origin", origin), 201, "emb-pub-create-session.json");

        assertGolden(get("/api/v1/embed/" + cid + "/messages/" + SES_MAIN + "/load", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 200, "emb-pub-load.json");
        assertGolden(get("/api/v1/embed/" + cid + "/messages/" + SES_MAIN + "/load", null)
                .header("Authorization", ea).header("Origin", origin), 403, "emb-pub-load-nosig.json");
        assertGolden(get("/api/v1/embed/" + cid + "/messages/" + SES_MAIN + "/load", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", "bogus"), 403, "emb-pub-load-badsig.json");
        assertGolden(get("/api/v1/embed/" + cid + "/messages/" + SES_OTHER + "/load", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigOther), 403, "emb-pub-load-othersession.json");
        assertGolden(get("/api/v1/embed/" + cid + "/messages/b5999999-0000-0000-0000-000000000001/load",
                null).header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", "x"), 404, "emb-pub-load-404.json");
        // （emb-pub-load-badvisitor：容器层畸形头拒绝，MockMvc 不重放，A/B 验证）

        // 停止已结束的消息：会话域换锚后是 204（无响应体）
        MvcResult stopped = mockMvc.perform(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN
                + "/stop", null, "{\"messageId\":\"" + MSG_DONE + "\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain)).andReturn();
        assertEquals(204, stopped.getResponse().getStatus(), raw(stopped));
        assertEquals("", raw(stopped), "204 必须无响应体");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/stop", null,
                "{\"messageId\":\"b6999999-0000-0000-0000-000000000001\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 404, "emb-pub-stop-404.json");
        assertGolden(post("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/stop", null, null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-stop-nobody.json");

        assertGolden(get("/api/v1/embed/" + cidMin + "/sessions/" + SES_OTHER + "/messages/"
                + MSG_DONE + "/suggestions", null)
                .header("Authorization", "Embed " + ptokenMin).header("Origin", origin)
                .header("X-Embed-Session", sigOther), 200, "emb-pub-suggestions-off.json");
        assertGolden(get("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/messages/"
                + "b6999999-0000-0000-0000-000000000009/suggestions", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 404, "emb-pub-suggestions-get-none.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/messages/"
                + MSG_DONE + "/suggestions", null, "not-json")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-suggestions-ensure-badbody.json");

        // 种推荐问题集（对照脚本 SQL）
        jdbc.update("INSERT INTO message_suggestion_sets (id, tenant_id, session_id, "
                + "assistant_message_id, agent_id, agent_tenant_id, placement, config_hash, locale, "
                + "status, allow_regenerate, suppression_reason, questions, model_id, prompt_tokens, "
                + "completion_tokens, latency_ms, error_code, created_at, updated_at) VALUES "
                + "(?, 10006, ?, ?, ?, 10006, 'after_answer', 'no-agent-config', 'zh-CN', 'ready', "
                + "TRUE, '', '[{\"id\":\"q1\",\"question\":\"还想了解什么？\"}]', '', 0, 0, 0, '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                SSET, SES_MAIN, MSG_DONE, AG);

        assertGolden(get("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/messages/"
                + MSG_DONE + "/suggestions", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 200, "emb-pub-suggestions-get.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/suggestion-events",
                null, "{\"suggestion_set_id\":\"" + SSET + "\",\"question_id\":\"q1\","
                        + "\"event_type\":\"impression\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 204, "emb-pub-suggestion-events.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/suggestion-events",
                null, "not-json")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-suggestion-events-badbody.json");

        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/events", null,
                "{\"type\":\"message_deleted\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-events-badtype.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/events", null,
                "not-json")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-events-badbody.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/events", null,
                "{\"type\":\"message_sent\",\"query\":\"你好\",\"content\":\"收到\","
                        + "\"session_id\":\"\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 200, "emb-pub-events-ok.json");

        String svcOauth = "b8000000-0000-0000-0000-000000000601";
        assertGolden(post("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/mcp-services/"
                + svcOauth + "/oauth/authorize-url", null, null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 400, "emb-pub-mcp-authorize-nobody.json");
        assertGolden(postJson("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/mcp-services/"
                + svcOauth + "/oauth/authorize-url", null,
                "{\"redirect_uri\":\"https://a.example.com/oauth\",\"frontend_redirect\":\"\"}")
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 404, "emb-pub-mcp-authorize-unknown.json");
        assertGolden(get("/api/v1/embed/" + cid + "/sessions/" + SES_MAIN + "/mcp-services/"
                + svcOauth + "/oauth/status", null)
                .header("Authorization", ea).header("Origin", origin)
                .header("X-Embed-Session", sigMain), 200, "emb-pub-mcp-status.json");
    }

    // create-disabled / create-minimal 的 token（公开面 disabled/交换链要用）
    private String ptokenOff = "";
    private String ptokenMin = "";
    private String sigOther = "";

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

    /** 对照脚本 sig()：HMAC-SHA256(token, "cid|sid") base64url 无填充。 */
    private static String sig(String publishToken, String channelId, String sessionId) {
        com.ragagent.embed.domain.EmbedChannelEntity tmp = new com.ragagent.embed.domain.EmbedChannelEntity();
        tmp.setId(channelId);
        tmp.setPublishToken(publishToken);
        return EmbedTokens.signHandle(tmp, sessionId);
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
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder post(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        if (bearer != null) {
            b = b.header("Authorization", bearer);
        }
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return b;
    }

    private static MockHttpServletRequestBuilder postJson(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).contentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            b = b.header("Authorization", bearer);
        }
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
        s = PUBLISH_TOKEN_PATTERN.matcher(s).replaceAll("em_<token>");
        s = SESSION_TOKEN_PATTERN.matcher(s).replaceAll("ems_<token>");
        s = SIG_PATTERN.matcher(s).replaceAll("$1<sig>\"");
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        return UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
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
