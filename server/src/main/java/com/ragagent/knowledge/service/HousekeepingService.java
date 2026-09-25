package com.ragagent.knowledge.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ragagent.agent.tools.SandboxExecuteResult;
import com.ragagent.knowledge.domain.Knowledge;

import jakarta.annotation.PreDestroy;

/**
 * 知识管家清扫——对照 Go {@code service/knowledge_housekeeping.go}（399 行，2026-09-25
 * follow-up 落地）。
 *
 * <p>周期扫描"卡在处理态"的知识行并判死为 failed。这是最后一道兜底网——其它防线
 * （worker 重试预算、多模态收口回调）漏掉的情形由它接住：</p>
 * <ul>
 *   <li>worker 进程在 handler 中途被杀，defer 还没跑到；</li>
 *   <li>DocReader 调用真的超过上限，而重试还没轮到；</li>
 *   <li>多模态计数器置为 N 但 N 个图片任务全部以绕过收口的方式失败。</li>
 * </ul>
 * <p>没有这道网，一次不幸的失败就能让一行永久停在 processing——用户侧只看到一个
 * 永远转的圈。有了它，"停滞 → 用户可见的失败"的最坏时延被界定为约
 * 「1 个 stale 阈值 + 1 个清扫周期」。</p>
 *
 * <h2>清扫 A：知识行（pending / processing / finalizing）</h2>
 * <p><b>两级判定是核心</b>：{@code knowledges.updated_at} 只在 parse_status 迁移时推进，
 * 而一个长阶段（500MB PDF 的 DocReader、5K 分块的嵌入）可以跑一小时且状态不变——
 * 只看 updated_at 会误杀正在跑的活。因此与 {@code knowledge_processing_spans} 的
 * 最新心跳做<b>或</b>组合：SpanTracker 的每次 Begin/End/Fail/Skip 都会推进 span 行，
 * 所以真在推进的流水线即使父行"冻结"也总有新鲜心跳。</p>
 * <p>完全没有 span 的知识行（Lite 模式、本机制上线前的历史任务）回落到单纯的
 * updated_at 判定——它们没有心跳可查。</p>
 *
 * <h2>第二道闸：队列仍有活则不算孤儿</h2>
 * <p>一行可能心跳过期但完全健康——当它的富化子任务（摘要/问题/图谱/wiki）只是排在
 * 忙碌队列后面时，没有 worker 取到它们，因此自 post-process 扇出以来就没有 span
 * 写入。杀掉这种行正是重上传压力下用户遇到的误报。两道探测：</p>
 * <ol>
 *   <li><b>持久表</b>（{@code task_pending_ops}，wiki ingest 按知识 id 去重）——立即判定，
 *       且<b>永远生效</b>（不依赖 inspector）；探测出错时<b>推迟本轮全部候选</b>——
 *       分不清积压与孤儿时，误杀一个活文档用户无法恢复，而多等一个周期可以；</li>
 *   <li><b>瞬时队列</b>（Go 的 asynq inspector）——本仓 Lite 无 asynq，注入为
 *       {@code null} 时<b>只关闭这一项</b>（照 Go 的 nil-safe 设计）；探测出错时按
 *       "仍是卡死"处理（与 span 心跳查询的失败方向一致）。</li>
 * </ol>
 *
 * <h2>清扫 B：摘要状态</h2>
 * <p>摘要是解析后阶段，阈值更短（单个 LLM 调用即可界定），且没有 span 心跳可查
 * （它在下游任务里）。沿用最初的简单判定：{@code summary_status=processing} 且
 * updated_at 早于 1 小时 → failed。</p>
 *
 * <h2>装配与开关</h2>
 * <p>启动即注册 5 分钟周期（先睡一个周期再扫，与 Go 的"启动后等到下一个整 5 分边界
 * 才首次触发"同形）；{@code WEKNORA_HOUSEKEEPING_ENABLED}
 * <b>缺省开启</b>，运维必须显式设 {@code false}/{@code 0}/{@code off}/{@code no} 才关停。</p>
 *
 * <p><b>与 Go 的差异（备案）</b>：① Go 用 robfig/cron，本仓用守护虚拟线程 + sleep
 * （同 {@code TemporaryDocumentService} 的 10 分钟 ticker 约定）；② span 心跳过滤由
 * "取 MAX(updated_at) 再在客户端解析字符串"改为<b>存在性判定</b>（Go 的字符串解析是为
 * 绕开 SQLite 对聚合返回值不做类型转换的问题；本仓改 {@code EXISTS(updated_at > cutoff)}，
 * 语义等价且免聚合类型问题）；③ Go 的 {@code TaskInspector} 在本仓无实现（Lite 无 asynq，
 * 见 {@code SystemAdminController} 的 noopTaskInspector 对照），本类保留同名能力的注入缝，
 * 生产装配传 {@code null}。</p>
 */
