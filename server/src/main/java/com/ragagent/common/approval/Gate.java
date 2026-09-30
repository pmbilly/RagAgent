package com.ragagent.common.approval;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ragagent.common.llm.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 工具人工审批门。
 *
 * <p>等待者（waiter）只存在于**发起 {@link #requestAndWait} 的那个实例**的内存里。
 * 当注入了 Redis 客户端时，打到任意副本的 Resolve 会经 Pub/Sub 广播，
 * 由持有 pending 的实例投递决策（跨实例支持）；没有 Redis 时退化为单进程行为
 * （部署必须开粘性会话）。</p>
 *
 * <p><b>语义要点</b>：
 * <ul>
 *   <li>pending 注册/等待/超时/取消：{@link #requestAndWait}；</li>
 *   <li>Resolve 幂等与冲突：已投递 → {@code ALREADY_RESOLVED}；不存在 → {@code PENDING_NOT_FOUND}；</li>
 *   <li>租户/用户不匹配：{@code TENANT_MISMATCH} / {@code USER_MISMATCH}（调用方 userID 为空
 *       但等待者有 userID 时**也算不匹配**，fail-close）；</li>
 *   <li>跨实例：{@link #resolveCrossInstance} 用“每 pending 的回复频道 + 每调用 nonce”换回准确状态；</li>
 *   <li>订阅循环：{@link #runSubscriber} 断线按 1s→30s 指数退避重连。</li>
 * </ul>
 *
 * <p><b>可配置性扩展</b>：ack 窗口（{@code ackTimeout}）与实例 ID（{@code instanceId}）
 * 均可注入，便于测试不必真等 3 秒、可在同一 JVM 模拟多实例；
 * 并新增 {@link #close()} 用于停止订阅线程（容器关闭/测试隔离需要确定出口）。</p>
 */
public class Gate implements McpApproval, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Gate.class);

    /** 跨副本广播 Resolve 的 Redis 频道前缀 */
    public static final String PUBSUB_CHANNEL_BASE = "weknora:mcp_approval:resolve";

    /**
     * 频道命名空间环境变量：频道会追加该后缀，
     * 使共享同一 Redis 的多套部署不互相串台。
     */
    public static final String NAMESPACE_ENV = "WEKNORA_REDIS_NAMESPACE";

    /**
     * fail-open 开关：只有值为 "true"（忽略大小写与首尾空白）才 fail-open；
     * 默认 fail-close——策略查询出错时**仍要求审批**，避免瞬时 DB 故障悄悄放行危险工具。
     */
    public static final String FAIL_OPEN_ENV = "WEKNORA_AGENT_TOOL_APPROVAL_FAIL_OPEN";

    /** 订阅生效等待窗口：2 秒 */
    private static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(2);
    /** 订阅循环的收消息轮询间隔（仅用于周期性检查关闭标志） */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /** 断线重连的初始退避 1s */
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    /** 断线重连的退避上限 30s */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Object mu = new Object();
    /** pendingId → 等待者 */
    private final Map<String, Waiter> pending = new HashMap<>();
    /** 策略查询口；为 null 表示关闭审批门 */
    private final Checker checker;
    /** 审批等待时长（默认 10 分钟） */
    private final Duration timeout;
    /** Redis pubsub；为 null 即单实例模式 */
    private final RedisPubSub redis;
    /** true = 策略查询失败时仍要求审批 */
    private final boolean failClose;
    /** 实例标识（可注入），用于忽略自己发布的 pubsub 报文 */
    private final String instanceId;
    /** 跨实例 ack 等待窗口（可配置） */
    private final Duration ackTimeout;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread subscriberThread;

    /**
     * checker 可为 null（关闭审批门）、options 可为 null（默认值）、redis 可为 null（单实例）。
     */
    public Gate(GateOptions options, Checker checker, RedisPubSub redis) {
        GateOptions opts = options == null ? GateOptions.defaults() : options;
        this.checker = checker;
        this.timeout = opts.timeout();
        this.redis = redis;
        this.failClose = opts.failClose();
        this.instanceId = opts.instanceId();
        this.ackTimeout = opts.ackTimeout();
        if (redis != null) {
            this.subscriberThread = Thread.ofVirtual()
                    .name("mcp-approval-subscriber")
                    .start(this::runSubscriber);
        } else {
            this.subscriberThread = null;
        }
    }

    /** 便捷构造：默认 options。 */
    public Gate(Checker checker, RedisPubSub redis) {
        this(null, checker, redis);
    }

    /** namespaced 的 pubsub 频道名 */
    static String pubsubChannel() {
        String ns = System.getenv(NAMESPACE_ENV);
        if (ns != null && !ns.isBlank()) {
            return PUBSUB_CHANNEL_BASE + ":" + ns.trim();
        }
        return PUBSUB_CHANNEL_BASE;
    }

    // ------------------------------------------------------------------
    // 策略查询
    // ------------------------------------------------------------------

    /**
     * 是否需要停下来等人工确认。
     * checker 为 null / 身份缺失 → false；查询出错时按 fail-close（要求审批）或 fail-open（放行）处理。
     */
    @Override
    public boolean needsApproval(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (checker == null || tenantId == 0
                || serviceId == null || serviceId.isEmpty()
                || toolName == null || toolName.isEmpty()) {
            return false;
        }
        try {
            return checker.isRequired(ctx, tenantId, serviceId, toolName);
        } catch (RuntimeException e) {
            if (failClose) {
                log.warn("mcp tool approval check failed (fail-close: requiring approval): {}", e.toString());
                return true;
            }
            log.warn("mcp tool approval check failed (fail-open: skip gate): {}", e.toString());
            return false;
        }
    }

    /**
     * 策略是否允许注册/执行。
     * 没有 gate/checker 时工具保持启用；租户或工具身份缺失时 fail-closed。
     */
    @Override
    public boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName) {
        if (checker == null) {
            return true;
        }
        if (tenantId == 0 || serviceId == null || serviceId.isEmpty()
                || toolName == null || toolName.isEmpty()) {
            return false;
        }
        return checker.isEnabled(ctx, tenantId, serviceId, toolName);
    }

    /** 目录枚举用的批量启用查询。 */
    public Map<String, Boolean> enabledTools(
            Cancellation ctx, long tenantId, String serviceId, List<String> names) {
        return ToolPolicy.enabledTools(ctx, checker, tenantId, serviceId, names);
    }

    // ------------------------------------------------------------------
    // 等待审批
    // ------------------------------------------------------------------

    /**
     * 先发 “approval required” 事件，
     * 再阻塞等待 Resolve / 超时 / ctx 取消三种出口。
     */
    @Override
    public Decision requestAndWait(Cancellation ctx, PendingRequest req) {
        if (checker == null) {
            // 未启用审批门 → 直接放行
            return Decision.allow();
        }
        if (req.eventBus() == null) {
            throw ApprovalException.internal("tool approval: EventBus is nil");
        }

        String pendingId = UUID.randomUUID().toString();
        Waiter w = new Waiter(req.tenantId(), req.userId());

        synchronized (mu) {
            pending.put(pendingId, w);
        }
        try {
            // args 解析失败不阻塞：事件体里的 args 保持为 null
            Object argsObj = ApprovalJson.parseLoose(req.args());
            int timeoutSec = timeoutSeconds(timeout);

            ToolApprovalRequiredData data = new ToolApprovalRequiredData(
                    pendingId, req.tenantId(), req.sessionId(), req.assistantMessageId(),
                    req.serviceId(), req.serviceName(), req.mcpToolName(), req.registeredToolName(),
                    req.description(), argsObj, req.args(), timeoutSec,
                    Instant.now().getEpochSecond(), req.toolCallId(), req.requestId());
            Event evt = Event.of(
                    pendingId + "-approval-required",
                    ResponseType.TOOL_APPROVAL_REQUIRED,
                    req.sessionId(),
                    data,
                    Map.of("assistant_message_id", req.assistantMessageId(), "pending_id", pendingId),
                    req.requestId());
            try {
                req.eventBus().emit(evt);
            } catch (RuntimeException e) {
                throw ApprovalException.internal("emit tool approval required: " + e.getMessage(), e);
            }

            // 注册取消回调：取消即投递取消决策
            AutoCloseable cancelReg = ctx.onCancel(() -> w.deliver(Decision.cancel("request canceled")));
            try {
                Decision d = w.await(timeout);
                if (d != null) {
                    emitResolved(req, pendingId, d);
                    return d;
                }
                // 投递超时决策后无条件再取一次——
                // 若投递失败说明 Resolve 抢先，则用它的决策。
                w.deliver(Decision.timeout("approval timeout"));
                d = w.await(null);
                emitResolved(req, pendingId, d);
                return d;
            } finally {
                closeQuietly(cancelReg);
            }
        } finally {
            synchronized (mu) {
                pending.remove(pendingId);
            }
        }
    }

    /**
     * 发 “mcp oauth required” 事件后阻塞等待授权。
     *
     * <p>与 {@link #requestAndWait} 不同：<b>不查审批策略</b>——它是由 MCP 传输层返回的
     * “authorization required” 错误被动触发的。返回 {@code approved == true} 表示用户已完成授权、
     * 工具调用应被重试。</p>
     */
    public Decision requestOAuthAndWait(Cancellation ctx, OAuthPendingRequest req) {
        if (req.eventBus() == null) {
            throw ApprovalException.internal("oauth gate: EventBus is nil");
        }

        String pendingId = UUID.randomUUID().toString();
        Waiter w = new Waiter(req.tenantId(), req.userId());

        synchronized (mu) {
            pending.put(pendingId, w);
        }
        try {
            Duration waitTimeout = timeout;
            if (req.waitTimeout() != null && !req.waitTimeout().isZero() && !req.waitTimeout().isNegative()) {
                waitTimeout = req.waitTimeout();
            }
            int timeoutSec = timeoutSeconds(waitTimeout);

            McpOauthRequiredData data = new McpOauthRequiredData(
                    pendingId, req.tenantId(), req.sessionId(), req.assistantMessageId(),
                    req.serviceId(), req.serviceName(), req.mcpToolName(), timeoutSec,
                    Instant.now().getEpochSecond(), req.toolCallId(), req.requestId());
            Event evt = Event.of(
                    pendingId + "-mcp-oauth-required",
                    ResponseType.MCP_OAUTH_REQUIRED,
                    req.sessionId(),
                    data,
                    Map.of("assistant_message_id", req.assistantMessageId(), "pending_id", pendingId),
                    req.requestId());
            try {
                req.eventBus().emit(evt);
            } catch (RuntimeException e) {
                throw ApprovalException.internal("emit mcp oauth required: " + e.getMessage(), e);
            }

            AutoCloseable cancelReg = ctx.onCancel(() -> w.deliver(Decision.cancel("request canceled")));
            try {
                Decision d = w.await(waitTimeout);
                if (d != null) {
                    emitOAuthResolved(req, pendingId, d);
                    return d;
                }
                w.deliver(Decision.timeout("authorization timeout"));
                d = w.await(null);
                emitOAuthResolved(req, pendingId, d);
                return d;
            } finally {
                closeQuietly(cancelReg);
            }
        } finally {
            synchronized (mu) {
                pending.remove(pendingId);
            }
        }
    }

    // ------------------------------------------------------------------
    // 决议
    // ------------------------------------------------------------------

    /**
     * 完成一个待决审批。
     *
     * <p>{@code tenantId} 必须与发起等待的租户一致；{@code userId}（非空时）必须是会话属主。
     * 本实例没有该 pending 且配置了 Redis 时，会广播到所有副本并等 ack，
     * 从而区分“在别的实例上完成了”与“根本没有这个 pending”。</p>
     *
     * @throws ApprovalException 按 {@link ApprovalException.Kind} 判别：
     *                           PENDING_NOT_FOUND / ALREADY_RESOLVED / TENANT_MISMATCH / USER_MISMATCH
     */
    public void resolve(long tenantId, String userId, String pendingId, Decision d) {
        ApprovalException err = deliverLocal(tenantId, userId, pendingId, d);
        if (err == null) {
            return;
        }
        switch (err.kind()) {
            case TENANT_MISMATCH, USER_MISMATCH, ALREADY_RESOLVED -> throw err;
            case PENDING_NOT_FOUND -> {
                if (redis == null) {
                    throw err;   // 单实例：本机没有就是真的没有
                }
                resolveCrossInstance(tenantId, userId, pendingId, d);
            }
            default -> throw err;
        }
    }

    /**
     * 广播 Resolve 并短暂等待持有者 ack。
     *
     * <p>ack 走“每 pending 一个回复频道”，携带与本地路径相同的错误码，
     * 因此即使会话在别的副本上，HTTP 状态码依然准确。</p>
     */
    private void resolveCrossInstance(long tenantId, String userId, String pendingId, Decision d) {
        // 每次调用一个 nonce：同一 pendingID 的并发 Resolve 不会互吞 ack
        String nonce = UUID.randomUUID().toString();
        String replyChannel = pubsubChannel() + ":reply:" + pendingId;
        RedisPubSub.Subscription sub = redis.subscribe(replyChannel);
        try {
            // 先确认订阅生效再发布，避免漏掉 ack
            if (!sub.awaitSubscribed(SUBSCRIBE_TIMEOUT)) {
                throw ApprovalException.internal("subscribe approval reply: timeout waiting for subscription");
            }

            String payload = ApprovalJson.write(
                    ResolveMessage.of(tenantId, userId, pendingId, d, replyChannel, instanceId, nonce));
            redis.publish(pubsubChannel(), payload);

            // ack 窗口很短（UX 上宁可快速返回准确的 404/409，也不要慢慢返回 200）
            long deadline = System.nanoTime() + ackTimeout.toNanos();
            while (true) {
                Duration left = Duration.ofNanos(deadline - System.nanoTime());
                if (left.isZero() || left.isNegative()) {
                    throw ApprovalException.pendingNotFound();
                }
                String msg = sub.receiveMessage(left);
                if (msg == null) {
                    // 超时或订阅错误：无人确认 → 视为不存在
                    throw ApprovalException.pendingNotFound();
                }
                ResolveAck ack = ApprovalJson.read(msg, ResolveAck.class);
                if (ack == null) {
                    continue;
                }
                // 忽略发给别的并发 Resolve 调用的 ack
                if (ack.requestNonce() != null && !ack.requestNonce().isEmpty()
                        && !ack.requestNonce().equals(nonce)) {
                    continue;
                }
                switch (ack.status() == null ? "" : ack.status()) {
                    case ResolveAck.STATUS_OK -> {
                        return;
                    }
                    case ResolveAck.STATUS_TENANT_MISMATCH -> throw ApprovalException.tenantMismatch();
                    case ResolveAck.STATUS_USER_MISMATCH -> throw ApprovalException.userMismatch();
                    case ResolveAck.STATUS_ALREADY_RESOLVED -> throw ApprovalException.alreadyResolved();
                    case ResolveAck.STATUS_NOT_FOUND -> throw ApprovalException.pendingNotFound();
                    default -> throw ApprovalException.internal(
                            "approval reply: unexpected status \"" + ack.status() + "\"");
                }
            }
        } finally {
            sub.close();
        }
    }

    /**
     * 只尝试满足**本实例**上的等待者。
     *
     * @return null 表示投递成功；否则是对应哨兵异常
     */
    private ApprovalException deliverLocal(long tenantId, String userId, String pendingId, Decision d) {
        Waiter w;
        synchronized (mu) {
            w = pending.get(pendingId);
            if (w == null) {
                return ApprovalException.pendingNotFound();
            }
            if (w.tenantId != tenantId) {
                return ApprovalException.tenantMismatch();
            }
            // 鉴权：注册时带 userID 的 pending，调用方必须给出**相同的非空** userID。
            // 调用方 userID 为空一律视为不匹配（fail-close），避免缺失鉴权中间件时绕过校验。
            if (!w.userId.isEmpty() && !w.userId.equals(userId)) {
                return ApprovalException.userMismatch();
            }
        }
        if (w.resolved.get()) {
            return ApprovalException.alreadyResolved();
        }
        if (!w.deliver(d)) {
            return ApprovalException.alreadyResolved();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 跨实例订阅
    // ------------------------------------------------------------------

    /**
     * 监听跨实例 Resolve 广播并投递给本地等待者。
     * 随进程/本对象存活（直到 {@link #close()}）；Redis 抖动时按上限指数退避重连。
     */
    private void runSubscriber() {
        String channel = pubsubChannel();
        Duration backoff = MIN_BACKOFF;
        while (!closed.get()) {
            RedisPubSub.Subscription sub = null;
            try {
                sub = redis.subscribe(channel);
                sub.awaitSubscribed(SUBSCRIBE_TIMEOUT);
                // 订阅成功后重置退避
                backoff = MIN_BACKOFF;
                while (!closed.get()) {
                    String payload = sub.receiveMessage(POLL_INTERVAL);
                    if (payload != null) {
                        handleResolveMessage(payload);
                    }
                }
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    log.warn("mcp approval pubsub subscribe: {}", e.toString());
                }
            } finally {
                if (sub != null) {
                    sub.close();
                }
            }
            if (closed.get()) {
                break;
            }
            // 断线后固定 sleep(backoff)，再把退避翻倍（上限 30s）
            sleepQuietly(backoff);
            if (backoff.compareTo(MAX_BACKOFF) < 0) {
                backoff = backoff.multipliedBy(2);
                if (backoff.compareTo(MAX_BACKOFF) > 0) {
                    backoff = MAX_BACKOFF;
                }
            }
        }
    }

    /** 处理一条 Resolve 广播报文并回执 ack */
    private void handleResolveMessage(String payload) {
        ResolveMessage m = ApprovalJson.read(payload, ResolveMessage.class);
        if (m == null) {
            log.warn("mcp approval pubsub: bad payload");
            return;
        }
        // 跳过自己发布的报文（在本地必然 miss，只会刷出 ErrPendingNotFound 噪音）
        if (instanceId.equals(m.originId())) {
            return;
        }
        ApprovalException err = deliverLocal(m.tenantId(), m.userId(), m.pendingId(), m.toDecision());

        // 回执给发起实例，让它返回准确的 HTTP 状态码：
        // 只有真正持有 pending（或真的检测到冲突）的实例才回复，其他副本对 NotFound 保持沉默。
        if (m.replyChannel() != null && !m.replyChannel().isEmpty()) {
            String status = statusOf(err);
            if (status != null) {
                try {
                    redis.publish(m.replyChannel(), ApprovalJson.write(
                            ResolveAck.of(m.pendingId(), status, instanceId, m.requestNonce())));
                } catch (RuntimeException e) {
                    log.warn("mcp approval pubsub reply: {}", e.toString());
                }
            }
        }

        // 投递成功或本副本没有该 pending 都保持安静，其他错误才告警
        if (err != null && err.kind() != ApprovalException.Kind.PENDING_NOT_FOUND) {
            log.warn("mcp approval pubsub deliver: {}", err.toString());
        }
    }

    /** 错误 → ack status 的映射（PENDING_NOT_FOUND → 不回执） */
    private static String statusOf(ApprovalException err) {
        if (err == null) {
            return ResolveAck.STATUS_OK;
        }
        return switch (err.kind()) {
            case TENANT_MISMATCH -> ResolveAck.STATUS_TENANT_MISMATCH;
            case USER_MISMATCH -> ResolveAck.STATUS_USER_MISMATCH;
            case ALREADY_RESOLVED -> ResolveAck.STATUS_ALREADY_RESOLVED;
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // 事件发送
    // ------------------------------------------------------------------

    /** 发结果事件（失败只吞掉） */
    private void emitResolved(PendingRequest req, String pendingId, Decision d) {
        try {
            req.eventBus().emit(Event.of(
                    pendingId + "-approval-resolved",
                    ResponseType.TOOL_APPROVAL_RESOLVED,
                    req.sessionId(),
                    new ToolApprovalResolvedData(pendingId, d.approved(), d.reason(), d.timedOut(),
                            d.contextCanceled()),
                    Map.of("assistant_message_id", req.assistantMessageId()),
                    req.requestId()));
        } catch (RuntimeException e) {
            log.warn("emit tool approval resolved failed (pending_id={}): {}", pendingId, e.toString());
        }
    }

    /** 发 OAuth 结果事件（失败只吞掉） */
    private void emitOAuthResolved(OAuthPendingRequest req, String pendingId, Decision d) {
        try {
            req.eventBus().emit(Event.of(
                    pendingId + "-mcp-oauth-resolved",
                    ResponseType.MCP_OAUTH_RESOLVED,
                    req.sessionId(),
                    new McpOauthResolvedData(pendingId, req.serviceId(), d.approved(), d.reason(),
                            d.timedOut(), d.contextCanceled()),
                    Map.of("assistant_message_id", req.assistantMessageId()),
                    req.requestId()));
        } catch (RuntimeException e) {
            log.warn("emit mcp oauth resolved failed (pending_id={}): {}", pendingId, e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 生命周期 / 工具
    // ------------------------------------------------------------------

    /**
     * 停止跨实例订阅线程（Spring 容器关闭/测试隔离需要一个确定的出口）。没配 Redis 时为空操作。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && subscriberThread != null) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 秒数向下取整，且至少 1 秒 */
    private static int timeoutSeconds(Duration d) {
        long sec = d.toSeconds();
        return (int) Math.max(1L, sec);
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // 注销/关闭失败无需处理
        }
    }

    /**
     * 待决等待者。
     *
     * <p>用 “CompletableFuture + CAS 的 AtomicBoolean” 表达单次投递：
     * {@code deliver} 谁赢得竞态谁返回 true，后到者返回 false（→ 上层映射为 ALREADY_RESOLVED）。</p>
     */
    private static final class Waiter {

        final long tenantId;
        /** 空串表示“跳过用户校验” */
        final String userId;

        private final CompletableFuture<Decision> future = new CompletableFuture<>();
        private final AtomicBoolean delivered = new AtomicBoolean(false);
        /**
         * deliverLocal 需在不持锁的情况下读它。
         * 顺序上先置 resolved 再 complete，保证读到时决策一定已可见。
         */
        private final AtomicBoolean resolved = new AtomicBoolean(false);

        Waiter(long tenantId, String userId) {
            this.tenantId = tenantId;
            this.userId = userId == null ? "" : userId;
        }

        /** 赢得竞态返回 true 并投递 */
        boolean deliver(Decision d) {
            if (!delivered.compareAndSet(false, true)) {
                return false;
            }
            resolved.set(true);
            future.complete(d);
            return true;
        }

        /**
         * 等待决策。{@code timeout} 为 null 表示不限时。
         *
         * @return 决策；超时返回 null（调用方据此走超时/取消分支）
         */
        Decision await(Duration timeout) {
            try {
                if (timeout == null) {
                    return future.join();
                }
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApprovalException.internal("interrupted while waiting for tool approval", e);
            } catch (ExecutionException e) {
                throw ApprovalException.internal("tool approval wait failed", e.getCause());
            }
        }
    }
}
