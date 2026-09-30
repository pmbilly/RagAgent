package com.ragagent.session.mapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionLastRequestState;
import com.ragagent.session.domain.SessionListItem;
import com.ragagent.session.domain.SessionListQuery;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.domain.SessionOwnerIds;
import com.ragagent.session.domain.SessionPage;
import org.springframework.stereotype.Component;

/**
 * 会话仓储（对照 Go internal/application/repository/session.go）。
 *
 * <h2>GORM 隐式行为 → Java 的等效清单（约定 §3 要求显式列出）</h2>
 * <ol>
 *   <li><b>钩子 BeforeCreate</b>（Go L141-144）：无条件 {@code s.ID = uuid.New().String()}。
 *       → {@link #create} 里无条件覆盖新 UUID（**不是**"为空才生成"）。</li>
 *   <li><b>软删除</b>：{@code gorm.DeletedAt}。GORM 会给模型查询自动加
 *       {@code deleted_at IS NULL}。Java 不用 {@code @TableLogic}，每条查询显式加条件；
 *       删除走 UPDATE 置 {@code deleted_at}（见 §9 的既定做法）——**硬删会连带触发
 *       {@code im_channel_sessions_session_id_fkey} 的 ON DELETE CASCADE**。</li>
 *   <li><b>可见性范围 {@code applySessionUserScope}</b>（Go L20-26）：{@code userID} 非空时加
 *       {@code (user_id = ? OR user_id IS NULL OR user_id = '')}——**空 owner 的历史行/API 行
 *       对所有人都可见**。这条件出现在 Get/GetByTenantID/GetPagedByTenantID/Update/Delete/
 *       BatchDelete/DeleteAllByTenantID/UpdateLastRequestState 共 8 处，Java 侧收敛成
 *       {@link #applyUserScope}。</li>
 *   <li><b>默认排序</b>：列表类查询显式 {@code updated_at DESC}（Go L100/L129）。</li>
 *   <li><b>Updates(map)</b>：Go 用 map 绕开零值省略，保证 {@code title}/{@code description}
 *       无条件覆盖（改成空串也真的写空）。Java 用 {@code LambdaUpdateWrapper.set} 逐列写，
 *       同一语义。</li>
 * </ol>
 */
@Component
public class SessionRepository {

    private static final String EMBED_PREFIX = "embed:";

    private final SessionMapper mapper;
    /** 对照 Go 本方法每次问的 {@code db.Dialector.Name() == "postgres"}（同 wiki 的做法）。 */
    private final boolean postgres;

    public SessionRepository(SessionMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.postgres = detectPostgres(dataSource);
    }

    /**
     * 对照 Go {@code applySessionUserScope}（L20-26）：ownerID 为空则不加范围条件，
     * 否则加 {@code (user_id = ? OR user_id IS NULL OR user_id = '')}。
     *
     * <p>这个范围条件在 Go 里散落在 8 个方法里，Java 侧收敛成这一处——
     * 改它之前先确认每条路径的语义确实相同（例如 {@code SetOwnerID} 刻意**不用**它）。</p>
     */
    private static LambdaQueryWrapper<Session> applyUserScope(LambdaQueryWrapper<Session> w, String userId) {
        if (userId != null && !userId.isEmpty()) {
            w.and(q -> q.eq(Session::getUserId, userId)
                    .or().isNull(Session::getUserId)
                    .or().eq(Session::getUserId, ""));
        }
        return w;
    }

    private static LambdaUpdateWrapper<Session> applyUserScope(LambdaUpdateWrapper<Session> w, String userId) {
        if (userId != null && !userId.isEmpty()) {
            w.and(q -> q.eq(Session::getUserId, userId)
                    .or().isNull(Session::getUserId)
                    .or().eq(Session::getUserId, ""));
        }
        return w;
    }

    /** 对照 Go 的 {@code escapeLikeKeyword}（internal/application/repository/knowledge.go L22-27）。 */
    static String escapeLikeKeyword(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ── 写 ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code Create}（L34-42）：显式写时间戳 + 钩子生成 UUID。 */
    public Session create(Session session) {
        OffsetDateTime now = OffsetDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setId(UUID.randomUUID().toString());
        mapper.insert(session);
        return session;
    }

