package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.common.prompt.AgentPromptPlaceholders;
import com.ragagent.common.tenant.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.common.settings.ConversationProperties;
import com.ragagent.event.Event;
import com.ragagent.event.EventBus;
import com.ragagent.event.EventIds;
import com.ragagent.event.EventType;
import com.ragagent.event.SessionTitleData;
import com.ragagent.knowledge.service.KnowledgeService;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatMessage;
import com.ragagent.llm.domain.ChatOptions;
import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.model.service.ModelRuntimeFactory;
import com.ragagent.model.service.ModelService;
import com.ragagent.common.wiki.WikiLanguageSupport;
import com.ragagent.session.domain.Message;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.domain.SessionPage;
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
    private final ModelService modelService;
    private final ModelRuntimeFactory modelRuntimeFactory;
    private final ConversationProperties conversationProps;
    private final com.ragagent.websearch.service.WebSearchTempKbStateService webSearchTempKbState;

    public SessionService(SessionRepository sessionRepository,
                          MessageRepository messageRepository,
                          MessageSuggestionRepository suggestionRepository,
                          KnowledgeService knowledgeService,
                          ModelService modelService,
                          ModelRuntimeFactory modelRuntimeFactory,
                          ConversationProperties conversationProps,
                          com.ragagent.websearch.service.WebSearchTempKbStateService webSearchTempKbState) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
        this.modelService = modelService;
        this.modelRuntimeFactory = modelRuntimeFactory;
        this.conversationProps = conversationProps;
        this.webSearchTempKbState = webSearchTempKbState;
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
                // （诊断留痕：owner 推导错/上下文丢失时这里是唯一线索，见
                //  2026-09-24 embed follow-up 的 SessionNotFound 排查）
                log.info("[session-read-miss] tenant={}, owner={}, session={}, principalType={}, principalId={}",
                        tenantId, ownerId, com.ragagent.common.security.LogSanitizer.sanitize(sessionId),
                        TenantContext.currentPrincipal() == null ? "" : TenantContext.currentPrincipal().type(),
                        TenantContext.currentPrincipal() == null ? "" : TenantContext.currentPrincipal().id());
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
            // （诊断留痕：渠道托管会话的运行时放行判定失败时，打印实际上下文）
            log.info("[session-read-forbidden] tenant={}, owner={}, session={}, principalType={}, principalId={}, role={}",
                    tenantId, ownerId, com.ragagent.common.security.LogSanitizer.sanitize(sessionId),
                    TenantContext.currentPrincipal() == null ? "" : TenantContext.currentPrincipal().type(),
                    TenantContext.currentPrincipal() == null ? "" : TenantContext.currentPrincipal().id(),
                    TenantContext.currentRole());
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
    public SessionPage listSessions(SessionListQuery query) {
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
    /**
     * 对照 Go UpdateSessionLastRequestState（session.go 的输入条状态写入）。
     * 波 4.6d 补（KnowledgeQaController 的异步 UI memo 用）。
     */
    public void updateSessionLastRequestState(String sessionId, com.ragagent.session.domain.SessionLastRequestState state) {
        long tenantId = TenantContext.currentTenantId() == null ? 0 : TenantContext.currentTenantId();
        sessionRepository.updateLastRequestState(tenantId, sessionUserIDForLookup(), sessionId, state);
    }

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
     * Go DeleteSession / BatchDeleteSessions 共用的「每会话清理」三件套
     * （知识 / 临时 KB / sandbox；建议删除在软删之后）。
     *
     * <p><b>已知差异（对照 Go）</b>：知识清理 Go 在 goroutine 里异步做（且走
     * cleanup-scope 授权），这里同步尽力而为——HTTP 响应不受影响，但删除请求会等
     * 知识清完才返回。临时 KB 清理（{@code DeleteWebSearchTempKBState}）失败被吞，
     * 无 HTTP 可见差异。</p>
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
        try {
            webSearchTempKbState.deleteTempKbState(sessionId);
        } catch (RuntimeException e) {
            log.warn("Failed to cleanup temporary KB for session {}: {}", sessionId, e.toString());
        }
    }

    /**
     * 对照 Go {@code GenerateTitle}（session.go L749-861 全量，2026-09-23 走查批接线）：
     * 标题已存在 → 直接返回；messages 为空时回库取第一条 user 消息（查不到 →
     * 500 "record not found"）；modelID 缺省找第一台 KnowledgeQA 模型 → GetChatModel
     * → system=GenerateSessionTitlePrompt（language 占位渲染）+ user=消息内容 →
     * Chat（temperature 0.3 / thinking=false）→ sanitizeGeneratedTitle（剥 think 前缀
     * + 100 码点截断）→ 落库。
     */
    public String generateTitle(Session session, List<Message> messages, String modelId) {
        if (session == null) {
            throw new BizException(AppError.internal("session cannot be empty"));
        }
        // 已有标题直接返回（不重生成、不落库）
        if (session.getTitle() != null && !session.getTitle().isEmpty()) {
            return session.getTitle();
        }

        Message message = null;
        if (messages == null || messages.isEmpty()) {
            message = messageRepository.getFirstMessageOfUser(session.getId());
            if (message == null) {
                // Go：GetFirstMessageOfUser 的 gorm.ErrRecordNotFound 原文
                throw new BizException(AppError.internal("record not found"));
            }
        } else {
            for (Message m : messages) {
                if (Message.ROLE_USER.equals(m.getRole())) {
                    message = m;
                    break;
                }
            }
        }
        if (message == null) {
            throw new BizException(AppError.internal("no user message found"));
        }

        if (modelId == null || modelId.isEmpty()) {
            List<com.ragagent.model.domain.Model> models = modelService.listModels();
            for (com.ragagent.model.domain.Model model : models) {
                if (model == null) {
                    continue;
                }
                if ("KnowledgeQA".equals(model.getType())) {
                    modelId = model.getId();
                    break;
                }
            }
            if (modelId == null || modelId.isEmpty()) {
                throw new BizException(
                        AppError.internal("no KnowledgeQA model available for title generation"));
            }
        }

        LlmChatClient chatModel;
        try {
            // 对照 Go L812：GetChatModel 失败原样返回（handler → 500 原文）
            chatModel = modelRuntimeFactory.getChatModel(modelId);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }

        String titlePrompt = AgentPromptPlaceholders.renderPromptPlaceholders(
                conversationProps.getGenerateSessionTitlePrompt(),
                Map.of("language", WikiLanguageSupport.languageNameFromContext()));
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.3); // Go 硬编码 0.3
        options.setThinking(Boolean.FALSE);
        ChatResponse response;
        try {
            response = chatModel.chat(
                    List.of(ChatMessage.system(titlePrompt),
                            ChatMessage.user(message.getContent() == null
                                    ? "" : message.getContent())),
                    options);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal(
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }

        GeneratedTitle generated = sanitizeGeneratedTitle(
                response == null || response.getContent() == null ? "" : response.getContent());
        if (generated.truncated()) {
            log.warn("Generated session title exceeded {} runes and was truncated, session={}, model={}",
                    MAX_SESSION_TITLE_RUNES, session.getId(), modelId);
        }
        session.setTitle(generated.title());
        sessionRepository.update(session, session.getUserId());
        return session.getTitle();
    }

    /** 对照 Go maxSessionTitleRunes（session.go L727-731）= 100。 */
    private static final int MAX_SESSION_TITLE_RUNES = 100;

    /** sanitizeGeneratedTitle 的返回对（Go 的多返回值元组）。 */
    private record GeneratedTitle(String title, boolean truncated) {
    }

    /**
     * 对照 Go sanitizeGeneratedTitle（session.go L738-745）：剥
     * {@code "<think>\n\n</think>"} 前缀 → Go TrimSpace → 超 100 码点按码点截断 + 再 trim。
     */
    private static GeneratedTitle sanitizeGeneratedTitle(String raw) {
        String text = raw;
        if (text.startsWith("<think>\n\n</think>")) {
            text = text.substring("<think>\n\n</think>".length());
        }
        String title = goTrimSpace(text);
        int count = title.codePointCount(0, title.length());
        if (count <= MAX_SESSION_TITLE_RUNES) {
            return new GeneratedTitle(title, false);
        }
        return new GeneratedTitle(goTrimSpace(
                title.substring(0, title.offsetByCodePoints(0, MAX_SESSION_TITLE_RUNES))), true);
    }

    /** Go strings.TrimSpace（unicode.IsSpace 全集；Java strip() 缺 U+0085/U+00A0）。 */
    private static String goTrimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isGoSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isGoSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    private static boolean isGoSpace(int cp) {
        return switch (cp) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u0085', '\u00A0' -> true;
            default -> Character.getType(cp) == Character.SPACE_SEPARATOR
                    || cp == 0x2028 || cp == 0x2029;
        };
    }

    /**
     * 对照 Go GenerateTitleAsync（session.go L863-937，2026-09-23 走查批接线）：
     * 捕获租户/请求 ID → 虚拟线程（bgCtx 语义：不依赖已结束的 HTTP 请求上下文）→
     * title 已存在跳过 → {@link #generateTitle}（首条 user 消息 = userQuery）→
     * emit {@code session_title} 事件（AgentStreamBridge 转发 SSE，前端据此更新标题）。
     */
    public void generateTitleAsync(Session session, String userQuery, String modelId,
                                   EventBus bus) {
        final Long tenantId = TenantContext.currentTenantId();
        final String requestId = TenantContext.currentRequestId();
        final String userId = TenantContext.currentUserId();
        Thread.ofVirtual().name("title-" + session.getId()).start(() -> {
            // 第 3 参是 role（不是 requestId！）：此前误把 requestId 传成 role，
            // 既让角色判定拿到未知串，又丢了 requestId。后台任务无角色 → null
            // （读取方 fail-closed 默认 Viewer），requestId 走独立 setter。
            TenantContext.set(tenantId, null, null, false, userId, false);
            TenantContext.setRequestId(requestId);
            try {
                if (session.getTitle() != null && !session.getTitle().isEmpty()) {
                    return;
                }
                Message userMessage = new Message();
                userMessage.setRole(Message.ROLE_USER);
                userMessage.setContent(userQuery);
                String title;
                try {
                    title = generateTitle(session, List.of(userMessage), modelId);
                } catch (RuntimeException e) {
                    log.error("Failed to generate title for session {}: {}",
                            session.getId(), e.getMessage());
                    return;
                }
                if (bus != null) {
                    try {
                        bus.emit(new Event(EventIds.generateEventID("session_title"),
                                EventType.EVENT_SESSION_TITLE, session.getId(),
                                new SessionTitleData(session.getId(), title), null, requestId));
                    } catch (RuntimeException e) {
                        log.error("Failed to emit title update event, session {}: {}",
                                session.getId(), e.getMessage());
                    }
                }
            } finally {
                TenantContext.clear();
            }
        });
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
