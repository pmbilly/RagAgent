package com.ragagent.audit.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计保留期清扫器。
 *
 * <p>每天清扫一次 audit_logs，删除早于 {@code retentionDays} 的行。它是一个小而自洽的
 * 后台任务——不引入调度器，因为保留期没有墙上时钟对齐的要求：
 * 只需要"大约每天、最终会跑"。实现是一条<b>虚拟线程</b>加一个可中断的等待。</p>
 *
 * <p>{@code retentionDays <= 0} 让 {@link #start()} 变成 no-op；这是配置层面
 * "彻底关掉保留期"的开关。校验发生在配置加载时，所以到这里时非正值是刻意为之。</p>
 *
 * <p><b>接线方式</b>：本类刻意<b>不带</b> Spring 注解，保持纯逻辑、可脱离容器单测。
 * 真实的启动/停止由 {@link AuditRetentionLifecycle}（{@code SmartLifecycle}）
 * 驱动，它读 {@code weknora.audit.retention-days}（缺省 90）。</p>
 */
public class AuditLogRetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRetentionRunner.class);

    /**
     * 两次清扫的间隔。
     * 24h 对按天保留期足够——cutoff 在两次运行之间正好推进 24h，
     * 每次清扫删掉一天的滚出量。缩短只会产生空扫；拉长会让陈旧行多堆一天。
     */
    public static final Duration PURGE_INTERVAL = Duration.ofHours(24);

    /**
     * 把首次清扫推迟到启动后不久，
     * 免得跟迁移流量或其他启动工作抢资源。长到首次 DELETE 不会撞上初始请求洪峰；
     * 短到运维在同一次重启里就能看到清扫触发。
     */
    public static final Duration PURGE_STARTUP_DELAY = Duration.ofMinutes(10);

    /** 单次清扫的超时（30s）：卡住的连接不能把线程扣作人质。 */
    public static final Duration PURGE_TIMEOUT = Duration.ofSeconds(30);

    private final AuditLogService svc;
    private final int retentionDays;
    private final Duration interval;
    private final Duration startupDelay;

    /** 一次性闸门：容器误调两次不能双重清扫。 */
    private final AtomicBoolean startOnce = new AtomicBoolean(false);
    private final AtomicBoolean stopOnce = new AtomicBoolean(false);
    /**
     * 在 startOnce 内部、done 被交给线程<b>之前</b>置位，
     * 这样 {@link #stop()} 能区分"Start 从未被调用"与"Start 正在运行"，而不必阻塞在 done 上。
     * 没有它的话，构造了却从未 Start 的 runner（容器早期初始化失败、跳过 Start 的测试装配）
     * 会让 Stop() 永久死锁在一个没人关闭的 done 上。
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final CountDownLatch done = new CountDownLatch(1);
    /** 保护 {@link #stopRequested} 并承载等待；{@code stop()} 会 notifyAll 叫醒它。 */
    private final Object waitLock = new Object();
    private boolean stopRequested;

    /** 生产默认：24h 间隔 + 10 分钟启动延迟。 */
    public AuditLogRetentionRunner(AuditLogService svc, int retentionDays) {
        this(svc, retentionDays, PURGE_INTERVAL, PURGE_STARTUP_DELAY);
    }

    /** 测试用：可缩短间隔与启动延迟。 */
    public AuditLogRetentionRunner(AuditLogService svc, int retentionDays,
                                   Duration interval, Duration startupDelay) {
        this.svc = svc;
        this.retentionDays = retentionDays;
        this.interval = interval;
        this.startupDelay = startupDelay;
    }

    /**
     * 拉起后台清扫线程。多次调用是 no-op（一次性闸门），
     * 所以误调两次的容器装配不会双重清扫。{@code retentionDays <= 0} 时 runner 保持休眠
     * ——{@link #stop()} 仍会干净地返回。
     */
    public void start() {
        if (svc == null) {
            // 在 startOnce 之前返回：started 保持 false，Stop 会立刻返回（不会挂在 done 上）。
            return;
        }
        if (!startOnce.compareAndSet(false, true)) {
            return;
        }
        started.set(true);
        if (retentionDays <= 0) {
            log.info("[audit-retention] disabled (retention_days={})", retentionDays);
            done.countDown();
            return;
        }
        log.info("[audit-retention] starting daily sweep: retention_days={} interval={}",
                retentionDays, interval);
        Thread.ofVirtual().name("audit-retention").start(this::loop);
    }

    /**
     * 通知循环退出并阻塞到它返回。幂等。
     * 从未调用过 Start 时立即返回（没有 done 可等——见 {@code started} 字段的注释）。
     */
    public void stop() {
        if (!started.get()) {
            return;
        }
        if (stopOnce.compareAndSet(false, true)) {
            synchronized (waitLock) {
                stopRequested = true;
                waitLock.notifyAll();
            }
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 真正的清扫节奏。
     *
     * <p>不使用任何请求作用域的 ThreadLocal（本方法不读
     * {@code TenantContext}，也<strong>不能</strong>读——它跑在独立虚拟线程上）。</p>
     */
    private void loop() {
        try {
            if (awaitQuietly(startupDelay)) {
                return;
            }
            runOnce();
            while (!isStopRequested()) {
                if (awaitQuietly(interval)) {
                    return;
                }
                runOnce();
            }
        } finally {
            done.countDown();
        }
    }

    /**
     * 执行一次清扫。
     *
     * <p>DB 调用给足超时（30s），卡住的连接不会把线程永久扣住——单次清扫 30s 内没跑完
     * 就记日志、24h 后再来。错误按 WARN 而非 ERROR 记，因为后果只是表再多涨一天，
     * 没有任何东西坏掉。</p>
     *
     * <p>30s 约束由数据源自身的超时参数承担（{@link #PURGE_TIMEOUT} 为文档常量）。</p>
     */
    public void runOnce() {
        long deleted;
        try {
            deleted = svc.purge(retentionDays);
        } catch (RuntimeException e) {
            log.warn("[audit-retention] sweep failed: retention_days={} err={}",
                    retentionDays, e.getMessage());
            return;
        }
        if (deleted > 0) {
            log.info("[audit-retention] sweep complete: deleted={} retention_days={}",
                    deleted, retentionDays);
        } else {
            log.debug("[audit-retention] sweep complete: deleted=0 retention_days={}",
                    retentionDays);
        }
    }

    // ── 可中断等待 ──

    /**
     * 等待指定时长；被 {@link #stop()} 叫醒时返回 true（调用方应退出循环）。
     * 用 {@code wait/notifyAll} 而非 {@code Thread.sleep}，才能让 Stop 立即生效
     * 而不是等满 24h。
     */
    private boolean awaitQuietly(Duration d) {
        long deadline = System.nanoTime() + d.toNanos();
        synchronized (waitLock) {
            while (!stopRequested) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                try {
                    waitLock.wait(Math.max(1L, remainingNanos / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
            return true;
        }
    }

    private boolean isStopRequested() {
        synchronized (waitLock) {
            return stopRequested;
        }
    }

    /** 诊断用（日志/测试可读当前保留期配置）。 */
    public int retentionDays() {
        return retentionDays;
    }
}
