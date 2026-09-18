package com.ragagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.ragagent.TestSchema;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionLastRequestState;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.mapper.SessionMapper;
import com.ragagent.session.mapper.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话仓储语义（H2）——对照 Go internal/application/repository/session.go。
 *
 * <p>覆盖的是 SQL 层的真实行为，mock 测不出来的那部分：可见性范围条件、软删除、
 * 影响行数（用于区分"不存在/不可见"与真出错）、以及 {@code is_pinned} 这个
 * **字段名与列名不一致**的字段。</p>
 *
 * <p>⚠️ {@code @AutoConfigureMockMvc} 看似多余，但它让本类与其余契约测试共用同一个
 * Spring 上下文缓存键——多一个独立上下文键会波及 MCP 的静态 SsrfGuard 装配（详见
 * {@code TenantAPIKeyRepositoryTest} 的类注释）。惯例照抄即可。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionRepositoryTest {

    private static final long TENANT = 10002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SessionRepository repo;
    @Autowired
    private SessionMapper mapper;

    @BeforeEach
    void seed() {
        TestSchema.createTables(jdbc);
        TestSchema.resetData(jdbc);
    }

    private Session newSession(String title, String owner) {
        Session s = new Session();
        s.setTenantId(TENANT);
        s.setTitle(title);
        s.setDescription("");
        if (owner != null) {
            s.setUserId(owner);
        }
        return s;
    }

    // ── 创建 ────────────────────────────────────────────────────────────────

    @Test
    void createAlwaysAssignsAFreshId() {
        // Go 的 BeforeCreate 是**无条件**覆盖 ID，不是"为空才生成"
        Session s = newSession("t", "u1");
        s.setId("caller-supplied-id");

        Session created = repo.create(s);

        assertThat(created.getId()).isNotEqualTo("caller-supplied-id");
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
    }

    @Test
    void createPersistsEmptyStringsNotNull() {
        // 对照 §9「Go 非指针零值在 DB 层同样是 ''」：GORM 写的是空串而非 NULL
        Session created = repo.create(newSession("t", null));
        String userId = jdbc.queryForObject(
                "SELECT user_id FROM sessions WHERE id = ?", String.class, created.getId());
        assertThat(userId).isNotNull().isEmpty();
    }

    // ── 可见性范围 ──────────────────────────────────────────────────────────

    @Test
    void userScopeAcceptsOwnRowsAndLegacyEmptyOwnerRows() {
        // 对照 applySessionUserScope：user_id 为空的历史行对所有 owner 都可见
        Session mine = repo.create(newSession("mine", "u1"));
        Session legacy = repo.create(newSession("legacy", ""));  // user_id = ''
        Session other = repo.create(newSession("other", "u2"));

        assertThat(repo.get(TENANT, "u1", mine.getId()).getTitle()).isEqualTo("mine");
        assertThat(repo.get(TENANT, "u1", legacy.getId()).getTitle()).isEqualTo("legacy");
        assertThatThrownBy(() -> repo.get(TENANT, "u1", other.getId()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void emptyOwnerScopeSeesEverythingInTheTenant() {
        // ownerID 为空 = 不做范围裁剪（API-Key / 租户级调用方走这条）
        Session a = repo.create(newSession("a", "u1"));
        Session b = repo.create(newSession("b", "u2"));

        assertThat(repo.getByTenantId(TENANT, "")).extracting(Session::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void tenantIsolationIsEnforced() {
        Session mine = repo.create(newSession("mine", "u1"));
        assertThatThrownBy(() -> repo.get(99999L, "u1", mine.getId()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void getByIdSkipsTheOwnerScope() {
        // GetByID 是"管理员越权读"的第二跳，**不带** user 范围
        Session other = repo.create(newSession("other", "u2"));
        assertThat(repo.getById(TENANT, other.getId()).getTitle()).isEqualTo("other");
    }

    // ── 软删除 ──────────────────────────────────────────────────────────────

    @Test
    void deleteIsSoftAndHidesTheRowFromReads() {
        Session s = repo.create(newSession("t", "u1"));

        assertThat(repo.delete(TENANT, "u1", s.getId())).isEqualTo(1);
        // 行还在，只是被标记
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM sessions WHERE id = ?", Object.class, s.getId())).isNotNull();
        assertThatThrownBy(() -> repo.get(TENANT, "u1", s.getId()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void deleteByAnotherUserAffectsNothing() {
        Session s = repo.create(newSession("t", "u1"));
        assertThat(repo.delete(TENANT, "u2", s.getId())).isZero();
        assertThat(repo.get(TENANT, "u1", s.getId())).isNotNull();
    }

    @Test
    void batchDeleteWithEmptyIdsIsANoOp() {
        // 对照 Go BatchDelete 的显式早退：空 ids 直接 0，**不做**全租户删除
        repo.create(newSession("t", "u1"));
        assertThat(repo.batchDelete(TENANT, "u1", List.of())).isZero();
        assertThat(repo.getByTenantId(TENANT, "u1")).hasSize(1);
    }

    @Test
    void deleteAllByTenantIdRemovesEveryVisibleRow() {
        repo.create(newSession("a", "u1"));
        repo.create(newSession("b", "u1"));
        repo.create(newSession("c", "u2"));

        assertThat(repo.deleteAllByTenantId(TENANT, "u1")).isEqualTo(2);
        assertThat(repo.getByTenantId(TENANT, "u1")).isEmpty();
        assertThat(repo.getByTenantId(TENANT, "u2")).hasSize(1);
    }

    // ── 更新 ────────────────────────────────────────────────────────────────

    @Test
    void updateOverwritesTitleAndDescriptionUnconditionally() {
        Session s = repo.create(newSession("old", "u1"));
        s.setTitle("");
        s.setDescription("");

        assertThat(repo.update(s, "u1")).isEqualTo(1);

        Session after = repo.get(TENANT, "u1", s.getId());
        assertThat(after.getTitle()).isEmpty();
        assertThat(after.getDescription()).isEmpty();
    }

    @Test
    void updateByAnotherUserAffectsNothing() {
        Session s = repo.create(newSession("old", "u1"));
        s.setTitle("hacked");
        assertThat(repo.update(s, "u2")).isZero();
        assertThat(repo.get(TENANT, "u1", s.getId()).getTitle()).isEqualTo("old");
    }

    @Test
    void setOwnerIdIgnoresTheUserScope() {
        // SetOwnerID 刻意不做 user 范围（对照 Go 的注释与 SQL）
        Session s = repo.create(newSession("t", ""));
        assertThat(repo.setOwnerId(TENANT, s.getId(), "u9")).isEqualTo(1);
        assertThat(repo.getById(TENANT, s.getId()).getUserId()).isEqualTo("u9");
    }

    @Test
    void setPinnedTogglesBothColumns() {
        Session s = repo.create(newSession("t", "u1"));

        assertThat(repo.setPinned(TENANT, "u1", s.getId(), true)).isEqualTo(1);
        Session pinned = repo.get(TENANT, "u1", s.getId());
        assertThat(pinned.isPinned()).isTrue();
        assertThat(pinned.getPinnedAt()).isNotNull();

        // 取消置顶要把 pinned_at 显式清成 NULL，不是"省略该列"
        assertThat(repo.setPinned(TENANT, "u1", s.getId(), false)).isEqualTo(1);
        Session unpinned = repo.get(TENANT, "u1", s.getId());
        assertThat(unpinned.isPinned()).isFalse();
        assertThat(unpinned.getPinnedAt()).isNull();
    }

    // ── last_request_state（走遗留的 agent_config jsonb 列） ─────────────────

    @Test
    void updateLastRequestStateWritesOnlyThatColumn() {
        Session s = repo.create(newSession("keep-me", "u1"));

        SessionLastRequestState state = new SessionLastRequestState();
        state.setAgentId("agent-1");
        state.setAgentEnabled(true);
        state.setModelId("model-1");
        state.setWebSearchEnabled(true);

        assertThat(repo.updateLastRequestState(TENANT, "u1", s.getId(), state)).isEqualTo(1);

        Session after = repo.get(TENANT, "u1", s.getId());
        // 这条路径**不碰** title/description
        assertThat(after.getTitle()).isEqualTo("keep-me");
        assertThat(after.getLastRequestState()).isNotNull();
        assertThat(after.getLastRequestState().getAgentId()).isEqualTo("agent-1");
        assertThat(after.getLastRequestState().isAgentEnabled()).isTrue();
        assertThat(after.getLastRequestState().isWebSearchEnabled()).isTrue();
    }

    @Test
    void updateLastRequestStateRespectsTheOwnerScope() {
        Session s = repo.create(newSession("t", "u1"));
        assertThat(repo.updateLastRequestState(TENANT, "u2", s.getId(),
                new SessionLastRequestState())).isZero();
    }

    // ── IM 来源 ─────────────────────────────────────────────────────────────

    @Test
    void getImPlatformReturnsEmptyWhenNoMappingExists() {
        Session s = repo.create(newSession("t", "u1"));
        assertThat(repo.getImPlatform(TENANT, s.getId())).isEmpty();
    }

    @Test
    void getImPlatformFindsTheMappingAndIgnoresItsSoftDelete() {
        // Go 的注释写明：任何映射（活的或被清掉的）都表明这个会话来自 IM——
        // 所以这条查询**不筛** im_channel_sessions.deleted_at
        Session s = repo.create(newSession("t", "u1"));
        jdbc.update("INSERT INTO im_channel_sessions "
                        + "(id, platform, user_id, chat_id, session_id, tenant_id, status, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "ics-1", "feishu", "im-user", "chat-1", s.getId(), TENANT, "active");

        assertThat(repo.getImPlatform(TENANT, s.getId())).isEqualTo("feishu");
    }

    @Test
    void getImPlatformIsScopedToTheTenant() {
        Session s = repo.create(newSession("t", "u1"));
        jdbc.update("INSERT INTO im_channel_sessions "
                        + "(id, platform, user_id, chat_id, session_id, tenant_id, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "ics-1", "feishu", "im-user", "chat-1", s.getId(), TENANT, "active");

        assertThat(repo.getImPlatform(99999L, s.getId())).isEmpty();
    }

    // ── 分页 ────────────────────────────────────────────────────────────────

    @Test
    void pagedReadClampsPageAndSizeLikeGoPagination() throws Exception {
        for (int i = 0; i < 3; i++) {
            Session s = repo.create(newSession("s" + i, "u1"));
            // 让 updated_at 严格递增，否则同毫秒内的排序不稳定
            Thread.sleep(5);
            s.setTitle("s" + i);
            repo.update(s, "u1");
        }

        // page<1 → 1；pageSize<1 → 20（对照 types.Pagination）
        SessionRepository.PagedSessions all = repo.getPagedByTenantId(TENANT, "u1", 0, 0);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.sessions()).hasSize(3);

        SessionRepository.PagedSessions firstPage = repo.getPagedByTenantId(TENANT, "u1", 1, 2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.sessions()).hasSize(2);
        // updated_at DESC：最后改的 s2 在最前
        assertThat(firstPage.sessions().get(0).getTitle()).isEqualTo("s2");

        SessionRepository.PagedSessions secondPage = repo.getPagedByTenantId(TENANT, "u1", 2, 2);
        assertThat(secondPage.sessions()).hasSize(1);
    }
}
