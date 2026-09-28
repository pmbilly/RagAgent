package com.ragagent.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ragagent.TestSchema;
import com.ragagent.apikey.filter.APIKeyRouteAuthorizer;
import com.ragagent.apikey.filter.APIKeyRoutePolicies;
import com.ragagent.apikey.filter.APIKeyRoutePolicy;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 会话 HTTP 层的契约测试（波 1 G1：CRUD + 置顶 8 条端点，对照 Go
 * {@code internal/handler/session/handler.go} 的 CreateSession / GetSession /
 * GetSessionsByTenant / UpdateSession / DeleteSession / BatchDeleteSessions /
 * PinSession / UnpinSession）。
 *
 * <h2>期望值来源：Go 实录</h2>
 * <p>golden 是对运行中的 Go dev server（:8080，db=localhost:15432）打真实请求录的，
 * 脚本 {@code scripts/record-session-golden.sh}，文件
 * {@code server/src/test/resources/contracts/session-*.json}。录制时 dev 库的
 * 测试租户里恰好遗留了一条空标题会话，所以列表类 golden 有 <b>4</b> 个条目——
 * H2 侧用 {@link #seedListState()} 精确复现录制时刻的状态（含 updated_at 的相对
 * 顺序：second-no-title &gt; golden-session &gt; u &gt; junk）。</p>
 *
 * <h2>掩码</h2>
 * <p>UUID（会话 id / user_id）与 RFC3339 时间戳两侧同掩码后逐字节比对。
 * 租户 id、title、description、is_pinned 等都是**裸比对**。</p>
 *
 * <h2>本轮 golden 实测抓到的关键契约（都钉在下面的用例里）</h2>
 * <ul>
 *   <li>渠道来源筛选（source=api 等）对非管理员**不是 403**，而是
 *       500 + {@code "error code: 1002, error message: …"}（Go 的 AppError.Error()
 *       被 handler 包进 InternalServerError）；</li>
 *   <li>{@code page=0} 被 omitempty 跳过（200，归一化成 page=1），
 *       负数才触发 min tag；</li>
 *   <li>非法 JSON 的 400 message 是 Go {@code encoding/json} 的措辞
 *       （{@code invalid character 'o' in literal null (expecting 'u')}）；</li>
 *   <li>批量删除里空白 id {@code "  "} 过 SanitizeForLog 保留 → 逐个判定不可见 →
 *       全部不可见回 404。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionHttpContractTest {

    private static final String BCRYPT =
            "$2a$10$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK"; // Passw0rd!
    private static final long TENANT = 10002L;
    /** 与录制时相同的用户 UUID（掩码后不比对，但保持语义可读）。 */
    private static final String USER_ID = "11111111-2222-3333-4444-555555555501";
    private static final String USER_EMAIL = "session-contract@weknora.test";
    private static final String VIEWER_ID = "11111111-2222-3333-4444-555555555504";
    private static final String VIEWER_EMAIL = "session-contract-viewer@weknora.test";
    private static final String UNKNOWN_ID = "11111111-2222-3333-4444-999999999999";

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\"");
    private static final Pattern TS_VALUE = Pattern.compile(
            "\"([a-z_]+)\":\"[2-9]\\d{3}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})\"");

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

    private String bearer;
    private String viewerBearer;

    // ══════════════════════════ 夹具 ══════════════════════════

    @BeforeEach
    void seed() throws Exception {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM message_suggestion_sets");

        Tenant tenant = new Tenant();
        tenant.setId(TENANT);
        tenant.setName("session-contract-tenant");
        tenant.setStatus("active");
        tenantMapper.insert(tenant);

        seedUser(USER_ID, "sessionowner", USER_EMAIL, "owner");
        seedUser(VIEWER_ID, "sessionviewer", VIEWER_EMAIL, "viewer");

        bearer = "Bearer " + login(USER_EMAIL);
        viewerBearer = "Bearer " + login(VIEWER_EMAIL);
    }

    private void seedUser(String id, String username, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
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

    /**
     * 精确复现录制 {@code session-list*.json} 时刻的库内状态：4 条 owner 名下的会话，
     * updated_at 的相对顺序决定列表序（QueryPaged 按 is_pinned DESC, pinned_at DESC,
     * updated_at DESC 排）。
     */
    private void seedListState() {
        seedSession("aaaaaaa1-0000-0000-0000-000000000001", "", "second no title",
                "2026-09-18 16:41:07.282881+08:00");
        seedSession("aaaaaaa1-0000-0000-0000-000000000002", "golden-session", "session golden",
                "2026-09-18 16:41:07.206864+08:00");
        seedSession("aaaaaaa1-0000-0000-0000-000000000003", "u", "",
                "2026-09-18 16:41:07.100000+08:00");
        seedSession("aaaaaaa1-0000-0000-0000-000000000004", "", "",
                "2026-09-18 16:41:07.000000+08:00");
    }

    private void seedSession(String id, String title, String description, String updatedAt) {
        seedSession(id, title, description, false, null, updatedAt);
    }

    private void seedSession(String id, String title, String description,
                             boolean pinned, String pinnedAt, String updatedAt) {
        jdbc.update("INSERT INTO sessions (id, tenant_id, title, description, user_id, "
                        + "is_pinned, pinned_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE), "
                        + "CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE))",
                id, TENANT, title, description, USER_ID, pinned, pinnedAt, updatedAt, updatedAt);
    }

    // ══════════════════════════ 1. 创建 ══════════════════════════

    @Test
    void createWithoutBodyIsEof() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-create-no-body.json"), raw(r));
    }

    /** Go 的 {@code encoding/json} 措辞，由 {@link com.ragagent.common.web.GoJsonBindError} 仿真。 */
    @Test
    void createWithInvalidJsonMatchesGoMessage() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-create-bad-json.json"), raw(r));
    }

    @Test
    void createEmptyObjectMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "{}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-create-empty-obj.json")), mask(raw(r)));
    }

    /** 未知字段被忽略（Go {@code encoding/json} 默认语义），title 落 "u"。 */
    @Test
    void createWithUnknownFieldMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "{\"title\":\"u\",\"unknown_key\":123}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-create-unknown-field.json")), mask(raw(r)));
    }

    @Test
    void createMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"),
                "{\"title\":\"golden-session\",\"description\":\"session golden\"}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-create.json")), mask(raw(r)));
        // 信封是 map：data < success（字母序）
        assertThat(raw(r)).startsWith("{\"data\":").contains(",\"success\":true");
    }

    /** Viewer 也能建会话（sessions 组是 Viewer+，不是 Admin+）。 */
    @Test
    void viewerCreateMatchesGo() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"), "{\"title\":\"viewer-session\"}")
                .header("Authorization", viewerBearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-create-viewer.json")), mask(raw(r)));
    }

    // ══════════════════════════ 2. 读取 ══════════════════════════

    @Test
    void getMatchesGo() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(get("/api/v1/sessions/" + id).header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-get.json")), mask(raw(r)));
    }

    @Test
    void getUnknownIsNotFound() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions/" + UNKNOWN_ID)
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-get-unknown.json"), raw(r));
    }

    // ══════════════════════════ 3. 列表 ══════════════════════════

    @Test
    void listMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list.json")), mask(raw(r)));
    }

    /** {@code page=0} 被 omitempty 跳过 → 200 且归一化成 page=1（golden 实测，不是 400）。 */
    @Test
    void listPageZeroIsSkippedByOmitEmpty() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?page=0").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list-page0.json")), mask(raw(r)));
    }

    @Test
    void listPaginationMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?page=1&page_size=1")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list-page2.json")), mask(raw(r)));
    }

    @Test
    void listKeywordFilterMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?keyword=golden")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list-keyword.json")), mask(raw(r)));
    }

    @Test
    void listKeywordNoHitMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?keyword=no-such-title-xyz")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-nohit.json"), raw(r));
    }

    @Test
    void listSourceWebMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?source=web")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list-source-web.json")), mask(raw(r)));
    }

    /** Admin/Owner 的租户级 api 视图：没有 API 会话 → 空列表（不是 403/500）。 */
    @Test
    void listSourceApiAsOwnerMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?source=api")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-source-api.json"), raw(r));
    }

    /** 未知 source 值同样走 Admin+ 租户视图 → 空列表。 */
    @Test
    void listSourceUnknownMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(get("/api/v1/sessions?source=bogus")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-source-unknown.json"), raw(r));
    }

    /** ⚠️ 非管理员 + 渠道 source：Go 是 **500**（AppError.Error() 被 handler 包 Internal）。 */
    @Test
    void viewerListWithChannelSourceIs500WithGoMessage() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions?source=api")
                .header("Authorization", viewerBearer));
        assertEquals(500, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-viewer-api.json"), raw(r));
    }

    @Test
    void viewerListMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions").header("Authorization", viewerBearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-viewer.json"), raw(r));
    }

    // ── 分页参数绑定：非整数 → strconv 文案；负数/超界 → validator 文案 ──

    @Test
    void listPageNotANumberMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions?page=abc").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-page-abc.json"), raw(r));
    }

    @Test
    void listPageNegativeMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions?page=-1").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-page-neg.json"), raw(r));
    }

    @Test
    void listPageSizeOverMaxMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions?page_size=1001")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-size-over.json"), raw(r));
    }

    @Test
    void listPageSizeNotANumberMatchesGo() throws Exception {
        MvcResult r = perform(get("/api/v1/sessions?page_size=abc")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-list-size-abc.json"), raw(r));
    }

    // ══════════════════════════ 4. 更新 ══════════════════════════

    @Test
    void updateWithoutBodyIsEof() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(put("/api/v1/sessions/" + id)
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-update-no-body.json"), raw(r));
    }

    @Test
    void updateWithInvalidJsonMatchesGoMessage() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(jsonBody(put("/api/v1/sessions/" + id), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-update-bad-json.json"), raw(r));
    }

    @Test
    void updateMatchesGo() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(jsonBody(put("/api/v1/sessions/" + id),
                "{\"title\":\"golden-session-renamed\",\"description\":\"updated desc\"}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-update.json")), mask(raw(r)));
    }

    /** 空串 title/description 也真的写空（repo.Update 的 map 白名单语义）。 */
    @Test
    void updateWithEmptyValuesWritesEmpty() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(jsonBody(put("/api/v1/sessions/" + id),
                "{\"title\":\"\",\"description\":\"\"}").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-update-empty-title.json")), mask(raw(r)));
    }

    @Test
    void updateUnknownIdIsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(put("/api/v1/sessions/" + UNKNOWN_ID), "{\"title\":\"x\"}")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-update-unknown-id.json"), raw(r));
    }

    /**
     * 客户端塞 {@code skill_maintenance:} 标记 → sanitize 丢弃（description 落空串），
     * 响应与后续 GET 都看不到标记。
     */
    @Test
    void updatePlantedMarkerIsDropped() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(jsonBody(put("/api/v1/sessions/" + id),
                "{\"title\":\"t\",\"description\":\"skill_maintenance:planted\"}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-update-marker.json")), mask(raw(r)));

        MvcResult after = perform(get("/api/v1/sessions/" + id)
                .header("Authorization", bearer));
        assertEquals(mask(golden("session-get-after-marker.json")), mask(raw(after)));
    }

    // ══════════════════════════ 5. 置顶 ══════════════════════════

    @Test
    void pinMatchesGoAndIsIdempotent() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(post("/api/v1/sessions/" + id + "/pin")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-pin.json"), raw(r));

        MvcResult again = perform(post("/api/v1/sessions/" + id + "/pin")
                .header("Authorization", bearer));
        assertEquals(golden("session-pin-again.json"), raw(again));
    }

    /**
     * 置顶后 GET：is_pinned=true 且 pinned_at 出现（掩码后比结构）。
     * golden 录制于 marker 更新（title="t"）之后，所以先复现那次更新。
     */
    @Test
    void getAfterPinMatchesGo() throws Exception {
        String id = createSessionId();
        perform(jsonBody(put("/api/v1/sessions/" + id),
                "{\"title\":\"t\",\"description\":\"skill_maintenance:planted\"}")
                .header("Authorization", bearer));
        perform(post("/api/v1/sessions/" + id + "/pin").header("Authorization", bearer));
        MvcResult r = perform(get("/api/v1/sessions/" + id).header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-get-pinned.json")), mask(raw(r)));
    }

    /** 置顶会话排在列表最前（is_pinned DESC, pinned_at DESC）。 */
    @Test
    void listPinnedMatchesGo() throws Exception {
        seedListState();
        jdbc.update("UPDATE sessions SET title = 't', description = '', is_pinned = TRUE, "
                        + "pinned_at = CAST('2026-09-18 16:41:07.937226+08:00' AS TIMESTAMP WITH TIME ZONE), "
                        + "updated_at = CAST('2026-09-18 16:41:07.937226+08:00' AS TIMESTAMP WITH TIME ZONE) "
                        + "WHERE title = 'golden-session'");
        MvcResult r = perform(get("/api/v1/sessions").header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(mask(golden("session-list-pinned.json")), mask(raw(r)));
    }

    @Test
    void pinUnknownIsNotFound() throws Exception {
        MvcResult r = perform(post("/api/v1/sessions/" + UNKNOWN_ID + "/pin")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-pin-unknown.json"), raw(r));
    }

    @Test
    void unpinMatchesGo() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(delete("/api/v1/sessions/" + id + "/pin")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-unpin.json"), raw(r));
    }

    @Test
    void unpinUnknownIsNotFound() throws Exception {
        MvcResult r = perform(delete("/api/v1/sessions/" + UNKNOWN_ID + "/pin")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-unpin-unknown.json"), raw(r));
    }

    // ══════════════════════════ 6. 批量删除 ══════════════════════════

    @Test
    void batchWithoutBodyIsInvalidRequest() throws Exception {
        MvcResult r = perform(delete("/api/v1/sessions/batch")
                .contentType("application/json").header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-no-body.json"), raw(r));
    }

    @Test
    void batchWithInvalidJsonIsInvalidRequest() throws Exception {
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"), "not-json")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-bad-json.json"), raw(r));
    }

    @Test
    void batchWithEmptyIdsIsBadRequest() throws Exception {
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"), "{\"ids\":[]}")
                .header("Authorization", bearer));
        assertEquals(400, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-empty-ids.json"), raw(r));
    }

    /**
     * {@code ["","  "]}：空串被 SanitizeForLog 清成空 → 丢弃；
     * 两个空格**保留**（SanitizeForLog 不动空格）→ 判定不可见 → 全部不可见 → 404。
     */
    @Test
    void batchWithBlankIdsIsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"), "{\"ids\":[\"\",\"  \"]}")
                .header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-blank-ids.json"), raw(r));
    }

    @Test
    void batchWithOnlyUnknownIdsIsNotFound() throws Exception {
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"),
                "{\"ids\":[\"" + UNKNOWN_ID + "\"]}").header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-unknown.json"), raw(r));
    }

    /** 未知 id 静默跳过，可见的那条被删（Go 的 visibleIDs 语义）。 */
    @Test
    void batchWithMixedIdsDeletesVisibleOnesOnly() throws Exception {
        seedListState();
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"),
                "{\"ids\":[\"" + UNKNOWN_ID + "\",\"aaaaaaa1-0000-0000-0000-000000000001\"]}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-batch-mixed.json"), raw(r));
        // 可见的被软删、不可见的本来就不存在
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ? AND deleted_at IS NULL",
                Integer.class, "aaaaaaa1-0000-0000-0000-000000000001"));
    }

    // ══════════════════════════ 7. 单个删除 ══════════════════════════

    @Test
    void deleteMatchesGoAndIsNotIdempotent() throws Exception {
        String id = createSessionId();
        MvcResult r = perform(delete("/api/v1/sessions/" + id).header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-delete.json"), raw(r));

        MvcResult again = perform(delete("/api/v1/sessions/" + id)
                .header("Authorization", bearer));
        assertEquals(404, again.getResponse().getStatus(), raw(again));
        assertEquals(golden("session-delete-again.json"), raw(again));
    }

    @Test
    void getDeletedSessionIsNotFound() throws Exception {
        String id = createSessionId();
        perform(delete("/api/v1/sessions/" + id).header("Authorization", bearer));
        MvcResult r = perform(get("/api/v1/sessions/" + id).header("Authorization", bearer));
        assertEquals(404, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-get-deleted.json"), raw(r));
        // 软删：行还在，只是 deleted_at 被置位
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, id));
    }

    // ══════════════════════════ 8. delete_all ══════════════════════════

    @Test
    void deleteAllMatchesGo() throws Exception {
        seedListState();
        MvcResult r = perform(jsonBody(delete("/api/v1/sessions/batch"), "{\"delete_all\":true}")
                .header("Authorization", bearer));
        assertEquals(200, r.getResponse().getStatus(), raw(r));
        assertEquals(golden("session-delete-all.json"), raw(r));

        MvcResult after = perform(get("/api/v1/sessions").header("Authorization", bearer));
        assertEquals(golden("session-list-after-delete-all.json"), raw(after));
    }

    // ══════════════════════════ 9. API-Key 策略登记 ══════════════════════════

    /**
     * 8 条路由在 API-Key 策略表里的登记形态：整组都是
     * {@code chat(fullAccess())}（对照 Go 的
     * {@code g.apiKeyGroup(r.Group("/sessions", g.Viewer()), apiKeyChat(apiKeyFullAccess()))}）。
     */
    @Test
    void allSessionRoutesAreRegisteredWithChatPolicy() {
        APIKeyRouteAuthorizer a = new APIKeyRouteAuthorizer();
        APIKeyRoutePolicies.registerAll(a);

        List<String[]> routes = List.of(
                new String[] {"POST", "/api/v1/sessions"},
                new String[] {"GET", "/api/v1/sessions"},
                new String[] {"GET", "/api/v1/sessions/{id}"},
                new String[] {"PUT", "/api/v1/sessions/{id}"},
                new String[] {"DELETE", "/api/v1/sessions/{id}"},
                new String[] {"DELETE", "/api/v1/sessions/batch"},
                new String[] {"POST", "/api/v1/sessions/{session_id}/pin"},
                new String[] {"DELETE", "/api/v1/sessions/{id}/pin"});

        APIKeyRoutePolicy expected = APIKeyRoutePolicy.chat(APIKeyRoutePolicy.fullAccess());
        for (String[] route : routes) {
            APIKeyRoutePolicy policy = a.lookup(route[0], route[1]);
            assertEquals(expected, policy, route[0] + " " + route[1] + " 的策略");
        }
    }

    // ══════════════════════════ 工具 ══════════════════════════

    /** 走真实 API 建一条 title=golden-session 的会话，返回 id。 */
    private String createSessionId() throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/sessions"),
                "{\"title\":\"golden-session\",\"description\":\"session golden\"}")
                .header("Authorization", bearer));
        assertEquals(201, r.getResponse().getStatus(), raw(r));
        Matcher m = Pattern.compile(
                "\"id\":\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")
                .matcher(raw(r));
        org.assertj.core.api.Assertions.assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String login(String email) throws Exception {
        MvcResult r = perform(jsonBody(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!\"}"));
        Matcher m = TOKEN.matcher(raw(r));
        org.assertj.core.api.Assertions.assertThat(m.find())
                .as("login 响应应含 token: " + raw(r)).isTrue();
        return m.group(1);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder,
                                                          String body) {
        return builder.contentType("application/json").content(body);
    }

    /** 按**原始字节**取响应体（MockMvc 默认 ISO-8859-1 会让中文变成 mojibake，§9）。 */
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

    /** 两侧同掩码：UUID 值 + 真实时间戳（2xxx-…）。 */
    private static String mask(String s) {
        String out = UUID_VALUE.matcher(s).replaceAll("\"$1\":\"<uuid>\"");
        return TS_VALUE.matcher(out).replaceAll("\"$1\":\"<ts>\"");
    }
}
