package com.ragagent.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 波 3 协作面批次契约测试：organizations 全部路由 + KB/agent shares + shared-* 读面
 * （对照 golden 逐字节/掩码比对）。
 *
 * golden 来源：Go dev server（localhost:8080，2026-09-19 录制，
 * scripts/record-org-golden.sh，68 条 org-*.json + 45 条 shr-*.json）。
 *
 * 场景顺序严格复刻录制脚本（同请求序列有状态依赖）：org CRUD → search/preview/join →
 * join-request 复审 → 成员管理 → invite-code/升级/成员上限 → KB shares → org shares 读面 →
 * agent shares → shared-agents/disabled → 收尾 resource_counts。
 *
 * 掩码面：uuid + 时间戳 + invite_code（16 位随机 hex，create/invite-code 每轮不同）。
 * 固定种子（ORG2/KB/agent 行）两侧同 id，不需要掩码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrganizationContractTest {

    private static final String BCRYPT = "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final String ORG2 = "77777777-1111-4444-8888-000000000002";
    private static final String CODE2 = "fixedcode00000002";
    private static final String KB_A = "33333333-3333-3333-3333-333333333301";
    private static final String KB_C = "33333333-3333-3333-3333-333333333311";
    private static final String AG_OK = "44444444-4444-4444-4444-444444444401";
    private static final String AG_UNSET = "44444444-4444-4444-4444-444444444402";
    private static final String AG_C = "44444444-4444-4444-4444-444444444411";
    private static final String UA = "11111111-2222-3333-4444-555555555501";
    private static final String MISSING = "99999999-9999-9999-9999-999999999999";

    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "\"(invite_code|code)\":\"[0-9a-f]{16}\"");
    /** by_organization 的键是 org uuid：掩掉键后 0/1 值的位次随随机 uuid 的排序漂移，整段掩掉。 */
    private static final Pattern BY_ORG_PATTERN = Pattern.compile(
            "\"by_organization\":\\{[^}]*\\}");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.execute("INSERT INTO tenants (id, name, description, business, status) VALUES "
                + "(10002,'phase1-test-tenant','','','active'),"
                + "(10003,'org-beta-tenant','','','active'),"
                + "(10004,'org-gamma-tenant','','','active')");
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                + "(?, 'phase1test','java-phase1@weknora.test',?,10002,true),"
                + "(?, 'orgb','org-b@weknora.test',?,10003,true),"
                + "(?, 'orgc','org-c@weknora.test',?,10004,true)",
                UA, BCRYPT,
                "11111111-2222-3333-4444-555555555021", BCRYPT,
                "11111111-2222-3333-4444-555555555022", BCRYPT);
        // viewer 用户（录制环境 dev PG 已存在，H2 需种子）
        jdbc.update("INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES "
                        + "(?, 'phase1viewer','java-phase1-viewer@weknora.test',?,10002,true)",
                "11111111-2222-3333-4444-555555555504", BCRYPT);
        jdbc.update("INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES "
                + "(?,10002,'owner','active'),(?,10003,'owner','active'),(?,10004,'owner','active'),"
                + "('11111111-2222-3333-4444-555555555504',10002,'viewer','active')",
                UA, "11111111-2222-3333-4444-555555555021", "11111111-2222-3333-4444-555555555022");
        jdbc.update("INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, "
                + "chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES "
                + "(?, 'shr-kb-alpha', 10002, 'document', 'kb owned by tenant 10002', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),"
                + "(?, 'shr-kb-gamma', 10004, 'document', 'kb owned by tenant 10004', ?, '{}', '', '', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                KB_A, UA, KB_C, "11111111-2222-3333-4444-555555555022");
        jdbc.update("INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, "
                + "created_by, config, created_at, updated_at) VALUES "
                + "(?, 'shr-agent-ok', 'agent with model', 'robot', false, 10002, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"shr-model-1\",\"rerank_model_id\":\"shr-rerank-1\","
                + "\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"" + KB_A + "\"],"
                + "\"mcp_selection_mode\":\"all\",\"web_search_enabled\":false}', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),"
                + "(?, 'shr-agent-unset', 'agent without model', '', false, 10002, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"\",\"kb_selection_mode\":\"none\","
                + "\"web_search_enabled\":false}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),"
                + "(?, 'shr-agent-gamma', 'agent owned by tenant 10004', '', false, 10004, ?, "
                + "'{\"agent_mode\":\"quick-answer\",\"model_id\":\"gamma-model\",\"rerank_model_id\":\"gamma-rerank\","
                + "\"kb_selection_mode\":\"none\",\"web_search_enabled\":false}', "
                + "'2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00')",
                AG_OK, UA, AG_UNSET, UA, AG_C, "11111111-2222-3333-4444-555555555022");
        // ORG2（require_approval=true）只能 SQL 种子：CreateOrganizationRequest 无该字段
        jdbc.update("INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, invite_code, "
                + "invite_code_validity_days, avatar, require_approval, searchable, member_limit, "
                + "created_at, updated_at) VALUES "
                + "(?, 'shr-approval-org', 'approval required', ?, 10002, ?, 7, '', true, false, 200, "
                + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')",
                ORG2, UA, CODE2);
        jdbc.update("INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, "
                + "representative_user_id, joined_at, created_at, updated_at) VALUES "
                + "('66666666-6666-4444-8888-000000000002', ?, 10002, 'admin', ?, "
                + "'2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00')",
                ORG2, UA);
    }

    @Test
    void unauthorized() throws Exception {
        assertGolden(getH("/api/v1/organizations", null), 401, "org-noauth.json");
    }

    /** 完整复刻录制脚本顺序的单条状态流。 */
    @Test
    void fullOrgAndShareFlow() throws Exception {
        String A = "Bearer " + login("java-phase1@weknora.test");
        String B = "Bearer " + login("org-b@weknora.test");
        String C = "Bearer " + login("org-c@weknora.test");
        String V = "Bearer " + login("java-phase1-viewer@weknora.test");

        // ── 1) org CRUD + 错误形态 ──
        MvcResult create = expect(201, postH("/api/v1/organizations", A,
                "{\"name\":\"shr-alpha-org\",\"description\":\"alpha org for ab\","
                        + "\"invite_code_validity_days\":7,\"member_limit\":50}"), "org-create.json", true);
        String org1 = jsonPath(create, "data.id");
        String code1 = jsonPath(create, "data.invite_code");

        expect(400, postH("/api/v1/organizations", A,
                "{\"name\":\"x-org\",\"invite_code_validity_days\":5}"), "org-create-bad-validity.json", false);
        expect(500, postH("/api/v1/organizations", A,
                "{\"name\":\"x-org\",\"member_limit\":-1}"), "org-create-neg-limit.json", false);
        expect(400, postH("/api/v1/organizations", A, null), "org-create-no-body.json", false);
        expect(200, getH("/api/v1/organizations/" + org1, A), "org-get.json", true);
        expect(404, getH("/api/v1/organizations/" + MISSING, A), "org-get-missing.json", false);
        expect(200, getH("/api/v1/organizations", A), "org-list.json", true);
        expect(404, getH("/api/v1/organizations/" + org1, B), "org-get-nonmember-private.json", false);
        expect(403, putH("/api/v1/organizations/" + org1, B, "{\"searchable\":true}"),
                "org-update-nonmember.json", false);
        expect(200, putH("/api/v1/organizations/" + org1, A,
                "{\"searchable\":true,\"description\":\"alpha updated\"}"), "org-update.json", true);
        expect(403, delH("/api/v1/organizations/" + org1, B), "org-delete-nonowner.json", false);

        // ── 2) search / preview / join ──
        expect(200, getH("/api/v1/organizations/search?q=shr-alpha", B), "org-search.json", true);
        expect(200, getH("/api/v1/organizations/search", B), "org-search-noq.json", true);
        expect(404, getH("/api/v1/organizations/preview/deadbeefdeadbeef", B), "org-preview-invalid.json", false);
        expect(200, getH("/api/v1/organizations/preview/" + code1, B), "org-preview.json", true);
        expect(400, postH("/api/v1/organizations/join-by-id", B,
                "{\"organization_id\":\"" + org1 + "\",\"role\":\"boss\"}"), "org-join-by-id-bad-role.json", false);
        expect(200, postH("/api/v1/organizations/join-by-id", B,
                "{\"organization_id\":\"" + org1 + "\"}"), "org-join-by-id.json", true);
        expect(200, postH("/api/v1/organizations/join-by-id", B,
                "{\"organization_id\":\"" + org1 + "\"}"), "org-join-idempotent.json", true);
        expect(404, postH("/api/v1/organizations/join", B, "{\"invite_code\":\"0000000000000000\"}"),
                "org-join-invalid-code.json", false);
        expect(404, postH("/api/v1/organizations/join", B, "{\"invite_code\":\"" + CODE2 + "\"}"),
                "org-join-approval-required.json", false);
        MvcResult jr = expect(200, postH("/api/v1/organizations/join-request", B,
                "{\"invite_code\":\"" + CODE2 + "\",\"message\":\"let me in\",\"role\":\"editor\"}"),
                "org-join-request.json", true);
        String reqId = jsonPath(jr, "data.id");
        expect(400, postH("/api/v1/organizations/join-request", B,
                "{\"invite_code\":\"" + CODE2 + "\",\"message\":\"again\"}"), "org-join-request-dup.json", false);
        expect(400, postH("/api/v1/organizations/join-request", B,
                "{\"invite_code\":\"" + CODE2 + "\",\"role\":\"boss\"}"), "org-join-request-bad-role.json", false);
        expect(400, postH("/api/v1/organizations/join-request", C,
                "{\"invite_code\":\"" + code1 + "\",\"message\":\"direct\"}"),
                "org-join-request-no-approval.json", false);
        expect(403, getH("/api/v1/organizations/" + ORG2 + "/join-requests", C),
                "org-join-requests-nonadmin.json", false);
        expect(200, getH("/api/v1/organizations/" + ORG2 + "/join-requests", A), "org-join-requests.json", true);
        expect(403, putH("/api/v1/organizations/" + ORG2 + "/join-requests/" + reqId + "/review", C,
                "{\"approved\":true}"), "org-review-nonadmin.json", false);
        expect(200, putH("/api/v1/organizations/" + ORG2 + "/join-requests/" + reqId + "/review", A,
                "{\"approved\":true,\"role\":\"editor\",\"message\":\"welcome\"}"), "org-review-approve.json", false);
        expect(400, putH("/api/v1/organizations/" + ORG2 + "/join-requests/" + reqId + "/review", A,
                "{\"approved\":true}"), "org-review-again.json", false);
        expect(200, getH("/api/v1/organizations/" + ORG2 + "/join-requests", A),
                "org-join-requests-after.json", true);
        expect(200, getH("/api/v1/organizations/" + ORG2 + "/members", A), "org2-members.json", true);

        // ── 3) 成员管理（ORG1：B=viewer）──
        expect(200, getH("/api/v1/organizations/" + org1 + "/search-tenants?q=org-gamma", A),
                "org-search-tenants.json", true);
        expect(200, getH("/api/v1/organizations/" + org1 + "/search-tenants", A),
                "org-search-tenants-empty.json", true);
        expect(403, getH("/api/v1/organizations/" + org1 + "/search-tenants?q=gamma", B),
                "org-search-tenants-nonadmin.json", false);
        expect(200, getH("/api/v1/organizations/" + org1 + "/search-users?q=org-gamma", A),
                "org-search-users-alias.json", true);
        expect(403, postH("/api/v1/organizations/" + org1 + "/invite", B,
                "{\"tenant_id\":10004,\"role\":\"viewer\"}"), "org-invite-nonadmin.json", false);
        expect(400, postH("/api/v1/organizations/" + org1 + "/invite", A, "{}"),
                "org-invite-missing-field.json", false);
        expect(400, postH("/api/v1/organizations/" + org1 + "/invite", A,
                "{\"tenant_id\":10004,\"role\":\"boss\"}"), "org-invite-bad-role.json", false);
        expect(404, postH("/api/v1/organizations/" + org1 + "/invite", A,
                "{\"tenant_id\":99999,\"role\":\"viewer\"}"), "org-invite-missing-tenant.json", false);
        expect(200, postH("/api/v1/organizations/" + org1 + "/invite", A,
                "{\"tenant_id\":10004,\"role\":\"viewer\"}"), "org-invite.json", false);
        expect(400, postH("/api/v1/organizations/" + org1 + "/invite", A,
                "{\"tenant_id\":10004,\"role\":\"viewer\"}"), "org-invite-already.json", false);
        expect(200, getH("/api/v1/organizations/" + org1 + "/members", A), "org-members.json", true);
        expect(200, postH("/api/v1/organizations/" + org1 + "/leave", B), "org-leave.json", false);
        expect(403, postH("/api/v1/organizations/" + org1 + "/leave", A), "org-leave-owner.json", false);
        expect(403, getH("/api/v1/organizations/" + org1 + "/members", B), "org-members-nonmember.json", false);
        expect(200, postH("/api/v1/organizations/join-by-id", B,
                "{\"organization_id\":\"" + org1 + "\"}"), "org-join-by-id-rejoin.json", true);
        expect(200, putH("/api/v1/organizations/" + org1 + "/members/10003", A, "{\"role\":\"editor\"}"),
                "org-update-member-role.json", false);
        expect(403, putH("/api/v1/organizations/" + org1 + "/members/10002", B, "{\"role\":\"viewer\"}"),
                "org-update-member-role-nonadmin.json", false);
        expect(403, putH("/api/v1/organizations/" + org1 + "/members/10002", A, "{\"role\":\"viewer\"}"),
                "org-update-member-role-owner.json", false);
        expect(400, putH("/api/v1/organizations/" + org1 + "/members/abc", A, "{\"role\":\"viewer\"}"),
                "org-update-member-role-bad-id.json", false);
        expect(200, delH("/api/v1/organizations/" + org1 + "/members/10004", A),
                "org-remove-member.json", false);
        expect(403, delH("/api/v1/organizations/" + org1 + "/members/10004", A),
                "org-remove-member-again.json", false);
        expect(403, delH("/api/v1/organizations/" + org1 + "/members/10002", B),
                "org-remove-member-nonadmin.json", false);

        // ── 4) invite-code / role upgrade / 成员上限 ──
        expect(200, postH("/api/v1/organizations/" + org1 + "/invite-code", A), "org-invite-code.json", true);
        expect(403, postH("/api/v1/organizations/" + org1 + "/invite-code", C), "org-invite-code-nonadmin.json", false);
        MvcResult up = expect(200, postH("/api/v1/organizations/" + org1 + "/request-upgrade", B,
                "{\"requested_role\":\"admin\",\"message\":\"promote me\"}"), "org-request-upgrade.json", true);
        String upId = jsonPath(up, "data.id");
        expect(400, postH("/api/v1/organizations/" + org1 + "/request-upgrade", B,
                "{\"requested_role\":\"admin\"}"), "org-request-upgrade-dup.json", false);
        expect(400, postH("/api/v1/organizations/" + org1 + "/request-upgrade", B,
                "{\"requested_role\":\"viewer\"}"), "org-request-upgrade-same.json", false);
        expect(500, postH("/api/v1/organizations/" + org1 + "/request-upgrade", A,
                "{\"requested_role\":\"admin\"}"), "org-request-upgrade-admin.json", false);
        expect(500, postH("/api/v1/organizations/" + org1 + "/request-upgrade", C,
                "{\"requested_role\":\"admin\"}"), "org-request-upgrade-nonmember.json", false);
        expect(200, getH("/api/v1/organizations/" + org1 + "/join-requests", A),
                "org-join-requests-upgrade.json", true);
        expect(200, getH("/api/v1/organizations/" + org1, B), "org-get-pending-upgrade.json", true);
        expect(200, putH("/api/v1/organizations/" + org1 + "/join-requests/" + upId + "/review", A,
                "{\"approved\":true,\"role\":\"admin\"}"), "org-upgrade-review-approve.json", false);
        MvcResult org3 = expect(201, postH("/api/v1/organizations", A,
                "{\"name\":\"shr-limit-org\",\"description\":\"limit 1\",\"member_limit\":1}"),
                "org3-create.json", true);
        String org3Id = jsonPath(org3, "data.id");
        String code3 = jsonPath(org3, "data.invite_code");
        expect(400, postH("/api/v1/organizations/join", B, "{\"invite_code\":\"" + code3 + "\"}"),
                "org-join-limit.json", false);
        expect(400, postH("/api/v1/organizations/" + org3Id + "/invite", A,
                "{\"tenant_id\":10003,\"role\":\"viewer\"}"), "org-invite-limit.json", false);
        expect(200, putH("/api/v1/organizations/" + ORG2, A, "{\"searchable\":true}"),
                "org2-update-searchable.json", true);
        expect(200, getH("/api/v1/organizations/" + ORG2, C), "org-get-nonmember-searchable.json", true);

        // ── 5) KB shares（OwnedKBOrAdmin 矩阵）──
        MvcResult share = expect(201, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"), "shr-kb-share.json", true);
        String shareId = jsonPath(share, "data.id");
        expect(201, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"editor\"}"),
                "shr-kb-share-update-existing.json", true);
        expect(403, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", B,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-kb-share-cross-tenant.json", false);
        expect(403, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", A,
                "{\"organization_id\":\"" + MISSING + "\",\"permission\":\"viewer\"}"),
                "shr-kb-share-missing-org.json", false);
        expect(403, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"boss\"}"),
                "shr-kb-share-bad-perm.json", false);
        expect(403, postH("/api/v1/knowledge-bases/" + KB_C + "/shares", C,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-kb-share-nonmember.json", false);
        expect(200, getH("/api/v1/knowledge-bases/" + KB_A + "/shares", A), "shr-kb-shares-list.json", true);
        expect(404, getH("/api/v1/knowledge-bases/" + MISSING + "/shares", A),
                "shr-kb-shares-list-missing-kb.json", false);
        expect(403, getH("/api/v1/knowledge-bases/" + KB_A + "/shares", B),
                "shr-kb-shares-list-notowner.json", false);
        expect(200, putH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, A, "{\"permission\":\"editor\"}"),
                "shr-kb-share-update.json", false);
        expect(403, putH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, A, "{\"permission\":\"boss\"}"),
                "shr-kb-share-update-badperm.json", false);
        expect(200, putH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, B, "{\"permission\":\"editor\"}"),
                "shr-kb-share-update-orgadmin.json", false);
        expect(403, delH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, C),
                "shr-kb-share-remove-nonadmin.json", false);
        expect(403, delH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + MISSING, A),
                "shr-kb-share-remove-missing.json", false);
        expect(200, delH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, A),
                "shr-kb-share-remove.json", false);
        expect(403, delH("/api/v1/knowledge-bases/" + KB_A + "/shares/" + shareId, A),
                "shr-kb-share-remove-again.json", false);
        expect(201, postH("/api/v1/knowledge-bases/" + KB_A + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"), "shr-kb-share-again.json", true);
        expect(200, postH("/api/v1/organizations/" + org1 + "/invite", A,
                "{\"tenant_id\":10004,\"role\":\"viewer\"}"), "org-invite-c-viewer.json", false);

        // ── 6) org shares 读面 + shared-knowledge-bases ──
        expect(200, getH("/api/v1/organizations/" + org1 + "/shares", A), "shr-org-shares.json", true);
        expect(403, getH("/api/v1/organizations/" + ORG2 + "/shares", C), "shr-org-shares-nonmember.json", false);
        expect(200, getH("/api/v1/shared-knowledge-bases", B), "shr-shared-kbs.json", true);
        expect(200, getH("/api/v1/shared-knowledge-bases", A), "shr-shared-kbs-owner.json", true);
        expect(200, getH("/api/v1/organizations/" + org1 + "/shared-knowledge-bases", A),
                "shr-org-shared-kbs.json", true);
        expect(403, getH("/api/v1/organizations/" + ORG2 + "/shared-knowledge-bases", C),
                "shr-org-shared-kbs-nonmember.json", false);

        // ── 7) agent shares 三条 + shared-agents ──
        MvcResult ashare = expect(201, postH("/api/v1/agents/" + AG_OK + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"editor\"}"), "shr-agent-share.json", true);
        String ashareId = jsonPath(ashare, "data.id");
        expect(201, postH("/api/v1/agents/" + AG_OK + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"editor\"}"),
                "shr-agent-share-exists.json", true);
        expect(400, postH("/api/v1/agents/" + AG_UNSET + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-agent-share-unset.json", false);
        expect(403, postH("/api/v1/agents/" + MISSING + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-agent-share-missing-agent.json", false);
        expect(403, postH("/api/v1/agents/" + AG_C + "/shares", C,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-agent-share-viewerorg.json", false);
        expect(200, getH("/api/v1/agents/" + AG_OK + "/shares", A), "shr-agent-shares-list.json", true);
        expect(404, getH("/api/v1/agents/" + AG_OK + "/shares", B), "shr-agent-shares-list-notowner.json", false);
        expect(403, delH("/api/v1/agents/" + AG_OK + "/shares/" + ashareId, C),
                "shr-agent-share-remove-nonadmin.json", false);
        expect(200, delH("/api/v1/agents/" + AG_OK + "/shares/" + ashareId, A),
                "shr-agent-share-remove.json", false);
        expect(403, delH("/api/v1/agents/" + AG_OK + "/shares/" + ashareId, A),
                "shr-agent-share-remove-again.json", false);
        expect(201, postH("/api/v1/agents/" + AG_OK + "/shares", A,
                "{\"organization_id\":\"" + org1 + "\",\"permission\":\"viewer\"}"),
                "shr-agent-share-again.json", true);
        expect(200, getH("/api/v1/organizations/" + org1 + "/agent-shares", A), "shr-org-agent-shares.json", true);
        expect(403, getH("/api/v1/organizations/" + ORG2 + "/agent-shares", C),
                "shr-org-agent-shares-nonmember.json", false);
        expect(200, getH("/api/v1/shared-agents", B), "shr-shared-agents.json", true);
        expect(200, getH("/api/v1/shared-agents", A), "shr-shared-agents-owner.json", true);
        expect(200, getH("/api/v1/organizations/" + org1 + "/shared-agents", A), "shr-org-shared-agents.json", true);
        expect(200, getH("/api/v1/organizations/" + org1 + "/shared-agents", B), "shr-org-shared-agents-b.json", true);
        expect(403, postH("/api/v1/shared-agents/disabled", V,
                "{\"agent_id\":\"" + AG_OK + "\",\"disabled\":true}"), "shr-shared-agent-disable-forbidden.json", false);
        expect(403, postH("/api/v1/shared-agents/disabled", A,
                "{\"agent_id\":\"" + MISSING + "\",\"disabled\":true}"), "shr-shared-agent-disable-missing.json", false);
        expect(200, postH("/api/v1/shared-agents/disabled", A,
                "{\"agent_id\":\"" + AG_OK + "\",\"disabled\":false}"), "shr-shared-agent-disable.json", false);
        expect(200, postH("/api/v1/shared-agents/disabled", C,
                "{\"agent_id\":\"" + AG_OK + "\",\"disabled\":true}"), "shr-shared-agent-disable-c.json", false);
        expect(200, getH("/api/v1/shared-agents", C), "shr-shared-agents-c.json", true);

        // ── 8) 收尾 resource_counts（含共享后的计数合并路径）──
        expect(200, getH("/api/v1/organizations", A), "org-list-late.json", true);
        expect(200, getH("/api/v1/organizations", B), "org-list-b.json", true);
    }

    // ── 辅助 ──

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

    /** 静态 golden（无掩码）：状态码 + 逐字节。 */
    private void assertGolden(MockHttpServletRequestBuilder req, int status, String goldenName)
            throws Exception {
        expect(status, req, goldenName, false);
    }

    private MvcResult expect(int status, MockHttpServletRequestBuilder req, String goldenName, boolean masked)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(status, r.getResponse().getStatus(), goldenName + " 状态码不符: " + raw(r));
        String actual = raw(r);
        String golden = golden(goldenName);
        if (masked) {
            assertEquals(mask(golden), mask(actual), goldenName);
        } else {
            assertEquals(golden, actual, goldenName);
        }
        return r;
    }

    private static MockHttpServletRequestBuilder getH(String url, String bearer) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        return bearer == null ? b : b.header("Authorization", bearer);
    }

    private static MockHttpServletRequestBuilder postH(String url, String bearer, String body) {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        if (bearer != null) {
            b = b.header("Authorization", bearer);
        }
        return body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder postH(String url, String bearer) {
        return postH(url, bearer, null);
    }

    private static MockHttpServletRequestBuilder putH(String url, String bearer, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(url).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder delH(String url, String bearer) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(url).header("Authorization", bearer);
    }

    private static String mask(String s) {
        s = TS_PATTERN.matcher(s).replaceAll("<ts>");
        s = UUID_PATTERN.matcher(s).replaceAll("\"<uuid>\"");
        s = CODE_PATTERN.matcher(s).replaceAll("\"$1\":\"<code>\"");
        s = BY_ORG_PATTERN.matcher(s).replaceAll("\"by_organization\":<map>");
        return s;
    }

    /** 从 MockMvc 结果按 JSON 路径取值（简易点分路径，数字段表示数组下标）。 */
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

    private static String raw(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String golden(String name) throws Exception {
        return new String(new ClassPathResource("contracts/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
    }
}