    /**
     * 对照 Go {@code Update}（L313-324）：**只写 title / description / updated_at**。
     *
     * <p>用 map 是刻意的——Go 借此保证把 title 改成空串也会真的写空，而不是被零值省略。
     * 这里同样逐列显式 SET。</p>
     *
     * @return 受影响行数（0 = 没命中，调用方据此区分"不存在/不可见"与真的出错）
     */
    public long update(Session session, String userId) {
        session.setUpdatedAt(OffsetDateTime.now());
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, session.getTenantId())
                .eq(Session::getId, session.getId())
                .isNull(Session::getDeletedAt)
                .set(Session::getTitle, session.getTitle())
                .set(Session::getDescription, session.getDescription())
                .set(Session::getUpdatedAt, session.getUpdatedAt());
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    /** 对照 Go {@code SetOwnerID}（L327-336）：**不做 user 范围**，只按租户 + id。 */
    public long setOwnerId(long tenantId, String id, String ownerId) {
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt)
                .set(Session::getUserId, ownerId)
                .set(Session::getUpdatedAt, OffsetDateTime.now());
        return mapper.update(null, w);
    }

    /**
     * 对照 Go {@code SetPinned}（L288-310）。
     *
     * <p>取消置顶时 {@code pinned_at} 显式写 **NULL**（Go 的 {@code updates["pinned_at"] = nil}）——
     * 这不是"省略该列"，是清空它。</p>
     */
    public long setPinned(long tenantId, String userId, String id, boolean pinned) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt)
                .set(Session::isPinned, pinned)
                .set(Session::getPinnedAt, pinned ? now : null)
                .set(Session::getUpdatedAt, now);
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    /**
     * 对照 Go {@code UpdateLastRequestState}（L342-363）：只写 agent_config + updated_at，
     * 不扰动 title/description。
     */
    public long updateLastRequestState(long tenantId, String userId, String sessionId,
            SessionLastRequestState state) {
        // 走 mapper 的显式 UPDATE：jsonb 列必须挂类型处理器，UpdateWrapper.set 不生效。
        boolean userScoped = userId != null && !userId.isEmpty();
        return mapper.updateLastRequestState(tenantId, sessionId, state, OffsetDateTime.now(),
                userScoped, userId);
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 对照 Go {@code Get}（L45-58）：租户 + id + user 范围；零行抛 404。 */
    public Session get(long tenantId, String userId, String id) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt);
        applyUserScope(w, userId);
        Session session = mapper.selectOne(w);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /** 对照 Go {@code GetByID}（L61-73）：**不带 user 范围**（管理员越权读的第二跳）。 */
    public Session getById(long tenantId, String id) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .eq(Session::getId, id)
                .isNull(Session::getDeletedAt);
        Session session = mapper.selectOne(w);
        if (session == null) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /**
     * 对照 Go {@code GetIMPlatform}（L78-92）。**查不到不是错误**——Go 的 {@code Pluck}
     * 零行时留空串返回 nil error，所以这里把 null 归一为 ""。
     */
    public String getImPlatform(long tenantId, String sessionId) {
        String platform = mapper.selectImPlatform(tenantId, sessionId);
        return platform == null ? "" : platform;
    }

    /** 对照 Go {@code GetByTenantID}（L95-105）。 */
    public List<Session> getByTenantId(long tenantId, String userId) {
        LambdaQueryWrapper<Session> w = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .orderByDesc(Session::getUpdatedAt);
        applyUserScope(w, userId);
        return mapper.selectList(w);
    }

    /**
     * 对照 Go {@code GetPagedByTenantID}（L108-138）。
     *
     * @param page 从 1 起；Go 的 {@code page.Offset()} 是 {@code (page-1)*pageSize}
     */
    public PagedSessions getPagedByTenantId(long tenantId, String userId, int page, int pageSize) {
        // 归一化逐条对照 Go types.Pagination 的 GetPage / GetPageSize（search.go L279-296）：
        // page < 1 → 1；pageSize < 1 → 20；pageSize > 1000 → 1000。别改成"0 就是不限"。
        int p = page < 1 ? 1 : page;
        int size = pageSize < 1 ? 20 : Math.min(pageSize, 1000);

        LambdaQueryWrapper<Session> countQ = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt);
        applyUserScope(countQ, userId);
        long total = mapper.selectCount(countQ);

        LambdaQueryWrapper<Session> listQ = new LambdaQueryWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .orderByDesc(Session::getUpdatedAt)
                // p/size 是上面归一化过的 int，不来自用户输入的直接拼接
                .last("OFFSET " + ((p - 1) * size) + " ROWS FETCH NEXT " + size + " ROWS ONLY");
        applyUserScope(listQ, userId);
        return new PagedSessions(mapper.selectList(listQ), total);
    }

    // ── 删 ──────────────────────────────────────────────────────────────────

    /**
     * 对照 Go {@code Delete}（L366-372）。
     *
     * <p>Go 的 {@code Delete(&types.Session{})} 在 {@code gorm.DeletedAt} 下是软删，
     * 所以这里也是 UPDATE deleted_at 而不是 DELETE——硬删会连带触发
     * {@code im_channel_sessions_session_id_fkey} 的 ON DELETE CASCADE。</p>
     */
    public long delete(long tenantId, String userId, String id) {
        return softDelete(tenantId, List.of(id), userId);
    }

    /** 对照 Go {@code BatchDelete}（L375-384）：空 ids 直接返回 0（Go 的显式早退）。 */
    public long batchDelete(long tenantId, String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return softDelete(tenantId, ids, userId);
    }

    /** 对照 Go {@code DeleteAllByTenantID}（L387-393）：没有 id 条件。 */
    public long deleteAllByTenantId(long tenantId, String userId) {
        return softDelete(tenantId, null, userId);
    }

    /** 三条删除路径的公共实现；{@code userId} 为空时不加 owner 范围（对照 applySessionUserScope）。 */
    private long softDelete(long tenantId, List<String> ids, String userId) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaUpdateWrapper<Session> w = new LambdaUpdateWrapper<Session>()
                .eq(Session::getTenantId, tenantId)
                .isNull(Session::getDeletedAt)
                .set(Session::getDeletedAt, now);
        if (ids != null) {
            w.in(Session::getId, ids);
        }
        applyUserScope(w, userId);
        return mapper.update(null, w);
    }

    // ── 列表（QueryPaged） ──────────────────────────────────────────────────

    /**
     * 对照 Go {@code QueryPaged}（L142-279）。服务层负责在调用前把 TenantID/UserID 填好
     * （含"渠道来源筛选需要 Admin+，且要丢掉按人裁剪"那段判定）。
     *
     * <p>方言差异只在两处，都由 {@code postgres} 开关切换：{@code ILIKE} 与 {@code NULLS LAST}
     * （H2 两个都不支持）。Go 每次查询都问 {@code db.Dialector.Name()}，同一进程内结果不变，
     * 故与 wiki 一样在构造期探测一次。</p>
     */
    public SessionPage queryPaged(SessionListQuery q) {
        String rawSource = q.source() == null ? "" : q.source().trim();
        String src = rawSource.toLowerCase(Locale.ROOT);

        // 对照 Go：先取原始大小写的 channelID（它切片的是 src 而不是 lower），
        // 再拼成 embed_channel:<channelID> 做**等值**匹配（不是 LIKE）
        String channelDesc = null;
        if (src.startsWith(EMBED_PREFIX)) {
            String channelId = rawSource.substring(EMBED_PREFIX.length()).trim();
            if (!channelId.isEmpty()) {
                channelDesc = Session.EMBED_SESSION_MARKER_PREFIX + channelId;
            }
        }

        String kw = q.keyword() == null ? "" : q.keyword().trim();
        String keywordLike = kw.isEmpty() ? null : "%" + escapeLikeKeyword(kw) + "%";

        // 四个 LIKE 用的前缀串（Go 在 SQL 里现拼，Java 侧拼好传参）
        String skillMarker = Session.SKILL_MAINTENANCE_SESSION_MARKER + "%";
        String embedLike = Session.EMBED_SESSION_MARKER_PREFIX + "%";
        String apiTenantLike = SessionOwnerIds.API_TENANT_KEY_PREFIX + "%";
        String apiExternalLike = SessionOwnerIds.API_EXTERNAL_USER_PREFIX + "%";

        long total = mapper.countPaged(q, postgres, keywordLike, src, channelDesc,
                embedLike, apiTenantLike, apiExternalLike, skillMarker);

        // 归一化对照 Go 本方法自己的 page/size 兜底（与 types.Pagination 同规则）
        int page = q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() < 1 ? 20 : Math.min(q.pageSize(), 1000);

        List<SessionListItem> items = mapper.queryPaged(q, postgres, keywordLike, src, channelDesc,
                embedLike, apiTenantLike, apiExternalLike, skillMarker, size, (page - 1) * size);

        return new SessionPage(items, total, page, size);
    }

    /** 对照 Go {@code wikiDialect()} 的同款探测（见 {@code WikiPageRepository.detectPostgres}）。 */
    private static boolean detectPostgres(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            // 与 wiki 的处置一致：探测失败按非 postgres 走（H2 路径），不阻断启动
            return false;
        }
    }

    /** {@code GetPagedByTenantID} 的返回：一页数据 + 总数。 */
    public record PagedSessions(List<Session> sessions, long total) {
    }

    /** {@code QueryPaged} 的返回（对照 Go {@code PageResult} 的四元组，去掉 Data 的泛型）。 */}
