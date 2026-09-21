package com.ragagent.im.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有界、按用户限额的 QA 请求队列 + 固定 worker 池（对照 Go internal/im/qaqueue.go
 * 全文，波 5 W5γ1 翻译）。Java 用虚拟线程做 worker（锁/条件与 Go 的 sync.Cond 同构）。
 *
 * <p><b>Redis 面（备案）</b>：Go 的 redis 可 nil——nil 时每用户限额只在本实例生效、
 * 无跨实例全局闸门（qaqueue.go L81-88 注释原文语义）。按波 5 纪律「Redis 一律先落
 * 进程内存形态」，本类只实现 Go 的 redis==nil 分支；全局 per-user 计数与全局闸门
 * （RedisKeyQueueUser/RedisKeyGlobalGate 的 Lua CAS）留接缝 {@link RedisPort}，
 * 多实例部署时接入。</p>
 */
public final class QaQueue {

    private static final Logger log = LoggerFactory.getLogger(QaQueue.class);

    /** 队列里最多排队数（qaqueue.go L17）。 */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 50;
    /** 单用户最多排队数（L20）。 */
    public static final int DEFAULT_MAX_PER_USER = 3;
    /** 默认并发 worker 数（L21）。 */
    public static final int DEFAULT_WORKERS = 5;
    /** 请求在队列里最多等这么久（L23）。 */
    public static final long QUEUE_TIMEOUT_SECONDS = 60;
    /** 指标日志间隔（L388）。 */
    private static final long METRICS_LOG_INTERVAL_SECONDS = 30;

    /** 可选的 Redis 计数面（跨实例部署接入；单实例传 null）。 */
    public interface RedisPort {
        /** INCR+EXPIRE；返回自增后计数；Redis 故障返回 null（跳过全局检查）。 */
        Long incrWithTtl(String key, int ttlSeconds);

        /** DECR。 */
        void decr(String key);
    }

    /** 排队的 QA 请求（对照 Go {@code im.qaRequest}，qaqueue.go L36-52）。γ2 填业务字段。 */
    public static final class QaRequest {
        final String userKey;
        final long enqueuedAtNanos = System.nanoTime();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        public final IncomingMessage msg;

        public QaRequest(String userKey, IncomingMessage msg) {
            this.userKey = userKey;
            this.msg = msg;
        }

        public String userKey() {
            return userKey;
        }

        /** 对照 req.ctx.Err()：取消即超时/剔除。 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            cancelled.set(true);
        }
    }

    /** 可观测队列状态（对照 Go {@code im.QueueMetrics}，qaqueue.go L55-68）。 */
    public record QueueMetrics(int depth, long activeWorkers, long totalEnqueued,
            long totalProcessed, long totalRejected, long totalTimeout) {
    }

    private final ReentrantLock mu = new ReentrantLock();
    private final Condition notEmpty = mu.newCondition();
@SuppressWarnings("unchecked")
    private final List<QaRequest> queue = new ArrayList<>();
    private final int maxSize;
    private final int maxPerUser;
    private final int workers;
    /** userKey → 排队数（redis==nil 分支的本地计数）。 */
    private final Map<String, Integer> perUser = new HashMap<>();
    private boolean closed;

    private final RedisPort redis;

    // 指标（对照 Go atomic 计数器，qaqueue.go L91-95）。
    private final AtomicLong activeWorkers = new AtomicLong();
    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalProcessed = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();
    private final AtomicLong totalTimeout = new AtomicLong();

    private final java.util.function.Consumer<QaRequest> handler;
    /** 超时兜底回复的送达通道（对照 Go 的 req.adapter.SendReply；γ2 接适配器）。 */
    private final java.util.function.BiConsumer<QaRequest, ReplyMessage> replySink;
    private final List<Thread> workerThreads = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public QaQueue(int workers, int maxSize, int maxPerUser,
            java.util.function.Consumer<QaRequest> handler) {
        this(workers, maxSize, maxPerUser, handler, null, null);
    }

    public QaQueue(int workers, int maxSize, int maxPerUser,
            java.util.function.Consumer<QaRequest> handler, RedisPort redis,
            java.util.function.BiConsumer<QaRequest, ReplyMessage> replySink) {
        ((ArrayList<QaRequest>) this.queue).ensureCapacity(maxSize);
        this.maxSize = maxSize;
        this.maxPerUser = maxPerUser;
        this.workers = workers;
        this.handler = handler;
        this.redis = redis;
        this.replySink = replySink;
    }

