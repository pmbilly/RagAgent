package com.ragagent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListItem;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionPage;
import com.ragagent.session.mapper.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话列表查询（{@code QueryPaged}）——对照 Go internal/application/repository/session.go L142-279。
 *
 * <p>这是本模块分支最多的一条 SQL（六种来源桶 + 关键字 + agent + 分页 + pin 排序），
 * 所以每一条分支都单独钉一个用例。H2 走的是非 postgres 分支
 * （{@code LOWER(...) LIKE LOWER(...)}，无 {@code NULLS LAST}）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionQueryPagedTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionRepository repo;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private Session create(String title, String description, String owner) {
        Session s = new Session();
        s.setTenantId(TENANT);
        s.setTitle(title);
        s.setDescription(description);
        s.setUserId(owner);
        return repo.create(s);
    }

    /** IM 映射行的 id 是 VARCHAR(36)，用短自增串别用 UUID。 */
    private static int imSeq = 0;

    private void bindIm(String sessionId, String platform, String agentId) {
        jdbc.update("INSERT INTO im_channel_sessions "
                        + "(id, platform, user_id, chat_id, session_id, tenant_id, agent_id, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "ics-" + (++imSeq), platform, "im-user", "chat-1", sessionId, TENANT, agentId, "active");
    }

    private List<SessionListItem> query(String source, String keyword, String agentId) {
        return queryAs(source, keyword, agentId, "u1");
    }

    /**
     * @param owner 所有者范围。**admin-only 的来源要把范围清空**——服务层就是这样做的
     *              （{@code ListSessions} 里 {@code SessionListSourceRequiresAdmin} 为真时
     *              {@code query.UserID = ""}）。仓储本身不管这事，测试要复刻服务层这一步。
     */
    private List<SessionListItem> queryAs(String source, String keyword, String agentId, String owner) {
        return repo.queryPaged(SessionListQuery.of(keyword, source, agentId, 1, 50).withScope(TENANT, owner))
                .items();
    }

    private List<String> titles(String source) {
        return query(source, null, null).stream().map(SessionListItem::getTitle).toList();
    }

    private List<String> titlesAsAdmin(String source) {
        return queryAs(source, null, null, "").stream().map(SessionListItem::getTitle).toList();
    }

    // ── 基线 ────────────────────────────────────────────────────────────────

    @Test
    void listsOnlyTheTenantAndExcludesSoftDeleted() {
        create("mine", "", "u1");
        Session gone = create("gone", "", "u1");
        repo.delete(TENANT, "u1", gone.getId());
        Session otherTenant = new Session();
        otherTenant.setTenantId(99999L);
        otherTenant.setTitle("other");
        otherTenant.setDescription("");
        otherTenant.setUserId("u1");
        repo.create(otherTenant);

        assertThat(titles("")).containsExactly("mine");
    }

    @Test
    void skillMaintenanceSessionsAreHiddenFromEverySource() {
        // Go 的注释：这条排除刻意放在 applyBase 而不是 applySource——
        // 维护会话必须从**所有**桶里消失，包括不带筛选的那次列表
        create("normal", "", "u1");
        create("hidden", Session.SKILL_MAINTENANCE_SESSION_MARKER + "install", "u1");

        assertThat(titles("")).containsExactly("normal");
        assertThat(titles("web")).containsExactly("normal");
    }

    @Test
    void ownerScopeAcceptsOwnRowsAndEmptyOwnerRows() {
        create("mine", "", "u1");
        create("legacy", "", "");
        create("theirs", "", "u2");

        assertThat(titles("")).containsExactlyInAnyOrder("mine", "legacy");
    }

    // ── 来源桶 ──────────────────────────────────────────────────────────────

    @Test
    void webBucketExcludesEmbedAndApiSessions() {
        create("web", "", "u1");
        create("embed", Session.EMBED_SESSION_MARKER_PREFIX + "ch-1", "u1");
        create("api", "", "api_tenant_key:10002:7");

        assertThat(titles("web")).containsExactly("web");
    }

    @Test
    void apiBucketMatchesBothApiOwnerShapes() {
        create("web", "", "u1");
        create("tenantKey", "", "api_tenant_key:10002:7");
        create("externalUser", "", "api_external_user:10002:ext-1");

        // api 是 admin-only 来源：服务层会清空按人裁剪，测试按同样方式查，
        // 否则 api_* owner 的行会被 user 范围条件挡掉
        assertThat(titlesAsAdmin("api")).containsExactlyInAnyOrder("tenantKey", "externalUser");
    }

    @Test
    void embedBucketMatchesTheMarkerPrefixAndASingleChannelByExactDescription() {
        create("web", "", "u1");
        create("ch1", Session.EMBED_SESSION_MARKER_PREFIX + "ch-1", "u1");
        create("ch2", Session.EMBED_SESSION_MARKER_PREFIX + "ch-2", "u1");

        assertThat(titles("embed")).containsExactlyInAnyOrder("ch1", "ch2");
        // embed:<channelID> 是**等值**匹配，不是 LIKE
        assertThat(titles("embed:ch-1")).containsExactly("ch1");
    }

    @Test
    void imPlatformBucketUsesTheJoinAndExcludesThoseSessionsFromWeb() {
        Session web = create("web", "", "u1");
        Session im = create("im", "", "u1");
        bindIm(im.getId(), "feishu", "agent-1");

        assertThat(titles("feishu")).containsExactly("im");
        // 绑过 IM 的会话不再属于 web 桶（ics.id IS NULL 那一条）
        assertThat(titles("web")).containsExactly("web");
        assertThat(web.getId()).isNotEqualTo(im.getId());
    }

    @Test
    void sessionDeletedImMappingStillCountsAsImOrigin() {
        // Go 的注释：/clear 会软删映射并另起会话，所以**软删的映射也要算数**——
        // 否则那些历史 IM 会话会掉进用户的 web 桶
        Session im = create("im", "", "u1");
        bindIm(im.getId(), "feishu", "agent-1");
        jdbc.update("UPDATE im_channel_sessions SET deleted_at = CURRENT_TIMESTAMP WHERE session_id = ?",
                im.getId());

        assertThat(titles("feishu")).containsExactly("im");
        assertThat(titles("web")).isEmpty();
    }

    @Test
    void sourceFilterIsCaseInsensitive() {
        Session im = create("im", "", "u1");
        bindIm(im.getId(), "feishu", "agent-1");

        assertThat(titles("FEISHU")).containsExactly("im");
    }

    // ── 关键字 / agent ──────────────────────────────────────────────────────

    @Test
    void keywordMatchesTitleCaseInsensitively() {
        create("Product Manual", "", "u1");
        create("Other", "", "u1");

        assertThat(query("", "product", null)).extracting(SessionListItem::getTitle)
                .containsExactly("Product Manual");
        assertThat(query("", "MANUAL", null)).extracting(SessionListItem::getTitle)
                .containsExactly("Product Manual");
    }

    @Test
    void keywordEscapesLikeMetacharacters() {
        // escapeLikeKeyword 把 % _ \ 转义掉，所以 "%" 不会退化成"匹配一切"
        create("Product Manual", "", "u1");
        create("100% cotton", "", "u1");
        create("a_b", "", "u1");

        assertThat(query("", "100%", null)).extracting(SessionListItem::getTitle)
                .containsExactly("100% cotton");
        assertThat(query("", "_", null)).extracting(SessionListItem::getTitle)
                .containsExactly("a_b");
    }

    @Test
    void agentFilterOnlyMatchesSessionsWithAnImMapping() {
        Session a = create("withAgent", "", "u1");
        bindIm(a.getId(), "feishu", "agent-1");
        create("noMapping", "", "u1");

        assertThat(query("", null, "agent-1")).extracting(SessionListItem::getTitle)
                .containsExactly("withAgent");
        assertThat(query("", null, "agent-9")).isEmpty();
    }

    // ── 排序与分页 ──────────────────────────────────────────────────────────

    @Test
    void pinnedSessionsFloatToTheTop() throws Exception {
        create("older", "", "u1");
        Thread.sleep(5);
        Session pinned = create("pinnedButOlder", "", "u1");
        repo.setPinned(TENANT, "u1", pinned.getId(), true);
        Thread.sleep(5);
        create("newer", "", "u1");

        List<String> order = titles("");
        assertThat(order.get(0)).isEqualTo("pinnedButOlder");
        // 其余按 updated_at DESC
        assertThat(order.subList(1, order.size())).containsExactly("newer", "older");
    }

    @Test
    void paginationReportsTotalAndReturnsTheRightSlice() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("s" + i, "", "u1");
            Thread.sleep(5);
        }

        SessionPage first =
                repo.queryPaged(SessionListQuery.of(null, "", null, 1, 2).withScope(TENANT, "u1"));
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).hasSize(2);
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.pageSize()).isEqualTo(2);
        assertThat(first.items().get(0).getTitle()).isEqualTo("s2");

        SessionPage second =
                repo.queryPaged(SessionListQuery.of(null, "", null, 2, 2).withScope(TENANT, "u1"));
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).getTitle()).isEqualTo("s0");
    }

    @Test
    void imFieldsArePopulatedFromTheJoin() {
        Session im = create("im", "", "u1");
        bindIm(im.getId(), "feishu", "agent-1");
        jdbc.update("UPDATE im_channel_sessions SET thread_id = ?, im_channel_id = ? WHERE session_id = ?",
                "thread-1", "chan-1", im.getId());

        SessionListItem item = query("", null, null).get(0);
        assertThat(item.getImPlatform()).isEqualTo("feishu");
        assertThat(item.getImChatId()).isEqualTo("chat-1");
        assertThat(item.getImThreadId()).isEqualTo("thread-1");
        assertThat(item.getImUserId()).isEqualTo("im-user");
        assertThat(item.getImAgentId()).isEqualTo("agent-1");
        assertThat(item.getImChannelId()).isEqualTo("chan-1");
    }

    /** Web 建的会话没有 IM 来源：六个 IM 字段是**空串**而不是 {@code null}（§1.6 恒输出键的零值语义）。 */
    @Test
    void webSessionsHaveEmptyImFields() {
        create("web", "", "u1");
        SessionListItem item = query("", null, null).get(0);
        assertThat(item.getImPlatform()).isEmpty();
        assertThat(item.getImChatId()).isEmpty();
    }
}
