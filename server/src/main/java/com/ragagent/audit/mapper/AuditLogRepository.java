package com.ragagent.audit.mapper;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.audit.domain.AuditLog;
import com.ragagent.audit.domain.AuditLogQuery;
import com.ragagent.audit.domain.AuditOutcome;
import org.springframework.stereotype.Component;

/**
 * audit_logs 仓储（对照 Go internal/application/repository/audit_log.go 整个文件）。
 *
 * <p>行是<b>只追加</b>的：没有 Update、没有软删、没有 Modify 钩子。
 * 游标分页用单调的 id 列而非 created_at——重复时间戳不会打断翻页。</p>
 *
 * <h2>GORM 隐式行为清单（约定 §3）</h2>
 * <ol>
 *   <li><b>钩子</b>：无。CreatedAt 由 service 层填（GORM 侧同样如此）。</li>
 *   <li><b>预加载</b>：无关联、无 Preload、无 N+1。</li>
 *   <li><b>软删除</b>：无 deleted_at 列 → 查询里<b>不</b>出现 {@code deleted_at IS NULL}。</li>
 *   <li><b>默认排序</b>：List 恒 {@code ORDER BY id DESC}（Go L83 的 {@code Order("id DESC")}）。</li>
 *   <li><b>唯一索引 / 外键</b>：均无。</li>
 *   <li><b>自动时间戳</b>：无（本表没有 updated_at）。</li>
 *   <li><b>LIMIT</b>：默认 50、硬上限 100（见 {@link #DEFAULT_LIMIT} / {@link #MAX_LIMIT}），
 *       Go 用 {@code .Limit(limit)}，Java 用 {@code .last("LIMIT n")}——n 已先夹到安全区间。</li>
 * </ol>
 */
@Component
public class AuditLogRepository {

    /** 对照 Go {@code limit := 50}（Go L48）。 */
    public static final int DEFAULT_LIMIT = 50;
    /** 对照 Go {@code auditLogListLimitMax = 100}（Go L37）。 */
    public static final int MAX_LIMIT = 100;

    private final AuditLogMapper mapper;

    public AuditLogRepository(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 对照 Go {@code Create}（L30-32）：单行插入。
     *
     * <p>校验很轻——schema 层只有 action 必填；CreatedAt 为 null 时由 DB 默认填。
     * service 层的 {@code Log()} 已在调用前把两者都填好，所以这里基本是 pass-through。</p>
     *
     * <p>跟 GORM 一样：{@code id} 为 null 由库自增生成；{@code details} 为 null 时
     * MyBatis-Plus 会从 INSERT 列清单省略它，由列默认 {@code '{}'} 补上。</p>
     *
     * <p><b>outcome 归一</b>：Go 的 {@code outcome} 是非指针 string，零值 "" 会被 GORM
     * 从 INSERT 省略 → 落列默认 {@code 'success'}。Java 的 {@code ""} 不是 null，
     * MyBatis-Plus 会真的写 {@code ''} —— 那样读回就与 Go 不一致。所以这里显式归一，
     * 让"未设置 outcome"在两种实现下得到同一个值。service 层的 {@code Log} 也会做
     * 同样的归一（对照 Go 服务层的 {@code if entry.Outcome == ""}），两处是幂等的双保险。</p>
     */
    public void create(AuditLog entry) {
        if (entry.getOutcome().isEmpty()) {
            entry.setOutcome(AuditOutcome.SUCCESS);
        }
        mapper.insert(entry);
    }

    /**
     * 对照 Go {@code List}（L43-87）：按租户返回最新在前的审计行，
     * 应用 {@link AuditLogQuery} 的可选游标与过滤器。
     *
     * <p>Action 与 Outcome 都是<b>精确匹配</b>——过滤集合还小，暂不需要子串匹配。</p>
     *
     * <p>注意 {@code UnscopedOnly} 与显式 {@code ScopeType} 在 Go 里是两条独立的
     * {@code Where} 叠加（互相矛盾时结果为 0 行）；这里保持同样的叠加语义。</p>
     */
    public List<AuditLog> list(long tenantId, AuditLogQuery q) {
        int limit = DEFAULT_LIMIT;
        if (q != null && q.limit() > 0) {
            limit = q.limit();
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }

        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId);
        if (q != null) {
            if (q.afterId() > 0) {
                w.lt(AuditLog::getId, q.afterId());
            }
            if (q.hasAction()) {
                w.eq(AuditLog::getAction, q.action());
            }
            if (q.hasOutcome()) {
                w.eq(AuditLog::getOutcome, q.outcome());
            }
            if (q.hasActorUserId()) {
                w.eq(AuditLog::getActorUserId, q.actorUserId());
            }
            if (q.hasScopeType()) {
                w.eq(AuditLog::getScopeType, q.scopeType());
            }
            if (q.hasScopeId()) {
                w.eq(AuditLog::getScopeId, q.scopeId());
            }
            if (q.unscopedOnly()) {
                w.eq(AuditLog::getScopeType, "");
            }
        }
        w.orderByDesc(AuditLog::getId).last("LIMIT " + limit);

        List<AuditLog> rows = mapper.selectList(w);
        // Go 的 nil slice 语义：无行时 GORM 留 nil。仓储对外统一返回空列表，
        // 与项目其它仓储一致（响应体的 null vs [] 由控制器决定，见 AuditLogController）。
        return rows == null ? Collections.emptyList() : rows;
    }

    /**
     * 对照 Go {@code CountSinceForDedup}（L97-115）：LogDenied 滑动窗口去重的限流原语。
     *
     * <p>{@code (tenant_id, action)} 索引让这个 count 很便宜；其余过滤条件是在
     * 索引结果集上追加的 WHERE。</p>
     *
     * <p>Go 的注释提到"首个命中就返回会更便宜，但 gorm 的 Count 用同一个计划，
     * 常数开销相对去重收益可忽略"——Java 侧同为 {@code selectCount}。</p>
     */
    public long countSinceForDedup(long tenantId,
                                   String actorUserId,
                                   String action,
                                   String requestPath,
                                   OffsetDateTime since) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .eq(AuditLog::getActorUserId, actorUserId == null ? "" : actorUserId)
                .eq(AuditLog::getAction, action)
                .eq(AuditLog::getRequestPath, requestPath == null ? "" : requestPath)
                .ge(AuditLog::getCreatedAt, since));
        return n == null ? 0L : n;
    }

    /**
     * 对照 Go {@code DeleteOlderThan}（L129-137）：单条 DELETE 清理严格早于 cutoff 的行。
     *
     * <p>租户维度<b>刻意不</b>在这个签名里：保留期是全局运维策略，不是每个租户的选择。
     * 将来若需要按租户保留，应新增 {@code DeleteOlderThanForTenant} 而不是重载这个原语。</p>
     *
     * <p>返回受影响行数，调用方（每日清扫）据此打 INFO 日志。
     * 错误原样向上抛——由调用方决定是终止还是重试。</p>
     */
    public long deleteOlderThan(OffsetDateTime cutoff) {
        return mapper.delete(new LambdaQueryWrapper<AuditLog>()
                .lt(AuditLog::getCreatedAt, cutoff));
    }
}
