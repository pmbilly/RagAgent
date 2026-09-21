package com.ragagent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.ragagent.knowledge.service.LocalStorageService;

/**
 * 跨租户消息文件授予的契约测试（波 5 W5α3）。golden：w5f-*（18 个，
 * scripts/record-w5f-golden.sh 的 Go 实录；种子态见脚本头注释）。
 *
 * <h2>覆盖的两条授予路径（Go access/files.go L154-230 + message_files.go）</h2>
 * <ul>
 *   <li><b>shared-agent 授予</b>：message.agent_tenant_id = 资源属主 ≠ caller →
 *       GetSharedAgentForTenant + GetMessageFileBindings + scope.Allows +
 *       API-Key 白名单（含消息 artifact 绑定的独立放行）；</li>
 *   <li><b>org-shared KB 证据链</b>：message.agent_tenant_id = 0（自有 agent +
 *       他方 KB 的 #3022 场景）→ 持久化检索证据（knowledge_references /
 *       agent_steps）+ kb_shares viewer + 存活 resource_bindings。</li>
 * </ul>
 *
 * <p>另锚两个 Go 真实契约：①证据链中 ToolCall.Result.Output 命中的 handle
 * <b>不能</b>归因到兄弟 Data map 的 knowledge_base_id（Go 先 Output 后 Data、
 * 上下文不共享）——所以 w5f-evidence-steps-ok 的证据串必须放在 Data map 内部；
 * ②API-Key 主体读 web 用户的会话恒 404（owner = api_tenant_key:&lt;tenant&gt;:&lt;keyID&gt;
 * 精确不匹配），白名单段必须用 key 自有会话。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class W5fCrossTenantFileContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK";
    private static final long T_CALLER = 10003L;
    private static final long T_SOURCE = 10005L;
    private static final String SYS_LOCAL = "c730730a-70f5-4d86-a7e1-58972cf27567";

    private static final String ORG = "77777777-1111-4444-8888-0000000000f5";
    private static final String UB = "11111111-2222-3333-4444-55555555f521";
    private static final String UD = "11111111-2222-3333-4444-55555555f525";
    private static final String UB_EMAIL = "w5f-b@weknora.test";
    private static final String KB_A = "33333333-3333-3333-3333-33333333f501";
    private static final String KB_E = "33333333-3333-3333-3333-33333333f502";
    private static final String KB_F = "33333333-3333-3333-3333-33333333f503";
    private static final String KB_OWN = "33333333-3333-3333-3333-33333333f504";
    private static final String KNOW_A = "55555555-5555-5555-5555-55555555f511";
    private static final String KNOW_E = "55555555-5555-5555-5555-55555555f512";
    private static final String KNOW_F = "55555555-5555-5555-5555-55555555f513";
    private static final String AG_A = "44444444-4444-4444-4444-44444444f501";
    private static final String AG_B = "44444444-4444-4444-4444-44444444f502";
    private static final String AG_C = "44444444-4444-4444-4444-44444444f503";
    private static final String RES_A = "5f00000a-0000-0000-0000-0000000000a1";
    private static final String RES_B = "5f00000a-0000-0000-0000-0000000000a2";
    private static final String RES_C = "5f00000a-0000-0000-0000-0000000000a3";
    private static final String RES_D = "5f00000a-0000-0000-0000-0000000000a4";
    private static final String RES_E = "5f00000a-0000-0000-0000-0000000000a5";
    private static final String H_A = "w5fresourcehandle000a1";
    private static final String H_B = "w5fresourcehandle000b1";
    private static final String H_C = "w5fresourcehandle000c1";
    private static final String H_D = "w5fresourcehandle000d1";
    private static final String H_E = "w5fresourcehandle000e1";
    private static final String SES = "5f000004-0000-0000-0000-0000000000f4";
    private static final String M_GRANT = "5f000002-0000-0000-0000-0000000000f1";
    private static final String M_SCOPE = "5f000002-0000-0000-0000-0000000000f2";
    private static final String M_NOSHARE = "5f000002-0000-0000-0000-0000000000f3";
    private static final String M_ARTIFACT = "5f000002-0000-0000-0000-0000000000f4";
    private static final String M_EVID = "5f000002-0000-0000-0000-0000000000f5";
    private static final String M_STEPS = "5f000002-0000-0000-0000-0000000000f6";
    private static final String M_NOBIND = "5f000002-0000-0000-0000-0000000000f7";
    private static final String M_NOKBSHARE = "5f000002-0000-0000-0000-0000000000f8";
    private static final String M_MISMATCH = "5f000002-0000-0000-0000-0000000000f9";
    private static final String M_USERX = "5f000002-0000-0000-0000-0000000000fa";
    private static final String M_GRANT_KF = "5f000002-0000-0000-0000-0000000000fb";
    private static final String M_GRANT_KR = "5f000002-0000-0000-0000-0000000000fc";
    private static final String SES_KF = "5f000004-0000-0000-0000-0000000000f5";
    private static final String SES_KR = "5f000004-0000-0000-0000-0000000000f6";

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

    private String caller;
    private String fullKey;
    private String restrictedKey;

    // ── 种子（复刻 record-w5f-golden.sh 的录制态）────────────────────────────

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);

        seedTenant(T_CALLER, "w5s-caller-tenant");
        seedTenant(T_SOURCE, "w5s-source-tenant");
        seedUser(UB, "w5fb", UB_EMAIL, T_CALLER);
        seedUser(UD, "w5fd", "w5f-d@weknora.test", T_SOURCE);

        jdbc.update("INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, "
                + "status, legacy_alias) VALUES (?, ?, 'System LOCAL', 'local', '{}', 'env', 'active', TRUE)",
                SYS_LOCAL, T_SOURCE);

        jdbc.update("INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, "
                + "invite_code, invite_code_validity_days, avatar, require_approval, searchable, "
                + "member_limit, created_at, updated_at) VALUES (?, 'w5f-org', 'w5f share org', ?, ?, "
                + "'w5ffixedcode0001', 7, '', FALSE, FALSE, 200, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00')", ORG, UD, T_SOURCE);
        jdbc.update("INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, "
                + "representative_user_id, joined_at, created_at, updated_at) VALUES "
                + "('66666666-6666-4444-8888-0000000000f5', ?, ?, 'admin', ?, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00')", ORG, T_SOURCE, UD);
        jdbc.update("INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, "
                + "representative_user_id, joined_at, created_at, updated_at) VALUES "
                + "('66666666-6666-4444-8888-0000000000f6', ?, ?, 'viewer', ?, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:00:00+00')", ORG, T_CALLER, UB);

        seedKb(KB_A, "w5f-kb-agent-scope", T_SOURCE, UD);
        seedKb(KB_E, "w5f-kb-org-shared", T_SOURCE, UD);
        seedKb(KB_F, "w5f-kb-private", T_SOURCE, UD);
        seedKb(KB_OWN, "w5f-kb-caller-own", T_CALLER, UB);
        seedKnowledge(KNOW_A, KB_A, "w5f doc alpha", "w5f-a.png");
        seedKnowledge(KNOW_E, KB_E, "w5f doc evidence", "w5f-b.png");
        seedKnowledge(KNOW_F, KB_F, "w5f doc private", "w5f-c.png");

        seedAgent(AG_A, "w5f-agent-grant",
                "{\"agent_mode\":\"quick-answer\",\"model_id\":\"w5f-model\","
                        + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_A + "\"],"
                        + "\"web_search_enabled\":false}");
        seedAgent(AG_B, "w5f-agent-empty",
                "{\"agent_mode\":\"quick-answer\",\"model_id\":\"w5f-model\","
                        + "\"kb_selection_mode\":\"none\",\"web_search_enabled\":false}");
        seedAgent(AG_C, "w5f-agent-private",
                "{\"agent_mode\":\"quick-answer\",\"model_id\":\"w5f-model\","
                        + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_A + "\"],"
                        + "\"web_search_enabled\":false}");
        jdbc.update("INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, "
                + "source_tenant_id, permission, created_at, updated_at) VALUES "
                + "('88888888-8888-4444-8888-0000000000f1', ?, ?, ?, ?, 'viewer', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:10:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:10:00+00')", AG_A, ORG, UD, T_SOURCE);
        jdbc.update("INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, "
                + "source_tenant_id, permission, created_at, updated_at) VALUES "
                + "('88888888-8888-4444-8888-0000000000f2', ?, ?, ?, ?, 'viewer', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:11:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:11:00+00')", AG_B, ORG, UD, T_SOURCE);
        jdbc.update("INSERT INTO kb_shares (id, knowledge_base_id, organization_id, shared_by_user_id, "
                + "source_tenant_id, permission, created_at, updated_at) VALUES "
                + "('88888888-8888-4444-8888-0000000000f3', ?, ?, ?, ?, 'viewer', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:12:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 09:12:00+00')", KB_E, ORG, UD, T_SOURCE);

        seedResource(RES_A, H_A, "w5f-a.png", "w5f shared-agent granted file\n");
        seedResource(RES_B, H_B, "w5f-b.png", "w5f org kb evidence file\n");
        seedResource(RES_C, H_C, "w5f-c.png", "w5f kb not shared file\n");
        seedResource(RES_D, H_D, "w5f-d.png", "w5f message artifact file\n");
        seedResource(RES_E, H_E, "w5f-e.png", "w5f unbound evidence file\n");

        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, "
                + "relation) VALUES ('5f00000c-0000-0000-0000-0000000000c1', ?, ?, 'knowledge', ?, "
                + "'source_file')", RES_A, T_SOURCE, KNOW_A);
        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, "
                + "relation) VALUES ('5f00000c-0000-0000-0000-0000000000c2', ?, ?, 'knowledge', ?, "
                + "'source_file')", RES_B, T_SOURCE, KNOW_E);
        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, "
                + "relation) VALUES ('5f00000c-0000-0000-0000-0000000000c3', ?, ?, 'knowledge', ?, "
                + "'source_file')", RES_C, T_SOURCE, KNOW_F);
        jdbc.update("INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, "
                + "relation) VALUES ('5f00000c-0000-0000-0000-0000000000c4', ?, ?, 'message', ?, "
                + "'artifact')", RES_D, T_SOURCE, M_ARTIFACT);

        jdbc.update("INSERT INTO sessions (id, tenant_id, user_id, title) VALUES (?, ?, ?, 'w5f')",
                SES, T_CALLER, UB);
        insertMessage(M_GRANT, SES, "assistant", "授权图 resource://" + H_A, AG_A, T_SOURCE, null, null);
        insertMessage(M_SCOPE, SES, "assistant", "越权图 resource://" + H_A, AG_B, T_SOURCE, null, null);
        insertMessage(M_NOSHARE, SES, "assistant", "私享图 resource://" + H_A, AG_C, T_SOURCE, null, null);
        insertMessage(M_ARTIFACT, SES, "assistant", "产物图 resource://" + H_D, AG_B, T_SOURCE, null, null);
        insertMessage(M_EVID, SES, "assistant", "证据图 resource://" + H_B, "", 0,
                "[{\"content\":\"chunk 文本 图片 resource://" + H_B + " 在此\",\"knowledge_id\":\""
                        + KNOW_E + "\",\"knowledge_base_id\":\"" + KB_E + "\"}]", null);
        insertMessage(M_STEPS, SES, "assistant", "步骤产出的图见上", "", 0, null,
                "[{\"tool_calls\":[{\"id\":\"tc1\",\"name\":\"search\",\"result\":{\"output\":\"检索完成\","
                        + "\"data\":{\"knowledge_base_id\":\"" + KB_E + "\",\"snippet\":\"图片 resource://"
                        + H_B + " 在此\"}}}]}]");
        insertMessage(M_NOBIND, SES, "assistant", "无绑定图 resource://" + H_E, "", 0,
                "[{\"content\":\"chunk 文本 图片 resource://" + H_E + " 在此\",\"knowledge_id\":\""
                        + KNOW_E + "\",\"knowledge_base_id\":\"" + KB_E + "\"}]", null);
        insertMessage(M_NOKBSHARE, SES, "assistant", "私库图 resource://" + H_C, "", 0,
                "[{\"content\":\"chunk 文本 图片 resource://" + H_C + " 在此\",\"knowledge_id\":\""
                        + KNOW_F + "\",\"knowledge_base_id\":\"" + KB_F + "\"}]", null);
        insertMessage(M_MISMATCH, SES, "assistant", "错配图 resource://" + H_A, "", T_CALLER, null, null);
        insertMessage(M_USERX, SES, "user", "用户图 resource://" + H_A, "", T_SOURCE, null, null);

        caller = "Bearer " + login(UB_EMAIL);

        // key-owned 会话：owner = "api_tenant_key:10003:<keyID>"（Go 的
        // SessionOwnerIDFromContext(PrincipalAPITenant)），白名单段只能用它触达。
        fullKey = createApiKey("{\"name\":\"w5f-full\",\"full_access\":true}");
        restrictedKey = createApiKey("{\"name\":\"w5f-kbrestricted\",\"capabilities\":[\"retrieve\"],"
                + "\"knowledge_base_ids\":[\"" + KB_OWN + "\"]}");
        long fullKeyId = jdbc.queryForObject(
                "SELECT id FROM tenant_api_keys WHERE name = 'w5f-full'", Long.class);
        long restrictedKeyId = jdbc.queryForObject(
                "SELECT id FROM tenant_api_keys WHERE name = 'w5f-kbrestricted'", Long.class);
        jdbc.update("INSERT INTO sessions (id, tenant_id, user_id, title) VALUES (?, ?, ?, 'w5f kf')",
                SES_KF, T_CALLER, "api_tenant_key:" + T_CALLER + ":" + fullKeyId);
        jdbc.update("INSERT INTO sessions (id, tenant_id, user_id, title) VALUES (?, ?, ?, 'w5f kr')",
                SES_KR, T_CALLER, "api_tenant_key:" + T_CALLER + ":" + restrictedKeyId);
        insertMessage(M_GRANT_KF, SES_KF, "assistant", "授权图 resource://" + H_A, AG_A, T_SOURCE,
                null, null);
        insertMessage(M_GRANT_KR, SES_KR, "assistant", "授权图 resource://" + H_A, AG_A, T_SOURCE,
                null, null);
    }

    private void seedTenant(long id, String name) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setName(name);
        tenant.setStatus("active");
        tenant.setDefaultStorageBackendId(SYS_LOCAL);
        tenantMapper.insert(tenant);
    }

    private void seedUser(String id, String name, String email, long tenantId) {
        User user = new User();
        user.setId(id);
        user.setUsername(name);
        user.setEmail(email);
        user.setPasswordHash(BCRYPT);
        user.setTenantId(tenantId);
        user.setIsActive(true);
        user.setPreferences(new UserPreferences());
        userMapper.insert(user);
        TenantMember member = new TenantMember();
        member.setUserId(id);
        member.setTenantId(tenantId);
        member.setRole("owner");
        member.setStatus("active");
        memberMapper.insert(member);
    }

    private void seedKb(String id, String name, long tenantId, String creator) {
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, "
                + "chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'document', 'w5f', ?, '{}', '', '', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 08:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 08:00:00+00')", id, name, tenantId, creator);
    }

    private void seedKnowledge(String id, String kbId, String title, String fileName) {
        jdbc.update("INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, "
                + "parse_status, summary_status, enable_status, file_name, file_type, file_size, "
                + "file_hash, created_at, updated_at) VALUES (?, ?, ?, 'document', ?, 'file', "
                + "'completed', 'none', 'enabled', ?, 'png', 10, ?, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 10:00:00+00')",
                id, T_SOURCE, kbId, title, fileName, "00000000000000000000000000000" + id.substring(27));
    }

    private void seedAgent(String id, String name, String config) {
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config, created_at, updated_at) VALUES (?, ?, 'w5f', '', FALSE, ?, ?, ?, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 08:10:00+00', "
                + "TIMESTAMP WITH TIME ZONE '2026-09-01 08:10:00+00')", id, name, T_SOURCE, UD, config);
    }

    private void seedResource(String id, String handle, String fileName, String content) throws Exception {
        String physical = "local://10005/exports/" + fileName;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO resources (id, handle, tenant_id, storage_backend_id, provider, "
                + "physical_path, location_hash, kind, mime_type, original_name, size, content_hash, "
                + "lifecycle, state) VALUES (?, ?, ?, NULL, 'local', ?, ?, 'file', 'image/png', ?, ?, "
                + "'', 'persistent', 'active')",
                id, handle, T_SOURCE, physical, sha256(physical), fileName, bytes.length);
        Path dir = localStorage.baseDir().resolve("10005/exports");
        Files.createDirectories(dir);
        Files.write(dir.resolve(fileName), bytes);
    }

    private void insertMessage(String id, String sessionId, String role, String content,
            String agentId, long agentTenantId, String knowledgeReferences, String agentSteps) {
        jdbc.update("INSERT INTO messages (id, request_id, session_id, role, content, is_completed, "
                + "agent_id, agent_tenant_id, knowledge_references, agent_steps) "
                + "VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, COALESCE(?, '[]'), ?)",
                id, "5f000003-0000-0000-0000-0000000000" + id.substring(id.length() - 2), sessionId,
                role, content, agentId, agentTenantId, knowledgeReferences, agentSteps);
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

    private String createApiKey(String body) {
        try {
            MvcResult r = mockMvc.perform(post("/api/v1/tenants/10003/api-keys")
                            .header("Authorization", caller)
                            .contentType("application/json").content(body))
                    .andReturn();
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(r.getResponse().getContentAsString()).path("data").path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 请求与比对辅助（同 W5cFileProxyContractTest 形态）─────────────────────

    private MvcResult call(String path, String auth, String... extraHeaders) {
        try {
            var builder = get(path);
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

    private static String files(String sessionId, String messageId, String handle) {
        // MockMvc 的 query 参数不做百分号解码，直接放解码值（Go c.Query 同形）
        return "/api/v1/sessions/" + sessionId + "/messages/" + messageId
                + "/files?file_path=resource://" + handle;
    }

    private Path golden(String name) {
        Path file = Path.of("src/test/resources/contracts", name);
        return Files.exists(file) ? file : Path.of("server/src/test/resources/contracts", name);
    }

    private String raw(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void compareJson(String goldenName, int expectedStatus, String path, String auth,
            String... extraHeaders) throws Exception {
        MvcResult r = call(path, auth, extraHeaders);
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status, body=" + raw(r));
        assertEquals(Files.readString(golden(goldenName), StandardCharsets.UTF_8), raw(r),
                () -> "golden mismatch: " + goldenName);
    }

    private void compareBinary(String goldenName, int expectedStatus, String path, String auth,
            String... extraHeaders) throws Exception {
        MvcResult r = call(path, auth, extraHeaders);
        assertEquals(expectedStatus, r.getResponse().getStatus(),
                () -> goldenName + " status, body=" + raw(r));
        byte[] expected = Files.readAllBytes(golden(goldenName + ".bin"));
        byte[] actual = r.getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertTrue(Arrays.equals(expected, actual),
                () -> goldenName + " body bytes");
        String expectedHeaders = normHeaders(
                Files.readString(golden(goldenName + ".bin.headers"), StandardCharsets.UTF_8));
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

    // ══════════════ 1. shared-agent 授予路径 ══════════════════════════

    @Test
    void sharedAgentGrant() throws Exception {
        // agent 共享 + scope 含绑定 KB → 200
        compareBinary("w5f-grant-ok", 200, files(SES, M_GRANT, H_A), caller);
        // agent 共享但 scope 为空 → 403
        compareJson("w5f-scope-denied.json", 403, files(SES, M_SCOPE, H_A), caller);
        // agent 未共享 → 403
        compareJson("w5f-agent-not-shared.json", 403, files(SES, M_NOSHARE, H_A), caller);
        // 消息 artifact 绑定独立放行（scope 为空也 200）
        compareBinary("w5f-artifact-ok", 200, files(SES, M_ARTIFACT, H_D), caller);
    }

    // ══════════════ 2. org-shared KB 证据链 ══════════════════════════

    @Test
    void orgSharedKbEvidence() throws Exception {
        // knowledge_references 证据 + kb_shares viewer + 存活绑定 → 200
        compareBinary("w5f-evidence-ok", 200, files(SES, M_EVID, H_B), caller);
        // agent_steps 的 Data map 内部证据（kb 上下文继承）→ 200
        compareBinary("w5f-evidence-steps-ok", 200, files(SES, M_STEPS, H_B), caller);
        // 证据 KB 共享但资源无绑定 → 403
        compareJson("w5f-evidence-nobind.json", 403, files(SES, M_NOBIND, H_E), caller);
        // 证据 KB 未共享 → 403
        compareJson("w5f-evidence-kb-not-shared.json", 403, files(SES, M_NOKBSHARE, H_C), caller);
        // agent_tenant ≠ 资源属主且证据链失败 → 立即 403（不落 shared-agent）
        compareJson("w5f-agent-tenant-mismatch.json", 403, files(SES, M_MISMATCH, H_A), caller);
        // role=user 跨租户 → 403
        compareJson("w5f-user-crosstenant.json", 403, files(SES, M_USERX, H_A), caller);
    }

    // ══════════════ 3. API-Key 段 ══════════════════════════

    @Test
    void apiKeyScenarios() throws Exception {
        // API-Key 主体读 web 用户的会话 → owner 不匹配 → 404 无体
        MvcResult r = call(files(SES, M_GRANT, H_A), null, "X-API-Key: " + fullKey);
        assertEquals(404, r.getResponse().getStatus(), () -> "key-websession body=" + raw(r));
        assertEquals(0, r.getResponse().getContentAsByteArray().length);
        // full-access key 读自有会话 → apiKeyAllowsKb 恒放行 → 200
        compareBinary("w5f-key-full", 200, files(SES_KF, M_GRANT_KF, H_A), null,
                "X-API-Key: " + fullKey);
        // KB 受限 key 白名单不含 KB_A → 403
        compareJson("w5f-key-kb-outofscope.json", 403, files(SES_KR, M_GRANT_KR, H_A), null,
                "X-API-Key: " + restrictedKey);
    }
}
