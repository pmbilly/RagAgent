package com.ragagent.wiki.mapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragagent.wiki.domain.TaskPendingOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用待办队列的持久化仓储（对照 Go
 * internal/application/repository/task_queue.go 的 {@code taskPendingOpsRepository}，
 * 接口见 internal/types/interfaces/task_queue.go）。
 *
 * <p>本仓储对消费语义<b>保持无知</b>：{@code (TaskType, Scope, ScopeID)} 三元组是它
 * 唯一理解的路由原语；去重、批处理与重试策略都住在消费方
 * （{@link com.ragagent.wiki.service.WikiIngestService}）。</p>
 *
 * <h2>两种消费原语的并存（与 Go 一致）</h2>
 * <ul>
 *   <li>{@link #peekBatch} <b>不</b>加行锁：消费方在带外保证按 scope 串行
 *       （例如外部 Redis 锁）。这是最初的原语，单进程（Lite）模式仍在用。</li>
 *   <li>{@link #claimBatch} <b>会</b>标记 {@code claimed_at}，让同一元组的多个并发
 *       消费者拉到<b>互不相交</b>的行、无需外部锁。wiki 管线借此丢掉按 KB 的独占
 *       批次锁，把一个 KB 的积压摊到整个 worker 池上。</li>
 * </ul>
 *
 * <h2>⚠️ Java 侧对 {@code SELECT ... FOR UPDATE SKIP LOCKED} 的替代（需决策点）</h2>
 * <p>Go 的 PG 实现在 {@code ClaimBatch} 里用 {@code FOR UPDATE SKIP LOCKED} 锁住每个
 * dedup_key 的锚行，让并发认领者"跳过被锁的行"从而拿到不相交的 key 集合。
 * <b>H2 不支持 {@code SKIP LOCKED}</b>，同一条 SQL 无法在测试库与生产库上通吃，
 * 因此 Java 侧改成：</p>
 * <ol>
 *   <li>先查出该元组下的<b>可认领行</b>（未认领，或认领已陈旧）；</li>
 *   <li>查出该元组下<b>新鲜认领</b>的 dedup_key 集合，把它们整体排除
 *       ——这正是 Go「新鲜认领阻塞它整个 dedup_key」的语义，保证同一文档的多个 op
 *       不会被拆到两个并发批次；</li>
 *   <li>按 dedup_key 分组、取前 limit 个 key；</li>
 *   <li>逐个 key 做<b>条件 UPDATE</b>（{@code claimed_at IS NULL OR claimed_at < staleBefore}）：
 *       只有受影响行数等于该 key 的候选行数时才<b>整个 key 认领成功</b>，
 *       否则整个 key 放弃。</li>
 * </ol>
 * <p><b>残余差异</b>：并发认领者在第 4 步可能发生"部分行被对方抢先"的极小窗口
 * （READ COMMITTED 下 UPDATE 重估 WHERE 导致），此时该 key 被放弃，其中已被本事务
 * 盖戳的行会停留在"已认领但未消费"态，直到 {@code claimStaleAfter}（90 分钟）后被
 * 下一个认领者回收——这与 Go 对"worker 崩溃留下认领"的恢复路径是同一条，
 * 因此不会永久丢行，只是回收变慢。要彻底消除它需要 PG 专属语法，故按可移植性优先。</p>
 *
 * <p>多实例部署下本实现<b>天然跨实例安全</b>（靠数据库行级条件更新），
 * 与 Go 的 Redis/claim 模式行为一致——这一点比 wiki 的进程内 slug 锁要好。</p>
 */
@Repository
public class TaskPendingOpsRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskPendingOpsRepository.class);

    /**
     * 认领时单次扫描的候选行上限（对照 Go 无此限制的取舍）。
     *
     * <p>Go 用 {@code LIMIT} 直接限制在 SQL 层，但它的"按 key 分组"是在 SQL 里做的；
     * Java 侧为了可移植性必须在内存里分组，因此加一个宽松的扫描上限防止病态元组
     * 一次拉空整表。取值远大于任何真实批次的候选量（默认每批 5 篇文档）。</p>
     */
    static final int CLAIM_SCAN_CAP = 2000;

    private final TaskPendingOpMapper mapper;

    public TaskPendingOpsRepository(TaskPendingOpMapper mapper) {
        this.mapper = mapper;
    }

    // ═══════════════════════════════════════════════════════════════
    // 写
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code Enqueue}：插入单条 op。调用方填 TenantID / TaskType / Scope /
     * ScopeID / Op / DedupKey / Payload；ID、FailCount、EnqueuedAt 由服务端默认值负责。
     */
    @Transactional
    public void enqueue(TaskPendingOp op) {
        if (op.getFailCount() == null) {
            op.setFailCount(0);
        }
        if (op.getDedupKey() == null) {
            op.setDedupKey("");
        }
        mapper.insert(op);
    }

    /**
     * 对照 Go {@code DeleteByIDs}：删除指定行。空入参是 no-op
     * （消费方因此在批次结束时可以无条件调用）。
     */
    @Transactional
    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        mapper.deleteByIds(ids);
    }

    /**
     * 对照 Go {@code ReleaseByIDs}：清掉给定行的 {@code claimed_at}，让"已认领但未消费"
     * 的行立刻可被下一次 {@code ClaimBatch} 认领，而不必等认领变陈旧。
     * 空入参 no-op；对从未被认领的行调用是无害的（Lite 模式即如此）。
     */
    @Transactional
    public void releaseByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        mapper.releaseByIds(ids);
    }

    /**
     * 对照 Go {@code IncrFailCount}：fail_count 加一并返回新值。
     * 行不存在时返回 0（与 DeleteByIDs 的竞态是良性的）。
     */
    @Transactional
    public int incrFailCount(long id) {
        int updated = mapper.incrementFailCount(id);
        if (updated == 0) {
            return 0;
        }
        TaskPendingOp row = mapper.selectById(id);
        return row == null || row.getFailCount() == null ? 0 : row.getFailCount();
    }

    /**
     * 对照 Go {@code DeleteByDedupKey}：删除该元组下 DedupKey 匹配的行。
     * {@code op} 非空时只删该精确 op 的行（这让 wiki ingest 能清掉排队的 {@code ingest}
     * op 而保留同一 knowledge 的 {@code retract} op——文档删除后撤回仍需清理 wiki 页）。
     * {@code op} 为空则不分 op 全删。
     */
    @Transactional
    public void deleteByDedupKey(String taskType, String scope, String scopeId,
                                 String dedupKey, String op) {
        LambdaQueryWrapper<TaskPendingOp> q = new LambdaQueryWrapper<TaskPendingOp>()
                .eq(TaskPendingOp::getTaskType, taskType)
                .eq(TaskPendingOp::getScope, scope)
                .eq(TaskPendingOp::getScopeId, scopeId)
                .eq(TaskPendingOp::getDedupKey, dedupKey);
        if (op != null && !op.isEmpty()) {
            q.eq(TaskPendingOp::getOp, op);
        }
        mapper.delete(q);
    }

    /**
     * 对照 Go {@code TaskPendingOpsScopeCleaner.DeleteByScope}：丢弃一个已删除 scope
     * 名下的<b>全部</b>待办操作。
     */
    @Transactional
    public void deleteByScope(String scope, String scopeId) {
        if (scopeId == null || scopeId.isEmpty()) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<TaskPendingOp>()
                .eq(TaskPendingOp::getScope, scope)
                .eq(TaskPendingOp::getScopeId, scopeId));
    }

    // ═══════════════════════════════════════════════════════════════
    // 读
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对照 Go {@code PeekBatch}：返回该队列元组下最多 {@code limit} 行，
     * 按 id ASC（队列内 FIFO）。<b>行不会被移除</b>——消费方处理完必须
     * {@code DeleteByIDs}（或 {@code IncrFailCount} 后留着给下一轮）。
     */
    public List<TaskPendingOp> peekBatch(String taskType, String scope, String scopeId, int limit) {
        if (limit <= 0) {
            // Go 的 PG 实现里 LIMIT n 在 n<=0 时是"无限制"；Java 侧显式挡掉，
            // 避免一次拉全表。wiki 的调用点总是传 >0 的值。
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<TaskPendingOp>()
                .eq(TaskPendingOp::getTaskType, taskType)
                .eq(TaskPendingOp::getScope, scope)
                .eq(TaskPendingOp::getScopeId, scopeId)
                .orderByAsc(TaskPendingOp::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 对照 Go {@code ClaimBatch}：原子地认领最多 {@code limit} 个 <b>dedup_key</b>
     * （文档，而非行）对应的行，并给它们盖上 {@code claimed_at = now}。
     *
     * <p><b>limit 计的是去重后的 key 数</b>：被选中的 key 名下<b>全部</b>可认领行会
     * 一起被认领，因此同一个文档排了多个 op 时绝不会被拆到两个并发批次里。</p>
     *
     * <p>行可认领的条件是「未认领」或「认领已陈旧」（早于 {@code staleBefore}）
     * ——后者回收崩溃 worker 遗留的行。实现细节与残余差异见类注释。</p>
     *
     * @param staleBefore 早于该时刻的认领视为陈旧
     * @return 认领成功的行（按 id ASC）；一个都没抢到时返回空列表
     */
    @Transactional
    public List<TaskPendingOp> claimBatch(String taskType, String scope, String scopeId,
                                          int limit, OffsetDateTime staleBefore) {
        if (limit <= 0) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now();

        // (1) 该元组下的可认领行（未认领，或认领已陈旧），按 id ASC = FIFO。
        List<TaskPendingOp> eligible = mapper.selectList(new LambdaQueryWrapper<TaskPendingOp>()
                .eq(TaskPendingOp::getTaskType, taskType)
                .eq(TaskPendingOp::getScope, scope)
                .eq(TaskPendingOp::getScopeId, scopeId)
                .and(w -> w.isNull(TaskPendingOp::getClaimedAt)
                        .or().lt(TaskPendingOp::getClaimedAt, staleBefore))
                .orderByAsc(TaskPendingOp::getId)
                .last("LIMIT " + CLAIM_SCAN_CAP));
        if (eligible.isEmpty()) {
            return List.of();
        }

        // (2) 新鲜认领的 dedup_key 整体阻塞——对照 Go「a fresh claim blocks its
        //     whole dedup_key so same-document ops never split across concurrent
        //     batches」。只取 dedup_key 一列，且这些行数 == 在飞批次 × 批大小，很小。
        Set<String> blockedKeys = new LinkedHashSet<>();
        List<TaskPendingOp> freshClaimed = mapper.selectList(
                new LambdaQueryWrapper<TaskPendingOp>()
                        .select(TaskPendingOp::getDedupKey)
                        .eq(TaskPendingOp::getTaskType, taskType)
                        .eq(TaskPendingOp::getScope, scope)
                        .eq(TaskPendingOp::getScopeId, scopeId)
                        .isNotNull(TaskPendingOp::getClaimedAt)
                        .ge(TaskPendingOp::getClaimedAt, staleBefore)
                        .last("LIMIT " + CLAIM_SCAN_CAP));
        for (TaskPendingOp row : freshClaimed) {
            if (row.getDedupKey() != null && !row.getDedupKey().isEmpty()) {
                blockedKeys.add(row.getDedupKey());
            }
        }

        // (3) 按 dedup_key 分组（保持 id ASC 的首次出现顺序），跳过被阻塞的 key，
        //     取前 limit 个。空 dedup_key 的行各自独立成组（对照 Go 的锚行语义：
        //     空 key 无人可与之"同组"，因此单独成一个候选）。
        Map<String, List<TaskPendingOp>> byKey = new LinkedHashMap<>();
        for (TaskPendingOp row : eligible) {
            String key = row.getDedupKey();
            if (key == null || key.isEmpty()) {
                key = " row " + row.getId();
            } else if (blockedKeys.contains(key)) {
                continue;
            }
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // (4) 逐 key 条件认领：整组抢到才采纳，否则整组放弃（见类注释的残余差异）。
        List<TaskPendingOp> claimedRows = new ArrayList<>();
        int taken = 0;
        for (Map.Entry<String, List<TaskPendingOp>> entry : byKey.entrySet()) {
            if (taken >= limit) {
                break;
            }
            List<TaskPendingOp> group = entry.getValue();
            List<Long> ids = new ArrayList<>(group.size());
            for (TaskPendingOp row : group) {
                ids.add(row.getId());
            }
            int won = mapper.claimByIds(ids, now, staleBefore);
            if (won != ids.size()) {
                // 该 key 被并发认领者抢走（或部分抢走）——整组放弃，
                // 未抢到的部分留给对方，抢到的部分由 stale 阈值兜底回收。
                log.debug("wiki claim: key {} lost the race ({}/{} rows), skipping",
                        entry.getKey(), won, ids.size());
                continue;
            }
            taken++;
            for (TaskPendingOp row : group) {
                row.setClaimedAt(now);
                claimedRows.add(row);
            }
        }
        return claimedRows;
    }

    /**
     * 对照 Go {@code PendingCount}：该元组当前排队的行数。
     * 走 {@code idx_task_pending_ops_scope}，很便宜；wiki ingest 的后续调度用它。
     */
    public long pendingCount(String taskType, String scope, String scopeId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<TaskPendingOp>()
                .eq(TaskPendingOp::getTaskType, taskType)
                .eq(TaskPendingOp::getScope, scope)
                .eq(TaskPendingOp::getScopeId, scopeId));
        return count == null ? 0L : count;
    }

    /**
     * 对照 Go 的按主键取行（{@code DeleteByIDs} / {@code IncrFailCount} 之外的诊断用途）。
     */
    public TaskPendingOp findById(long id) {
        return mapper.selectById(id);
    }
}
