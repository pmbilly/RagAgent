package com.ragagent.audit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.audit.domain.AuditAction;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditLogQuery;
import com.ragagent.audit.domain.AuditOutcome;
import com.ragagent.audit.mapper.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务。
 *
 * <p>它是 {@link AuditLogRepository} 的高层包装，负责两件事：</p>
 * <ol>
 *   <li><b>时间戳兜底</b>：{@link #log} 在 CreatedAt 为空时填当前时间，
 *       免得每个调用方都要自己写。</li>
 *   <li><b>1 分钟滑动窗口去重</b>：{@link #logDenied} 用它挡住探测型客户端
 *       把 audit_logs 表刷爆。</li>
 * </ol>
 *
 * <p>服务在消费侧是<b>可空容忍</b>的：装配面用 {@code ObjectProvider.getIfAvailable()} 探测，
 * 见 {@code RbacDeniedAuditorRegistrar} 与 {@code WikiActivityAuditRecorder}。</p>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /**
     * 去重窗口：同一个
     * (tenant, actor, path, action) 元组最多每 1 分钟写一行。
     *
     * <p>1 分钟足够短：跨不同路径的突发请求仍会各自留痕（每路径每分钟一行）；
     * 又足够长：单个被打的端点 100 RPS 只产生 1 行/分钟，而不是 6000。</p>
     */
    public static final Duration DENY_DEDUP_WINDOW = Duration.ofMinutes(1);

    private final AuditLogRepository repo;

    /**
     * 时钟：测试可注入确定性时钟，
     * 在不 sleep 的前提下驱动去重窗口与保留期边界。
     */
    private final Clock clock;

    /** Spring 装配用：系统默认时区时钟。 */
    @Autowired
    public AuditLogService(AuditLogRepository repo) {
        this(repo, Clock.systemDefaultZone());
    }

    /** 测试用：注入可控时钟。 */
    public AuditLogService(AuditLogRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 当前时间（由注入时钟驱动）。 */
    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── 写入 ─────────────────────────────────────────────────────────────

    /**
     * 规范写入路径。
     *
     * <p>仓储的 Create 在 SQL 层有 CreatedAt 默认值，但这里也填一次——
     * 好让测试与"刚刚 Log 完就读 entry.CreatedAt"的调用方无需往数据库跑一趟。</p>
     *
     * <p><b>错误语义</b>：审计失败绝不能拖垮被审计的业务操作。本方法保持抛异常，
     * 需要"尽力而为"的调用点请用 {@link #logBestEffort}。</p>
     *
     * @throws IllegalArgumentException entry 为 null 或 action 为空
     * @throws RuntimeException         仓储写入失败（记日志后原样抛出）
     */
    public void log(AuditLog entry) {
        if (entry == null) {
            throw new IllegalArgumentException("audit log: nil entry");
        }
        if (entry.getAction() == null || entry.getAction().isEmpty()) {
            throw new IllegalArgumentException("audit log: action is required");
        }
        if (entry.getOutcome() == null || entry.getOutcome().isEmpty()) {
            entry.setOutcome(AuditOutcome.SUCCESS);
        }
        if (entry.getCreatedAt() == null) {
            entry.setCreatedAt(now());
        }
        try {
            repo.create(entry);
        } catch (RuntimeException e) {
            // 响亮地记日志，但**是否向上传播由调用方决定**——见方法注释。
            log.error("audit log write failed: action={} target={}",
                    entry.getAction(), entry.getTargetId(), e);
            throw e;
        }
    }

    /**
     * best-effort 语义：审计失败只记日志，绝不影响业务。
     *
     * <p>单独开一个方法让调用方显式选择该语义。</p>
     */
    public void logBestEffort(AuditLog entry) {
        try {
            log(entry);
        } catch (RuntimeException e) {
            log.warn("audit log dropped (best-effort): action={} err={}",
                    entry == null ? null : entry.getAction(), e.getMessage());
        }
    }

    /**
     * 记录一次中间件级拒绝。
     *
     * <p>按 (tenant_id, actor_user_id, action, request_path) 做 1 分钟滑动窗口去重，
     * 免得探测型客户端把表刷爆。</p>
     *
     * <p>非持久化的告警日志行 {@code [rbac] role insufficient: ...} 仍然每次拒绝都打
     * （在 {@code RbacInterceptor} 里）——去重只压制<b>落库</b>，不压制 stderr 可观测性。</p>
     *
     * <p><b>requestPath 用路由模板而非原始 URL</b>：否则攻击者遍历 URL 里的 UUID 会
     * 让每个请求都拿到新的去重键，窗口失效、表被撑爆。原始 URL 保留在 Details 的
     * {@code raw_path} 里供取证用，所以"探测了哪个资源"并没有丢。</p>
     *
     * @param tenantId      调用方活动空间；0 = system 作用域
     * @param actorUserId   调用方用户 ID
     * @param actorRole     调用方角色字符串；未附加角色时为 ""
     * @param requiredRole  被拒绝时要求的最低角色
     *                      （RequireSystemAdmin 路径传字面量 {@code "system_admin"}）
     * @param requestPath   路由<b>模板</b>；
     *                      为空时调用方应回落为原始 URL
     * @param requestMethod HTTP 方法
     * @param rawPath       原始 URL 路径，
     *                      与 requestPath 不同时才写进 Details
     */
    public void logDenied(long tenantId,
                          String actorUserId,
                          String actorRole,
                          String requiredRole,
                          String requestPath,
                          String requestMethod,
                          String rawPath) {
        String actor = actorUserId == null ? "" : actorUserId;
        String path = requestPath == null ? "" : requestPath;
        String raw = rawPath == null ? "" : rawPath;

        // 去重探测：这个元组在窗口内已有行就跳过落库。
        // 探测失败是**非致命**的——降级行为是"多写一行重复"，好过"因为 count 失败而漏审计"。
        OffsetDateTime since = now().minus(DENY_DEDUP_WINDOW);
        boolean alreadyRecorded = false;
        try {
            alreadyRecorded = repo.countSinceForDedup(
                    tenantId, actor, AuditAction.ACCESS_DENIED, path, since) > 0;
        } catch (RuntimeException e) {
            // 探测失败不阻断：继续照写。
            log.warn("audit dedup probe failed, writing anyway: tenant={} path={} err={}",
                    tenantId, path, e.getMessage());
        }
        if (alreadyRecorded) {
            return;
        }

        // details 键按**字母序**输出
        // → raw_path（r-a）在 required_role（r-e）之前。PG jsonb 落库后还会按
        // （长度, 字节序）再规范化一次，Java 读取路径的 PgJsonTypeHandler 做同样的事，两端一致。
        ObjectNode details = AUDIT_DETAILS_MAPPER.createObjectNode();
        if (!raw.isEmpty() && !raw.equals(path)) {
            details.put("raw_path", raw);
        }
        details.put("required_role", requiredRole == null ? "" : requiredRole);

        log(newAuditLog(tenantId, actor, actorRole, AuditAction.ACCESS_DENIED,
                path, requestMethod, AuditOutcome.DENIED, details));
    }

    /** {@link #logDenied} 的便捷重载：路由模板缺省等于原始路径。 */
    public void logDenied(long tenantId, String actorUserId, String actorRole,
                          String requiredRole, String requestPath, String requestMethod) {
        logDenied(tenantId, actorUserId, actorRole, requiredRole,
                requestPath, requestMethod, requestPath);
    }

    // ── 读取 ─────────────────────────────────────────────────────────────

    /**
     * 代理到仓储。
     *
     * <p>handler 在到达这里之前已经做了 PathTenantMatch + Admin 守卫，
     * 所以这里<b>不</b>重复校验租户作用域。</p>
     */
    public List<AuditLog> list(long tenantId, AuditLogQuery q) {
        return repo.list(tenantId, q);
    }

    // ── 保留期 ───────────────────────────────────────────────────────────

    /**
     * 删除 created_at 严格早于
     * {@code retentionDays} 天前的行。retentionDays &lt;= 0 直接短路——
     * 没配保留期的运维方零数据库往返。
     *
     * <p>cutoff 用服务的时钟算（{@link #now}），测试才能在不碰墙上时间的前提下
     * 驱动确定性边界。</p>
     *
     * <p>刻意<b>不</b>分批 DELETE：审计表在 24h 窗口里现实能达到的体量下，
     * 单条带索引的 DELETE 在 Postgres 上远不到一秒。真到了单次清扫会阻塞 vacuum 的
     * 体量，该加 LIMIT 式分块的地方是仓储辅助方法——服务保持简单。</p>
     *
     * @return 删除行数；retentionDays &lt;= 0 时为 0
     */
    public long purge(int retentionDays) {
        if (retentionDays <= 0) {
            return 0L;
        }
        // Duration.ofDays 是精确 86400s，不受夏令时影响。
        OffsetDateTime cutoff = now().minus(Duration.ofDays(retentionDays));
        return repo.deleteOlderThan(cutoff);
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────

    /** 纯 JDK 映射器（只用来造 details 的 ObjectNode，不读配置）。 */
    private static final ObjectMapper AUDIT_DETAILS_MAPPER = new ObjectMapper();

    /** 组装一条审计行的样板。 */
    static AuditLog newAuditLog(long tenantId, String actorUserId, String actorRole,
                                String action, String requestPath, String requestMethod,
                                String outcome, ObjectNode details) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(tenantId);
        entry.setActorUserId(actorUserId == null ? "" : actorUserId);
        entry.setActorRole(actorRole == null ? "" : actorRole);
        entry.setAction(action);
        entry.setRequestPath(requestPath == null ? "" : requestPath);
        entry.setRequestMethod(requestMethod == null ? "" : requestMethod);
        entry.setOutcome(outcome);
        entry.setDetails(details);
        return entry;
    }
}
