package com.ragagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 评估契约测试（对照 Go routes_infra.go L81-89 的 POST/GET /evaluation）。
 * golden：record-system-golden.sh 的 ev-*（Go dev 实录）。
 *
 * <p><b>执行步（2026-09-24 A4 接线后）</b>：后台真实跑 EvalDataset——本 fixture 的源 KB
 * 无 embedding 模型，段落同步建索引在 ChunkVectorIndexer 处以 Go 原文
 * "model ID cannot be empty" 失败（部署有模型时才会跑到 metrics 产出，见 ev-get.json
 * 的 Go dev 终态）。创建响应（ev-post）的 task+params 形态两侧一致（掩码 task id /
 * start_time 后字节比对——params 里的 prompt 常量按 dev 配置固化在 EvaluationPromptDefaults）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvaluationContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long TENANT = 10002L;
    private static final String OWNER = "11111111-2222-3333-4444-555555555501";
    private static final String VIEWER = "11111111-2222-3333-4444-555555555504";
    private static final String KB_ID = "2645450c-1060-419c-87de-b1f58f61256d";
    private static final String UNKNOWN_KB = "11111111-2222-3333-4444-999999999999";

    private static final String OWNER_EMAIL = "java-phase1@weknora.test";
    private static final String VIEWER_EMAIL = "java-phase1-viewer@weknora.test";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern TASK_ID = Pattern.compile("\"id\":\"(evaluation_[^\"]+)\"");
    private static final Pattern START_TIME = Pattern.compile("\"start_time\":\"[^\"]+\"");

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String owner;
    private String viewer;

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("phase1-test-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(OWNER, "phase1test", OWNER_EMAIL);
        seedUser(VIEWER, "phase1viewer", VIEWER_EMAIL);
        seedMember(OWNER, "owner", "2026-09-01T10:00:00Z");
        seedMember(VIEWER, "viewer", "2026-09-01T10:01:00Z");

        // 评估源 KB（golden 用的 dev KB id 原样播种；GetKnowledgeBaseByID 只验存在）
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, is_temporary, "
                + "creator_id, created_at, updated_at) VALUES (?, 'chunk-golden-kb', ?, 'document', "
                + "FALSE, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, TENANT, OWNER);

        owner = "Bearer " + login(OWNER_EMAIL);
        viewer = "Bearer " + login(VIEWER_EMAIL);
    }

    private void seedUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(TENANT);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
    }

    private void seedMember(String userId, String role, String joinedAt) {
        TenantMember member = new TenantMember();
        member.setUserId(userId);
        member.setTenantId(TENANT);
        member.setRole(role);
        member.setStatus("active");
        member.setJoinedAt(OffsetDateTime.parse(joinedAt));
        memberMapper.insert(member);
    }

    @Test
    void postBadJsonMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner, "not-json")).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-badjson.json"), raw(r));
    }

    /** 租户无模型 → 500 "no default models found for evaluation"（模型表空，确定性）。 */
    @Test
    void postEmptyMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner, "{}")).andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-empty.json"), raw(r));
    }

    @Test
    void postUnknownKbMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledge_base_id\":\"" + UNKNOWN_KB + "\",\"chat_id\":\"fake-chat-model-id\"}"))
                .andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-kb-missing.json"), raw(r));
    }

    /** viewer 打 POST → 403（Admin 门，role 文案）。 */
    @Test
    void postViewerForbidden() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), viewer, "{}")).andReturn();
        assertEquals(403, r.getResponse().getStatus(), raw(r));
        assertEquals("{\"error\":\"Forbidden: insufficient workspace role\"}", raw(r));
    }

    @Test
    void getValidationMatchesGo() throws Exception {
        MvcResult missing = mockMvc.perform(get("/api/v1/evaluation")
                .header("Authorization", owner)).andReturn();
        assertEquals(400, missing.getResponse().getStatus(), raw(missing));
        assertEquals(golden("ev-get-missing.json"), raw(missing));

        MvcResult unknown = mockMvc.perform(get("/api/v1/evaluation?task_id=no-such-task")
                .header("Authorization", owner)).andReturn();
        assertEquals(500, unknown.getResponse().getStatus(), raw(unknown));
        assertEquals(golden("ev-get-unknown.json"), raw(unknown));
    }

    /** 创建响应（掩码 task id / start_time 后与 Go golden 字节比对，status=0 快照）。 */
    @Test
    void postSuccessMatchesGo() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledge_base_id\":\"" + KB_ID + "\",\"chat_id\":\"fake-chat-model-id\"}"))
                .andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ev-post.json")), mask(raw(r)));
        // status=0（创建快照；Go 是与后台 goroutine 的竞态，实测 0/1 都出现）
        JsonNode node = MAPPER.readTree(raw(r));
        assertEquals(0, node.path("data").path("task").path("status").asInt());
    }

    /** 终态：真实执行（无 embedding 模型 → failed，err_msg = Go 原文）。 */
    @Test
    void getTerminalRunsExecution() throws Exception {
        MvcResult created = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledge_base_id\":\"" + KB_ID + "\",\"chat_id\":\"fake-chat-model-id\"}"))
                .andReturn();
        Matcher m = TASK_ID.matcher(raw(created));
        assertThat(m.find()).isTrue();
        String taskId = m.group(1);

        // viewer 同租户可读（GET=Viewer）
        MvcResult viewerGet = mockMvc.perform(get("/api/v1/evaluation?task_id=" + taskId)
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, viewerGet.getResponse().getStatus(), raw(viewerGet));

        // 轮询到终态（真实执行：段落建索引因无模型失败），params/task 契约形态保持
        MvcResult r = mockMvc.perform(get("/api/v1/evaluation?task_id=" + taskId)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        JsonNode node = MAPPER.readTree(raw(r));
        JsonNode task = node.path("data").path("task");
        for (int i = 0; i < 250 && task.path("status").asInt() <= 1; i++) {
            Thread.sleep(20);
            r = mockMvc.perform(get("/api/v1/evaluation?task_id=" + taskId)
                    .header("Authorization", owner)).andReturn();
            node = MAPPER.readTree(raw(r));
            task = node.path("data").path("task");
        }
        assertEquals(3, task.path("status").asInt(), raw(r));
        // 失败点在 ChunkVectorIndexer.updateChunkVector（KB 无 embedding 模型），
        // err_msg = AppError 原文（对照 Go err.Error() 语义）
        assertEquals("model ID cannot be empty", task.path("err_msg").asText());
        JsonNode createdNode = MAPPER.readTree(raw(created));
        assertEquals(createdNode.path("data").path("params"), node.path("data").path("params"));
        // 失败早于指标记录 → metric 缺省
        assertThat(node.path("data").has("metric")).isFalse();
    }

    // ════════════════ 辅助 ════════════════

    private String login(String email) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        Matcher m = TOKEN.matcher(raw(r));
        assertThat(m.find()).as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String bearer, String body) {
        builder.header("Authorization", bearer);
        return builder.contentType("application/json").content(body);
    }

    private static String raw(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    /** 两侧同掩码：task id（evaluation_…）与 start_time。 */
    private static String mask(String s) {
        String out = TASK_ID.matcher(s).replaceAll("\"id\":\"<taskid>\"");
        out = START_TIME.matcher(out).replaceAll("\"start_time\":\"<ts>\"");
        return out;
    }
}
