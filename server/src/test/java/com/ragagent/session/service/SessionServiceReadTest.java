package com.ragagent.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ragagent.common.context.TenantContext;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 会话读路径的可见性判定（对照 Go {@code internal/application/service/session.go}
 * 的 {@code loadSessionForRead} / {@code runtimeMayBypassAdminConsoleRead}）。
 *
 * <p>这一组是**授权判定**，不是数据管道：错了就是把别人的会话交出去，
 * 或者把合法调用方挡在门外。每条用例都对应 Go 侧的一段注释。</p>
 */
class SessionServiceReadTest {

    private static final long TENANT = 10002L;
    private static final String SESSION_ID = "sess-1";

    private SessionRepository repo;
    private SessionService service;

    @BeforeEach
    void setUp() {
        repo = mock(SessionRepository.class);
        // 波 1 G1 起构造器多了写路径依赖（知识清理/建议删除）；读路径用例用 mock 隔离
        service = new SessionService(repo,
                org.mockito.Mockito.mock(com.ragagent.session.mapper.MessageRepository.class),
                org.mockito.Mockito.mock(com.ragagent.session.mapper.MessageSuggestionRepository.class),
                org.mockito.Mockito.mock(com.ragagent.knowledge.service.KnowledgeService.class));
        TenantContext.set(TENANT, null, null, false, "u-1", false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SessionLookupScope.clear();
    }

    /** 一条普通 Web 会话（owner = 调用方）。 */
    private static Session normalSession() {
        Session s = new Session();
        s.setId(SESSION_ID);
        s.setTenantId(TENANT);
        s.setUserId("u-1");
        return s;
    }

    /** 一条 API-Key 托管的会话（`api_tenant_key:` 前缀）。 */
    private static Session apiKeySession() {
        Session s = new Session();
        s.setId(SESSION_ID);
        s.setTenantId(TENANT);
        s.setUserId("api_tenant_key:10002:7");
        return s;
    }

    // ── 普通会话：owner 范围内谁都读得到 ─────────────────────────────────────

    @Test
    void ownerScopedReadOfANormalSessionSucceeds() {
        Session session = normalSession();
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(session);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThat(service.getSession(SESSION_ID)).isSameAs(session);
    }

    // ── 渠道托管会话 ────────────────────────────────────────────────────────

    /**
     * 非管理员**不得**打开渠道托管会话——即便 owner 范围（历史行的空 user_id）恰好命中。
     */
    @Test
    void nonAdminCannotOpenAChannelManagedSession() {
        // 历史行的空 owner：owner 范围条件是 (user_id = ? OR user_id IS NULL OR user_id = '')，
        // 所以这一行**会**落在普通用户的可见范围内——但它是 embed 渠道托管的
        // （description 带标记），非管理员仍然不该打开它。
        Session channelSession = new Session();
        channelSession.setId(SESSION_ID);
        channelSession.setTenantId(TENANT);
        channelSession.setUserId("");
        channelSession.setDescription("embed_channel:chan-1");
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(channelSession);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    /** IM 平台非空同样算渠道托管——即便 description/owner 都不带标记。 */
    @Test
    void nonEmptyImPlatformAloneMakesItChannelManaged() {
        Session session = normalSession();
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(session);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("feishu");

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    /** 管理员可以读：走的是 loadSessionForRead 的 getById 回退。 */
    @Test
    void adminFallsBackToTenantScopedReadForChannelSessions() {
        TenantContext.set(TENANT, null, "admin", false, "u-1", false);
        Session channelSession = apiKeySession();
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID)))
                .thenThrow(new SessionNotFoundException());
        when(repo.getById(TENANT, SESSION_ID)).thenReturn(channelSession);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("feishu");

        Session out = service.getSession(SESSION_ID);

        assertThat(out).isSameAs(channelSession);
        assertThat(out.getImPlatform()).isEqualTo("feishu");
    }

