package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.mapper.MessageRepository;
import com.ragagent.session.mapper.MessageSuggestionRepository;
import com.ragagent.session.mapper.SessionRepository;

/**
 * 会话的**最小读路径**（对照 Go {@code internal/application/service/session.go}
 * 的 {@code GetSession} / {@code GetOwnedSession} / {@code GetSessionByID}
 * 与 {@code loadSessionForRead}）。
 *
 * <p>本阶段（5.2 步 3）只落 {@code continue-stream} 需要的那几个读方法；
 * 会话 CRUD 的其余部分随各自的端点补。</p>
 *
 * <h2>读路径为什么有两条</h2>
 * <ul>
 *   <li>{@link #getSession(String)} —— **读**用。带 Admin+ 回退：租户管理员可以打开
 *       渠道托管会话（API-Key / IM / embed），普通用户不行。
 *       非管理员即便 owner 范围恰好命中（历史行的空 user_id）也**不得**打开渠道行。</li>
 *   <li>{@link #getOwnedSession(String)} —— **写/变更**用。严格走 owner 范围，
 *       不做 Admin 回退。租户管理员可以读一条 API-Key 会话，但**不得**改它
 *       （标题、附件、流状态、消息）。</li>
 * </ul>
 * <p>两者刻意分开：合成一条会让"能读"悄悄变成"能改"。</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageSuggestionRepository suggestionRepository;
    private final KnowledgeService knowledgeService;

    public SessionService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          MessageSuggestionRepository suggestionRepository,
                          KnowledgeService knowledgeService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
    }

    // ── Go 的包级辅助 ──────────────────────────────────────────────────────

    /**
     * 对照 Go {@code sessionUserIDForLookup}（message.go L70-76）：共享 agent 流水线
     * 先解析出会话属主租户时，保持那次内部查询是**租户范围**的（返回空 owner）。
     */
    public static String sessionUserIDForLookup() {
        if (SessionLookupScope.isMarked()) {
            return "";
        }
        return SessionOwnerIds.currentSessionOwnerId();
    }

    /**
     * 对照 Go {@code runtimeMayBypassAdminConsoleRead}（L28-54）：
     * owner 范围内的非管理员调用方，什么情况下仍可打开一条渠道托管会话。
     *
     * <p>管理员走的是 {@link #loadSessionForRead} 里的 {@code getById} 回退，
     * 从不经过这里。</p>
     */
    static boolean runtimeMayBypassAdminConsoleRead(Session session, String imPlatform) {
        TenantContext.Principal principal = TenantContext.currentPrincipal();
        if (principal == null || session == null
                || principal.type() == null || principal.id() == null) {
            return false;
        }
        String type = principal.type();
        return switch (type) {
            // IM 用户的会话由渠道托管；只要确实带着渠道平台就放行。
            case TenantContext.PrincipalTypes.IM_USER ->
                    imPlatform != null && !imPlatform.trim().isEmpty();
            // API 主体只能读**自己这个 owner** 名下的 API 会话。
            case TenantContext.PrincipalTypes.API_TENANT,
                 TenantContext.PrincipalTypes.API_EXTERNAL_USER -> {
                String ownerId = SessionOwnerIds.currentSessionOwnerId();
                yield SessionOwnerIds.isApiSessionOwnerId(session.getUserId())
                        && session.getUserId().equals(ownerId);
            }
            // 一个 embed 组件虽然跑在 Viewer 权限下，却是自己那个渠道会话的合法属主
            // （上游 ensureEmbedSession 已连同签名句柄校验过）。
            // 只放行它自己拥有的那一条——仓储的 owner 范围已把它限制在这一行内。
            case TenantContext.PrincipalTypes.EMBED_SESSION ->
                    session.getUserId().equals(SessionOwnerIds.currentSessionOwnerId());
            default -> false;
        };
    }

    /**
     * 对照 Go {@code loadSessionForRead}（L57-95）：在调用方的按用户范围下加载会话，
     * 并带一条 Admin+ 回退——让管理员能从 Web 控制台读租户的渠道会话。
     */
    static Session loadSessionForRead(SessionRepository repo, long tenantId, String ownerId, String sessionId) {
        boolean isAdmin = TenantRole.fromString(TenantContext.currentRole())
                .hasPermission(TenantRole.ADMIN);

        Session session;
        try {
            session = repo.get(tenantId, ownerId, sessionId);
        } catch (SessionNotFoundException notFound) {
            if (!isAdmin) {
                // 非管理员到此为止：连"存在但你看不到"都不该知道
                throw notFound;
            }
            Session byId;
            try {
                byId = repo.getById(tenantId, sessionId);
            } catch (SessionNotFoundException stillMissing) {
                // 第二跳也没找到——返回**第一跳**的错误，别泄漏第二跳的存在性
                throw notFound;
            }
            String platform = repo.getImPlatform(tenantId, sessionId);
            if (!Session.requiresAdminConsoleRead(byId, platform)) {
                // 管理员只被额外允许读**渠道托管**行；普通行仍然要经过 owner 范围
                throw notFound;
            }
            if (!platform.isEmpty()) {
                byId.setImPlatform(platform);
            }
            return byId;
        }

        String imPlatform = repo.getImPlatform(tenantId, sessionId);
        if (Session.requiresAdminConsoleRead(session, imPlatform)
                && !isAdmin
                && !runtimeMayBypassAdminConsoleRead(session, imPlatform)) {
            // 刻意复用"不存在"：未授权者不该能区分这两种情况
            throw new SessionNotFoundException();
        }
        if (!imPlatform.isEmpty()) {
            session.setImPlatform(imPlatform);
        }
        return session;
    }

    // ── 读方法 ─────────────────────────────────────────────────────────────

    /** 对照 Go {@code GetSession}（L210-249）。 */
    public Session getSession(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("session id is required");
        }
        long tenantId = requireTenantId();
        String userId = SessionOwnerIds.currentSessionOwnerId();

        Session session = loadSessionForRead(sessionRepository, tenantId, userId, id);

        // IM 来源尽力而为：控制台读会话详情时要据此归类，
        // 但查失败**不得**让详情请求失败。
        if (session.getImPlatform() == null || session.getImPlatform().isEmpty()) {
            try {
                session.setImPlatform(sessionRepository.getImPlatform(tenantId, session.getId()));
            } catch (RuntimeException e) {
                log.warn("Failed to resolve IM platform for session {}: {}",
                        session.getId(), e.toString());
            }
        }
        return session;
    }

    /**
     * 对照 Go {@code GetOwnedSession}（L253-261）：严格在调用方 owner 范围内加载。
     * 与 {@link #getSession(String)} 不同，它**不做** Admin+ 的 API-Key 读回退，
     * 所以写/变更端点是正确的选择。
     */
    public Session getOwnedSession(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("session id is required");
        }
        return sessionRepository.get(requireTenantId(), SessionOwnerIds.currentSessionOwnerId(), id);
    }

    /** 对照 Go {@code GetSessionByID}（L263-273）：按租户 + id 加载，**不做 user 范围**。 */
    public Session getSessionById(long tenantId, String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("session id is required");
        }
        if (tenantId == 0) {
            throw new IllegalArgumentException("workspace id is required");
        }
        return sessionRepository.getById(tenantId, id);
    }

    // ── 写方法（波 1 G1，对照 Go session.go 各写方法） ─────────────────────

    /**
     * 对照 Go {@code CreateSession}（L188-207）：校验租户后落库。
     *
     * <p>Go 的校验失败返回普通 error，handler 包成 500 Internal（不是 400）——
     * 实际到不了这里（Auth 中间件已保证租户存在），但形态要保持一致。</p>
     */
    public Session createSession(Session session) {
        Long tenantId = session.getTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw new BizException(AppError.internal("tenant ID is required"));
        }
        return sessionRepository.create(session);
    }

    /**
     * 对照 Go {@code ListSessions}（L331-369）：带 keyword/source/agent_id 过滤的分页列表。
     *
     * <p>渠道来源筛选（api / embed / IM 平台）是**租户级管理员视图**：要求 Admin+，
     * 且命中时**丢掉按人裁剪**（Drop per-user owner scope）。其余来源保持调用方
     * 自己的 owner 范围。</p>
     */
    public SessionRepository.PagedItems listSessions(SessionListQuery query) {
        long tenantId = requireTenantId();
        String userId;
        if (Session.listSourceRequiresAdmin(query.source())) {
            if (!TenantRole.fromString(TenantContext.currentRole()).hasPermission(TenantRole.ADMIN)) {
                // ⚠️ 不是 403：Go 的 handler 把这个 ForbiddenError 包进
                // NewInternalServerError(err.Error())，而 AppError.Error() 的形态是
                // "error code: 1002, error message: …" —— golden 实测是 500 + 该文案。
                throw new BizException(AppError.internal(
                        "error code: 1002, error message: listing channel sessions requires tenant admin or owner role"));
            }
            userId = "";
        } else {
            userId = SessionOwnerIds.currentSessionOwnerId();
        }
        return sessionRepository.queryPaged(query.withScope(tenantId, userId));
    }

    /**
     * 对照 Go {@code SetSessionPinned}（L398-410）。
     *
     * @return 受影响行数；0 = 会话不存在或不可见（handler 据此回 404）
     */
    public long setSessionPinned(String sessionId, boolean pinned) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new BizException(AppError.internal("session id is required"));
        }
        long tenantId = requireTenantId();
        String userId = SessionOwnerIds.currentSessionOwnerId();
        return sessionRepository.setPinned(tenantId, userId, sessionId, pinned);
    }

    /**
     * 对照 Go {@code UpdateSession}（L412-442）：**写路径用 owner 范围严格加载**
     * （{@code repo.Get}，不是 loadSessionForRead——管理员能读但**不得改**），
     * 然后 sanitize description 再更新（只写 title/description/updated_at）。
     */
    public void updateSession(Session session) {
        if (session.getId() == null || session.getId().isEmpty()) {
            throw new BizException(AppError.internal("session id is required"));
        }
        String userId = SessionOwnerIds.currentSessionOwnerId();
        Session existing = sessionRepository.get(session.getTenantId(), userId, session.getId());
        session.setDescription(Session.sanitizeClientSessionDescription(
                session.getDescription(), existing.getDescription()));
        sessionRepository.update(session, userId);
    }

    /**
     * 对照 Go {@code DeleteSession}（L471-538）：先严格范围加载（404 门槛），
     * 再做三件套清理（知识 / 临时 KB / 建议 / sandbox），最后软删。
     */
    public void deleteSession(String id) {
        if (id == null || id.isEmpty()) {
            throw new BizException(AppError.internal("session id is required"));
        }
        long tenantId = requireTenantId();
        String userId = SessionOwnerIds.currentSessionOwnerId();

        sessionRepository.get(tenantId, userId, id);

        cleanupSessionResources(tenantId, id);

        long rows = sessionRepository.delete(tenantId, userId, id);
        if (rows == 0) {
            throw new SessionNotFoundException();
        }
    }

    /**
     * 对照 Go {@code BatchDeleteSessions}（L540-609）：先筛出**可见**的 id
     * （逐个 {@code repo.Get}，不可见的静默跳过），全部不可见 → 404；
     * 清理与删除都只对可见集合做。
     */
    public void batchDeleteSessions(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(AppError.internal("session ids are required"));
        }
        long tenantId = requireTenantId();
        String userId = SessionOwnerIds.currentSessionOwnerId();

        List<String> visibleIds = new ArrayList<>();
        for (String id : ids) {
            try {
                sessionRepository.get(tenantId, userId, id);
                visibleIds.add(id);
            } catch (SessionNotFoundException notFound) {
                // Go：该 id 不可见就跳过，不算错误
            }
        }
        if (visibleIds.isEmpty()) {
            throw new SessionNotFoundException();
        }

        for (String id : visibleIds) {
            cleanupSessionResources(tenantId, id);
        }

        sessionRepository.batchDelete(tenantId, userId, visibleIds);
        for (String id : visibleIds) {
            try {
                suggestionRepository.deleteBySessionId(tenantId, id);
            } catch (RuntimeException e) {
                log.warn("Failed to delete suggestions for session {}: {}", id, e.toString());
            }
        }
    }

    /**
     * 对照 Go {@code DeleteAllSessions}（L611-668）：列出当前范围的全部会话做清理，
     * 再整体软删 + 删建议。列表失败**不阻断**删除（Go 的 Warnf + 继续走）。
     */
    public void deleteAllSessions() {
        long tenantId = requireTenantId();
        String userId = SessionOwnerIds.currentSessionOwnerId();

        List<Session> sessions;
        try {
            sessions = sessionRepository.getByTenantId(tenantId, userId);
        } catch (RuntimeException e) {
            log.warn("Failed to list sessions for cleanup: {}", e.toString());
            sessions = null;
        }
        if (sessions != null) {
            for (Session session : sessions) {
                cleanupSessionResources(tenantId, session.getId());
            }
        }

        sessionRepository.deleteAllByTenantId(tenantId, userId);

        if (sessions != null) {
            for (Session session : sessions) {
                try {
                    suggestionRepository.deleteBySessionId(tenantId, session.getId());
                } catch (RuntimeException e) {
                    log.warn("Failed to delete suggestions for session {}: {}",
                            session.getId(), e.toString());
                }
            }
        }
    }

    /**
     * Go DeleteSession / BatchDeleteSessions 共用的「每会话清理」三件套。
     *
     * <p><b>已知差异（对照 Go，待随对应波次收口）</b>：</p>
     * <ul>
     *   <li>知识清理：Go 在 goroutine 里异步做（且走 cleanup-scope 授权），错误全吞；
     *       这里同步尽力而为——HTTP 响应不受影响，但删除请求会等知识清完才返回。</li>
     *   <li>临时 KB 清理（webSearchStateRepo）：web-search 模块未翻译，TODO(波 2)。
     *       Go 侧失败同样被吞，无 HTTP 可见差异。</li>
     *   <li>destroyBoundSandbox：sandbox 模块未翻译，TODO(波 3)。后端 Disabled 时
     *       Go 本就是 no-op；翻译 sandbox 时补上。</li>
     * </ul>
     */
    private void cleanupSessionResources(long tenantId, String sessionId) {
        try {
            List<String> knowledgeIds = messageRepository.getKnowledgeIdsBySessionId(sessionId);
            for (String knowledgeId : knowledgeIds) {
                try {
                    knowledgeService.deleteKnowledge(knowledgeId);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete chat history knowledge for session {}: {}",
                            sessionId, e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to get knowledge IDs for session {}: {}", sessionId, e.toString());
        }
        // TODO(波 2 web-search): Go 在此调 webSearchStateRepo.DeleteWebSearchTempKBState（失败被吞）。
        // TODO(波 3 sandbox): Go 在此 destroyBoundSandbox（会话绑定的 MicroVM；Disabled 后端是 no-op）。
    }

    /**
     * 对照 Go {@code types.MustTenantIDFromContext}：上下文中没有租户是**编程错误**，
     * Go 直接 panic。Java 侧同样不该悄悄降级成"无租户查询"——那会跨租户泄漏。
     */
    private static long requireTenantId() {
        Long tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("types.TenantIDContextKey not set in context");
        }
        return tenantId;
    }
}
