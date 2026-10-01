package com.ragagent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 评估契约测试（POST/GET /api/v1/evaluation）。
 *
 * <p>fixture 锚定本仓行为：创建响应（ev-post）掩码 task id / startTime 后整体比对；
 * params 里的 prompt 常量按 dev 配置固化在 {@code EvaluationPromptDefaults}。</p>
 *
 * <p><b>执行步</b>：后台真实跑 EvalDataset——本 fixture 的源 KB 无 embedding 模型，
 * 段落同步建索引在 ChunkVectorIndexer 处失败 "model ID cannot be empty"
 * （部署有模型时才会跑到 metrics 产出，见 ev-get.json 的终态）。</p>
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
    private static final Pattern START_TIME = Pattern.compile("\"startTime\":\"[^\"]+\"");

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
    void postBadJson() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner, "not-json")).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-badjson.json"), raw(r));
    }

    /** 0 字节空体 → 400「请求体不能为空」（@RejectEmptyBody 拦截）。 */
    @Test
    void postEmptyBody() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/evaluation")
                .header("Authorization", owner).contentType("application/json")).andReturn();
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-nobody.json"), raw(r));
    }

    /** 租户无模型 → 500 "no default models found for evaluation"（模型表空，确定性）。 */
    @Test
    void postEmpty() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner, "{}")).andReturn();
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("ev-post-empty.json"), raw(r));
    }

    @Test
    void postUnknownKb() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledgeBaseId\":\"" + UNKNOWN_KB + "\",\"chatId\":\"fake-chat-model-id\"}"))
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
    void getValidation() throws Exception {
        MvcResult missing = mockMvc.perform(get("/api/v1/evaluation")
                .header("Authorization", owner)).andReturn();
        assertEquals(400, missing.getResponse().getStatus(), raw(missing));
        assertEquals(golden("ev-get-missing.json"), raw(missing));

        MvcResult unknown = mockMvc.perform(get("/api/v1/evaluation?taskId=no-such-task")
                .header("Authorization", owner)).andReturn();
        assertEquals(500, unknown.getResponse().getStatus(), raw(unknown));
        assertEquals(golden("ev-get-unknown.json"), raw(unknown));
    }

    /** 创建响应（掩码 task id / startTime 后整体比对，status=0 快照）。 */
    @Test
    void postSuccess() throws Exception {
        MvcResult r = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledgeBaseId\":\"" + KB_ID + "\",\"chatId\":\"fake-chat-model-id\"}"))
                .andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("ev-post.json")), mask(raw(r)));
        // status=0（创建快照；后台 goroutine 竞态下 0/1 都可能出现）
        JsonNode node = MAPPER.readTree(raw(r));
        assertEquals(0, node.path("task").path("status").asInt());
    }

    /** 终态：真实执行（无 embedding 模型 → failed，errMsg = AppError 原文）。 */
    @Test
    void getTerminalRunsExecution() throws Exception {
        MvcResult created = mockMvc.perform(json(post("/api/v1/evaluation"), owner,
                "{\"knowledgeBaseId\":\"" + KB_ID + "\",\"chatId\":\"fake-chat-model-id\"}"))
                .andReturn();
        Matcher m = TASK_ID.matcher(raw(created));
        assertThat(m.find()).isTrue();
        String taskId = m.group(1);

        // viewer 同租户可读（GET=Viewer）
        MvcResult viewerGet = mockMvc.perform(get("/api/v1/evaluation?taskId=" + taskId)
                .header("Authorization", viewer)).andReturn();
        assertEquals(200, viewerGet.getResponse().getStatus(), raw(viewerGet));

        // 轮询到终态（真实执行：段落建索引因无模型失败），params/task 契约形态保持
        MvcResult r = mockMvc.perform(get("/api/v1/evaluation?taskId=" + taskId)
                .header("Authorization", owner)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        JsonNode node = MAPPER.readTree(raw(r));
        JsonNode task = node.path("task");
        for (int i = 0; i < 250 && task.path("status").asInt() <= 1; i++) {
            Thread.sleep(20);
            r = mockMvc.perform(get("/api/v1/evaluation?taskId=" + taskId)
                    .header("Authorization", owner)).andReturn();
            node = MAPPER.readTree(raw(r));
            task = node.path("task");
        }
        assertEquals(3, task.path("status").asInt(), raw(r));
        // 失败点在 ChunkVectorIndexer.updateChunkVector（KB 无 embedding 模型）
        assertEquals("model ID cannot be empty", task.path("errMsg").asText());
        JsonNode createdNode = MAPPER.readTree(raw(created));
        assertEquals(createdNode.path("params"), node.path("params"));
        // 失败早于指标记录 → metric 显式 null（键恒在）
        assertThat(node.has("metric")).isTrue();
        assertThat(node.path("metric").isNull()).isTrue();
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

    /** 两侧同掩码：task id（evaluation_…）与 start_time。 */
    private static String mask(String s) {
        String out = TASK_ID.matcher(s).replaceAll("\"id\":\"<taskid>\"");
        out = START_TIME.matcher(out).replaceAll("\"start_time\":\"<ts>\"");
        return out;
    }
}