    /**
     * 管理员回退**只覆盖渠道托管行**：一条普通的、不在范围内的会话
     * （比如别人的 Web 会话）仍然要 404。
     */
    @Test
    void adminFallbackDoesNotReachOrdinarySessions() {
        TenantContext.set(TENANT, null, "admin", false, "u-1", false);
        Session otherUsersSession = new Session();
        otherUsersSession.setId(SESSION_ID);
        otherUsersSession.setUserId("someone-else");
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID)))
                .thenThrow(new SessionNotFoundException());
        when(repo.getById(TENANT, SESSION_ID)).thenReturn(otherUsersSession);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    /** 第二跳也没找到时，返回的是**第一跳**的错误——不泄漏"租户里确实有这一行"。 */
    @Test
    void secondHopMissReportsTheSameNotFound() {
        TenantContext.set(TENANT, null, "admin", false, "u-1", false);
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID)))
                .thenThrow(new SessionNotFoundException());
        when(repo.getById(TENANT, SESSION_ID)).thenThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ── owner 范围内的渠道会话：按主体类型放行 ───────────────────────────────

    /** embed 组件跑在 Viewer 权限下，但是自己那条渠道会话的合法属主。 */
    @Test
    void embedSessionMayOpenTheChannelSessionItOwns() {
        String ownerId = "embed_session:chan-1:visitor-1";
        TenantContext.set(TENANT,
                new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_SESSION, "chan-1:visitor-1"),
                "viewer", false, "u-1", false);

        Session owned = new Session();
        owned.setId(SESSION_ID);
        owned.setUserId(ownerId);
        owned.setDescription("embed_channel:chan-1");
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(owned);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThat(service.getSession(SESSION_ID)).isSameAs(owned);
    }

    /** 但**不是**自己那条就不行——owner 范围把它限制在这一行内是前提。 */
    @Test
    void embedSessionMayNotOpenSomeoneElsesSession() {
        TenantContext.set(TENANT,
                new TenantContext.Principal(TenantContext.PrincipalTypes.EMBED_SESSION, "chan-1:visitor-1"),
                "viewer", false, "u-1", false);

        Session other = new Session();
        other.setId(SESSION_ID);
        other.setUserId("embed_session:chan-1:visitor-2");
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(other);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    /** 普通 Web 用户（web_user 主体）不走任何旁路——渠道会话一律 404。 */
    @Test
    void defaultPrincipalTypeGetsNoBypass() {
        TenantContext.set(TENANT,
                new TenantContext.Principal(TenantContext.PrincipalTypes.WEB_USER, "u-1"),
                "viewer", false, "u-1", false);
        Session channelSession = apiKeySession();
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID))).thenReturn(channelSession);
        when(repo.getImPlatform(anyLong(), anyString())).thenReturn("");

        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ── 写路径严格 ──────────────────────────────────────────────────────────

    /**
     * {@link SessionService#getOwnedSession} **不做** Admin 回退：
     * 管理员能读 API-Key 会话，但不能改它。
     */
    @Test
    void getOwnedSessionHasNoAdminFallback() {
        TenantContext.set(TENANT, null, "admin", false, "u-1", false);
        when(repo.get(eq(TENANT), anyString(), eq(SESSION_ID)))
                .thenThrow(new SessionNotFoundException());

        assertThatThrownBy(() -> service.getOwnedSession(SESSION_ID))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // ── 入参与上下文校验 ────────────────────────────────────────────────────

    @Test
    void emptySessionIdIsRejected() {
        assertThatThrownBy(() -> service.getSession(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getOwnedSession(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 上下文里没有租户是**编程错误**（Go 直接 panic），不得降级成跨租户查询。 */
    @Test
    void missingTenantInContextFailsClosed() {
        TenantContext.clear();
        assertThatThrownBy(() -> service.getSession(SESSION_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getSessionByIdRequiresBothIds() {
        assertThatThrownBy(() -> service.getSessionById(0L, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getSessionById(TENANT, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code sessionUserIDForLookup} 的共享 agent 分支：标记存在时返回**空 owner**
     * （= 跳过 user 范围的内部查询）。
     */
    @Test
    void sharedAgentScopeDropsTheUserScope() {
        TenantContext.set(TENANT, null, null, false, "u-1", false);
        assertThat(SessionService.sessionUserIDForLookup()).isEqualTo("u-1");

        SessionLookupScope.mark();
        assertThat(SessionService.sessionUserIDForLookup()).isEmpty();
    }
}