@Component
public class HousekeepingService {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingService.class);

    /** 对照 Go 的 cron 表达式（每 5 分钟的整分触发，Go 侧为 {@code cron.WithSeconds()} 语法）。 */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    /** 对照 Go 清扫 B 的 {@code summaryCutoff := time.Now().Add(-1 * time.Hour)}。 */
    static final Duration SUMMARY_STALE_THRESHOLD = Duration.ofHours(1);

    /** 对照 staleThreshold 的下限 {@code base := 1 * time.Hour}。 */
    static final Duration STALE_FLOOR = Duration.ofHours(1);

    /** 对照 staleThreshold 的缓冲 {@code base + 10*time.Minute}。 */
    static final Duration STALE_BUFFER = Duration.ofMinutes(10);

    /** 对照 Go {@code config.DefaultDocumentProcessTimeout = 2 * time.Hour}。 */
    static final Duration DEFAULT_DOCUMENT_PROCESS_TIMEOUT = Duration.ofHours(2);

    static final String ENABLED_ENV = "WEKNORA_HOUSEKEEPING_ENABLED";
    static final String DOCUMENT_PROCESS_TIMEOUT_ENV = "WEKNORA_DOCUMENT_PROCESS_TIMEOUT";

    /** 对照 Go 的 {@code []string{ParseStatusPending, ParseStatusProcessing, ParseStatusFinalizing}}。 */
    static final List<String> CANDIDATE_STATUSES = List.of(
            Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING, Knowledge.PARSE_FINALIZING);

    /** 对照 Go 的 {@code types.SummaryStatusProcessing} / {@code SummaryStatusFailed}。 */
    static final String SUMMARY_STATUS_PROCESSING = "processing";
    static final String SUMMARY_STATUS_FAILED = "failed";

    /** 对照 Go 的 wiki 持久 op 三元组（task_type / scope / op）。 */
    static final String WIKI_TASK_TYPE = "wiki:ingest";
    static final String WIKI_TASK_SCOPE = "knowledge_base";
    static final String WIKI_OP_INGEST = "ingest";

    private final JdbcTemplate jdbc;
    private final KnowledgeQueueInspector inspector;
    private final Duration documentProcessTimeout;
    private final boolean enabled;

    private final Object lock = new Object();
    private volatile boolean started;
    private volatile boolean stopping;

    /**
     * 瞬时队列探测缝——对照 Go 的 {@code interfaces.TaskInspector.HasQueuedTasksForKnowledge}。
     * <b>Lite 无 asynq ⇒ 生产装配传 {@code null}</b>（只关闭这一项检查，持久表那道闸不受影响）。
     * 探测失败以 {@link RuntimeException} 表达（Go 的 error 返回值）。
     */
    public interface KnowledgeQueueInspector {
        boolean hasQueuedTasksForKnowledge(String knowledgeId);
    }

    @Autowired
    public HousekeepingService(JdbcTemplate jdbc) {
        this(jdbc, null, documentProcessTimeoutFromEnv(), housekeepingEnabledFromEnv());
    }

    /** 测试口：显式给阈值与开关（对照 Go 测试注入 {@code cfg}）/注入 inspector。 */
    HousekeepingService(JdbcTemplate jdbc, KnowledgeQueueInspector inspector,
                        Duration documentProcessTimeout, boolean enabled) {
        this.jdbc = jdbc;
        this.inspector = inspector;
        this.documentProcessTimeout = documentProcessTimeout;
        this.enabled = enabled;
    }

    /**
     * 注册 5 分钟周期并启动后台循环。幂等——重复调用是 no-op（装配方无需协调顺序）。
     * 对照 Go {@code Start}：关停开关命中时只记日志、不启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        synchronized (lock) {
            if (started) {
                return;
            }
            if (!enabled) {
                log.info("[Housekeeping] disabled via {}=false", ENABLED_ENV);
                return;
            }
            started = true;
            stopping = false;
        }
        Thread.ofVirtual().name("knowledge-housekeeping").start(this::sweepLoop);
        log.info("[Housekeeping] started with 5-minute sweep");
    }

    /** 对照 Go {@code Stop}：停止周期循环（当前这轮清扫跑完即退出）。 */
    @PreDestroy
    public void stop() {
        synchronized (lock) {
            if (!started) {
                return;
            }
            stopping = true;
            started = false;
        }
    }

    private void sweepLoop() {
        while (!stopping) {
            try {
                Thread.sleep(SWEEP_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopping) {
                return;
            }
            try {
                runSweep();
            } catch (RuntimeException e) {
                // 对照 Go cron.WithChain(cron.Recover(...))：单轮异常不拆掉定时器
                log.warn("[Housekeeping] sweep failed: {}", e.getMessage());
            }
        }
    }

    /** 单轮清扫——对照 Go {@code runSweep}（导出以便测试直驱一轮而不等周期）。 */
    public void runSweep() {
        Duration threshold = staleThreshold();
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(threshold);

        List<String> candidates = listStuckKnowledgeCandidates(cutoff);
        List<String> stuck = filterByLastSpanActivity(candidates, cutoff);
        int spanSkipped = candidates.size() - stuck.size();
        int[] queueSkipped = new int[1];
        stuck = filterOutQueued(stuck, queueSkipped);

        if (!stuck.isEmpty()) {
            recoverStuckKnowledge(stuck, threshold);
        }
        if (spanSkipped > 0) {
            log.info("[Housekeeping] {} candidate(s) skipped — span heartbeat within threshold",
                    spanSkipped);
        }
        if (queueSkipped[0] > 0) {
            log.info("[Housekeeping] {} candidate(s) skipped — tasks still queued "
                    + "(backpressure, not stuck)", queueSkipped[0]);
        }

        sweepSummary();
    }

    /**
     * 把判定为卡死的行推到 failed。对照 Go 的
     * {@code Updates({parse_status: failed, error_message: ..., pending_subtasks_count: 0})}，
     * WHERE 同时带 id 集与可复位状态集（Go 的 {@code id IN ? AND parse_status IN ?}）。
     */
    private void recoverStuckKnowledge(List<String> stuck, Duration threshold) {
        String placeholders = String.join(",", Collections.nCopies(stuck.size(), "?"));
        List<Object> args = new ArrayList<>(stuck.size() + 6);
        args.add(Knowledge.PARSE_FAILED);
        args.add("task stuck in processing > " + goDuration(threshold)
                + ", recovered by housekeeping");
        args.add(0);
        args.addAll(stuck);
        args.addAll(CANDIDATE_STATUSES);
        try {
            int rows = jdbc.update("UPDATE knowledges SET parse_status = ?, error_message = ?, "
                            + "pending_subtasks_count = ? WHERE id IN (" + placeholders + ") "
                            + "AND parse_status IN (?, ?, ?) AND deleted_at IS NULL",
                    args.toArray());
            if (rows > 0) {
                log.info("[Housekeeping] recovered {} stuck knowledge rows (threshold={})",
                        rows, goDuration(threshold));
            }
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] knowledge sweep update failed: {}", e.getMessage());
        }
    }

    /**
     * 清扫 A 的候选集：可复位状态 + 行 updated_at 已过 cutoff。
     * 对照 Go 的 {@code parse_status IN ? AND updated_at < ?}（GORM 软删条件显式写出）。
     * 查询失败只记警告并<b>放弃本轮</b>（照 Go 的 {@code return}）。
     */
    private List<String> listStuckKnowledgeCandidates(OffsetDateTime cutoff) {
        try {
            return jdbc.queryForList("SELECT id FROM knowledges WHERE parse_status IN (?, ?, ?) "
                            + "AND updated_at < ? AND deleted_at IS NULL",
                    String.class,
                    Knowledge.PARSE_PENDING, Knowledge.PARSE_PROCESSING,
                    Knowledge.PARSE_FINALIZING, cutoff);
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] knowledge candidate query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 心跳过滤——候选里**最近 span 行早于 cutoff（或压根没有 span）**的子集即真卡死。
     * 对照 Go {@code filterByLastSpanActivity}：查库失败<b>失败安全</b>——按"全都没有心跳"
     * 处理（全部候选都当卡死），宁可多回收也绝不漏回收。
     */
    private List<String> filterByLastSpanActivity(List<String> candidates, OffsetDateTime cutoff) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Set<String> alive;
        try {
            String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
            Object[] args = new Object[candidates.size() + 1];
            for (int i = 0; i < candidates.size(); i++) {
                args[i] = candidates.get(i);
            }
            args[candidates.size()] = cutoff;
            alive = new LinkedHashSet<>(jdbc.queryForList(
                    "SELECT DISTINCT knowledge_id FROM knowledge_processing_spans "
                            + "WHERE knowledge_id IN (" + placeholders + ") AND updated_at > ?",
                    String.class, args));
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] span heartbeat query failed: {} "
                    + "(will fail safe and recover all candidates)", e.getMessage());
            return candidates;
        }
        List<String> out = new ArrayList<>(candidates.size());
        for (String id : candidates) {
            if (!alive.contains(id)) {
                out.add(id); // 无新鲜心跳 → 仍按卡死处理
            }
        }
        return out;
    }

    /**
     * 第二道闸——把"队列里还有活"的候选剔掉（积压 ≠ 孤儿）。
     * 对照 Go {@code filterOutQueued}：先查持久表（错误 ⇒ 推迟<b>全部</b>候选），
     * 再按 inspector 查瞬时队列（错误 ⇒ 当作仍卡死）。
     *
     * @param skippedHolder 单元素输出：被剔掉的候选数（对照 Go 的 {@code skipped} 返回值）
     */
    private List<String> filterOutQueued(List<String> candidates, int[] skippedHolder) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        Set<String> durable;
        try {
            List<Object> args = new ArrayList<>(candidates.size() + 3);
            args.add(WIKI_TASK_TYPE);
            args.add(WIKI_TASK_SCOPE);
            args.add(WIKI_OP_INGEST);
            args.addAll(candidates);
            durable = new LinkedHashSet<>(jdbc.queryForList(
                    "SELECT DISTINCT dedup_key FROM task_pending_ops "
                            + "WHERE task_type = ? AND scope = ? AND op = ? AND dedup_key IN ("
                            + placeholders + ")",
                    String.class, args.toArray()));
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] durable queue probe failed: {} (deferring {} candidate(s))",
                    e.getMessage(), candidates.size());
            skippedHolder[0] = candidates.size();
            return List.of();
        }

        List<String> out = new ArrayList<>(candidates.size());
        int skipped = 0;
        for (String id : candidates) {
            if (durable.contains(id)) {
                skipped++;
                continue;
            }
            if (inspector == null) {
                out.add(id);
                continue;
            }
            boolean queued;
            try {
                queued = inspector.hasQueuedTasksForKnowledge(id);
            } catch (RuntimeException e) {
                log.warn("[Housekeeping] queue probe failed for {}: {} "
                        + "(will fail safe and treat as stuck)", id, e.getMessage());
                out.add(id);
                continue;
            }
            if (queued) {
                skipped++;
                continue;
            }
            out.add(id);
        }
        skippedHolder[0] = skipped;
        return out;
    }

    /** 清扫 B——对照 Go 的摘要清扫（{@code summary_status=processing AND updated_at < cutoff}）。 */
    private void sweepSummary() {
        OffsetDateTime summaryCutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minus(SUMMARY_STALE_THRESHOLD);
        int rows;
        try {
            rows = jdbc.update("UPDATE knowledges SET summary_status = ? "
                            + "WHERE summary_status = ? AND updated_at < ? AND deleted_at IS NULL",
                    SUMMARY_STATUS_FAILED, SUMMARY_STATUS_PROCESSING, summaryCutoff);
        } catch (RuntimeException e) {
            log.warn("[Housekeeping] summary sweep failed: {}", e.getMessage());
            return;
        }
        if (rows > 0) {
            log.info("[Housekeeping] recovered {} stuck summary rows", rows);
        }
    }

    /**
     * 对照 Go {@code staleThreshold}：processing 行可以静置多久才被判孤儿。下限 1 小时
     * （真慢的大 PDF 不能在飞行中被杀），上限随运维配置的 DocumentProcessTimeout 再加
     * 10 分钟缓冲吸收调度抖动。
     */
    Duration staleThreshold() {
        Duration base = STALE_FLOOR;
        if (documentProcessTimeout != null && documentProcessTimeout.compareTo(base) > 0) {
            base = documentProcessTimeout;
        }
        return base.plus(STALE_BUFFER);
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * 对照 Go {@code applyKnowledgeBaseEnvOverrides}：{@code WEKNORA_DOCUMENT_PROCESS_TIMEOUT}
     * 走 Go 的 {@code time.ParseDuration}（{@code 2h}/{@code 90m}/{@code 1h30m}），
     * 缺省、非正数或解析失败回落 {@code DefaultDocumentProcessTimeout}（2h）。
     */
    static Duration documentProcessTimeoutFromEnv() {
        return documentProcessTimeoutFromEnv(System.getenv(DOCUMENT_PROCESS_TIMEOUT_ENV));
    }

    static Duration documentProcessTimeoutFromEnv(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_DOCUMENT_PROCESS_TIMEOUT;
        }
        Duration parsed = parseGoDuration(raw.trim());
        return parsed == null || parsed.isZero() || parsed.isNegative()
                ? DEFAULT_DOCUMENT_PROCESS_TIMEOUT : parsed;
    }

    /**
     * Go {@code time.ParseDuration} 的最小复刻（单位表 ns/us/µs/ms/s/m/h）。
     * 单位按 Go 的语义消费"连续到下一个数字或小数点为止的整段"；文法错误返回
     * {@code null}（= Go 的 error 分支 → 调用方回落缺省值）。
     */
    private static Duration parseGoDuration(String s) {
        if (s.isEmpty()) {
            return null;
        }
        Duration total = Duration.ZERO;
        int i = 0;
        while (i < s.length()) {
            int numStart = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (i == numStart) {
                return null;
            }
            String digits = s.substring(numStart, i);
            int unitStart = i;
            while (i < s.length() && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '.') {
                i++;
            }
            if (i == unitStart) {
                return null; // 缺单位
            }
            String unit = s.substring(unitStart, i);
            long scale = switch (unit) {
                case "ns" -> 1L;
                case "us", "\u00b5s", "\u03bcs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60L * 1_000_000_000L;
                case "h" -> 3_600L * 1_000_000_000L;
                default -> -1L;
            };
            if (scale < 0) {
                return null; // 未知单位
            }
            try {
                total = total.plusNanos((long) (Double.parseDouble(digits) * scale));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return total;
    }

    /** 对照 Go {@code housekeepingEnabled}：缺省开启，只有显式 0/false/off/no 才关。 */
    static boolean housekeepingEnabledFromEnv() {
        return housekeepingEnabledFromEnv(System.getenv(ENABLED_ENV));
    }

    static boolean housekeepingEnabledFromEnv(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return true;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "0", "false", "off", "no" -> {
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** 复用全仓唯一的 Go {@code time.Duration.String()} 复刻（错误文案是契约的一部分）。 */
    private static String goDuration(Duration d) {
        return SandboxExecuteResult.GoDuration.of(d);
    }
}
