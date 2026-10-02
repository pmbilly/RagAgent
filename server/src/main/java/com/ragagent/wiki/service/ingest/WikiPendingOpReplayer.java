package com.ragagent.wiki.service.ingest;

import java.util.List;

import com.ragagent.wiki.domain.TaskPendingOp;
import com.ragagent.wiki.mapper.TaskPendingOpsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期重放孤儿 wiki op（B12）。
 *
 * <p><b>问题</b>：ingest op 落在数据库 {@code task_pending_ops} 里，而触发是请求驱动的
 * （上传/重解析等）；进程重启后，<b>队列在进程内、随重启消失</b>，于是那些已排队的 op
 * 再没人触发——文档虽已被启动恢复置为可重试（不会卡「优化中」），但该文的 wiki 内容要等
 * 下一次 KB 触发才补生成。Go 时代靠 Redis/asynq 的重投语义覆盖这一点；单机形态没有那一层。</p>
 *
 * <p><b>做法</b>：{@code ApplicationReadyEvent} 时扫出仍有在途 ingest op 的 KB，逐个走
 * 与请求侧<b>完全相同</b>的 {@link WikiIngestService#enqueueWikiIngestTrigger} 触发
 * （自带 30 秒防抖与 TaskID 合并），批次随后按正常路径处理并结算这些 op。</p>
 *
 * <p><b>仅 Lite</b>：判据用 {@link WikiIngestService#isLiteMode()}（在途限流器是否为进程内实现）
 * ——有 Redis 级协调的部署里，队列自身会重投，重放反而是多余的重复触发。</p>
 *
 * <p>幂等性：op 未被删除前每轮启动都会再触发一次，但触发只是「让批次去看这个 KB」，
 * 批次按 op 行处理、处理后删除行——重复触发只会让后到者看到空队列而空转。</p>
 */
@Component
public class WikiPendingOpReplayer {

    private static final Logger log = LoggerFactory.getLogger(WikiPendingOpReplayer.class);

    private final WikiIngestService ingestService;
    private final TaskPendingOpsRepository pendingRepo;

    public WikiPendingOpReplayer(WikiIngestService ingestService, TaskPendingOpsRepository pendingRepo) {
        this.ingestService = ingestService;
        this.pendingRepo = pendingRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void replayOrphanOps() {
        if (!ingestService.isLiteMode()) {
            log.debug("wiki orphan replay: skipped (distributed coordination in place)");
            return;
        }
        List<String> kbIds;
        try {
            kbIds = pendingRepo.distinctIngestScopeIds(WikiIngestConstants.TASK_TYPE);
        } catch (RuntimeException e) {
            log.warn("wiki orphan replay: list pending scopes failed: {}", e.getMessage());
            return;
        }
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        int replayed = 0;
        for (String kbId : kbIds) {
            if (kbId == null || kbId.isEmpty()) {
                continue;
            }
            try {
                ingestService.enqueueWikiIngestTrigger(tenantOf(kbId), kbId);
                replayed++;
            } catch (RuntimeException e) {
                log.warn("wiki orphan replay: enqueue for KB {} failed: {}", kbId, e.getMessage());
            }
        }
        log.info("wiki orphan replay: re-triggered {} KB(s) holding pending ingest ops", replayed);
    }

    /** 该 KB 在途 op 的租户（取一行即可；查不到时回落 0，与入队侧的缺省一致）。 */
    private long tenantOf(String kbId) {
        try {
            List<TaskPendingOp> rows = pendingRepo.peekBatch(WikiIngestConstants.TASK_TYPE,
                    WikiIngestConstants.TASK_SCOPE, kbId, 1);
            if (!rows.isEmpty() && rows.get(0).getTenantId() != null) {
                return rows.get(0).getTenantId();
            }
        } catch (RuntimeException e) {
            log.debug("wiki orphan replay: tenant lookup failed for KB {}: {}", kbId, e.getMessage());
        }
        return 0L;
    }
}