    /** 启动 worker 线程（对照 Start；metricsLoop 的 30s 周期日志随 Stop 一并退出）。 */
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < workers; i++) {
            int id = i;
            Thread t = Thread.ofVirtual().name("im-qa-worker-" + id).unstarted(() -> runWorker(id));
            workerThreads.add(t);
            t.start();
        }
    }

    /** 通知所有 worker 在排空后退出（对照 Stop）。 */
    public void stop() {
        mu.lock();
        try {
            closed = true;
            notEmpty.signalAll();
        } finally {
            mu.unlock();
        }
    }

    /**
     * 入队。返回 0 基位置；队满或超每用户限额抛 {@link RejectedException}
     * （对照 Go 的 error 返回）。
     */
    public int enqueue(QaRequest req) {
        // 本地（redis==nil）每用户限额在锁内查——Go 的 redis==nil 分支。
        mu.lock();
        try {
            if (closed) {
                throw new RejectedException("queue is closed");
            }
            if (queue.size() >= maxSize) {
                totalRejected.incrementAndGet();
                throw new RejectedException("queue full (" + queue.size() + "/" + maxSize + ")");
            }
            int mine = perUser.getOrDefault(req.userKey, 0);
            if (mine >= maxPerUser) {
                totalRejected.incrementAndGet();
                throw new RejectedException(
                        "per-user queue limit reached (" + mine + "/" + maxPerUser + ")");
            }
            queue.add(req);
            perUser.merge(req.userKey, 1, Integer::sum);
            totalEnqueued.incrementAndGet();
            notEmpty.signal();
            return queue.size() - 1;
        } finally {
            mu.unlock();
        }
    }

    /** 按 userKey 取消并移除排队请求；有命中返回 true（对照 Remove）。 */
    public boolean remove(String userKey) {
        mu.lock();
        try {
            for (int i = 0; i < queue.size(); i++) {
                QaRequest req = queue.get(i);
                if (req.userKey.equals(userKey)) {
                    req.cancel();
                    queue.remove(i);
                    decrementPerUser(userKey);
                    return true;
                }
            }
            return false;
        } finally {
            mu.unlock();
        }
    }

    public QueueMetrics metrics() {
        mu.lock();
        int depth;
        try {
            depth = queue.size();
        } finally {
            mu.unlock();
        }
        return new QueueMetrics(depth, activeWorkers.get(), totalEnqueued.get(),
                totalProcessed.get(), totalRejected.get(), totalTimeout.get());
    }

    private void decrementPerUser(String userKey) {
        int left = perUser.merge(userKey, -1, Integer::sum);
        if (left <= 0) {
            perUser.remove(userKey);
        }
    }

    private void runWorker(int id) {
        while (true) {
            QaRequest req = dequeue();
            if (req == null) {
                return; // 队列已关
            }
            // 排队期间被取消/超时的请求直接跳过。
            if (req.isCancelled()) {
                totalTimeout.incrementAndGet();
                continue;
            }
            long waitedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - req.enqueuedAtNanos);
            if (waitedSeconds > QUEUE_TIMEOUT_SECONDS) {
                totalTimeout.incrementAndGet();
                log.warn("[IM] Queue timeout: user={} waited={}s, discarding",
                        req.msg == null ? "" : req.msg.userId, waitedSeconds);
                if (replySink != null) {
                    try {
                        replySink.accept(req, new ReplyMessage("您的消息等待超时，请重新发送。", false, true));
                    } catch (RuntimeException e) {
                        // Go 的 _ = SendReply：错误吞掉
                    }
                }
                req.cancel();
                continue;
            }

            activeWorkers.incrementAndGet();
            try {
                handler.accept(req);
            } catch (RuntimeException e) {
                log.error("[IM] QA handler panicked: {}", e.getMessage(), e);
            } finally {
                activeWorkers.decrementAndGet();
            }
            totalProcessed.incrementAndGet();
        }
    }

    private QaRequest dequeue() {
        mu.lock();
        try {
            while (queue.isEmpty() && !closed) {
                notEmpty.awaitUninterruptibly();
            }
            if (closed && queue.isEmpty()) {
                return null;
            }
            QaRequest req = queue.remove(0);
            decrementPerUser(req.userKey);
            return req;
        } finally {
            mu.unlock();
        }
    }

    /** 入队被拒（队满/超限/已关）。 */
    public static final class RejectedException extends RuntimeException {
        public RejectedException(String message) {
            super(message);
        }
    }
}
