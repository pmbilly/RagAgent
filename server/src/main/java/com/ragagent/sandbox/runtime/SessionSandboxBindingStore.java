package com.ragagent.sandbox.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话→沙箱绑定外部状态（对照 Go internal/sandbox/session_binding.go 全文）。
 *
 * <p>绑定是权威外部状态：生产走 Redis（{@link SessionSandboxBindingRedis}），
 * 测试与单进程部署走内存实现。实现必须提供 create-if-absent 与
 * compare-delete 语义。Redis 的键名与 JSON 序列化形态是<b>跨语言互操作契约</b>
 * （Go 与 Java 两个实现读写同一批键），逐字对照见 Redis 实现的注释。</p>
 *
 * <p>Go 的 {@code WithLifecycleLock(ctx, key, fn)} 在 Java 显式化等待预算：
 * {@code lockWaitTimeout} 为 null/零表示一直等到获得（对应 Go 的无上限 ctx），
 * 非零对应调用方包一层 2s 计时的场景（{@code bindingInvalidateLockTimeout}）。
 * 锁内回调拿到的"ownership context"（Go 的 WithoutCancel 分支）在 Java 不可表达，
 * 调用方在锁内做清理时只能尽力而为——见 RemoteSessionLifecycle.cleanupSandboxID
 * 的注释。</p>
 */
public interface SessionSandboxBindingStore {

    /** 返回当前绑定；会话未绑定时返回 null（对照 nil）。 */
    SessionSandboxBinding get(SessionSandboxKey key);

    /** 仅当 key 未绑定时写入一个通过校验的当前版本绑定；已存在返回 false。 */
    boolean create(SessionSandboxKey key, SessionSandboxBinding binding);

    /** 只删除 provider 与 sandboxId 都匹配的绑定。 */
    boolean deleteIfMatch(SessionSandboxKey key, String provider, String sandboxId);

    /**
     * 只在绑定仍指向 expected 的 provider/sandbox 时改写 traffic token；只 patch
     * 这一个字段，并发 stale 标记不会被整体覆盖抹掉。空 token 是 no-op。
     */
    boolean replaceTrafficTokenIfMatch(SessionSandboxKey key, SessionSandboxBinding expected,
            String token);

    /**
     * 在 key 的分布式生命周期锁内执行 action，串行化 create/recover/replace/delete。
     * lockWaitTimeout 见类注释。
     */
    <T> T withLifecycleLock(SessionSandboxKey key, Duration lockWaitTimeout,
            LifecycleAction<T> action);

    /** 把某工作区一个沙箱配置的全部绑定标记 stale，返回标记数量。 */
    int invalidateByConfig(long tenantId, String configId);

    /** 对照 Go 的 {@code func(ctx) error} 生命周期锁回调。 */
    @FunctionalInterface
    interface LifecycleAction<T> {
        T run();
    }

    // ── SessionSandboxKey（对照 L16-36） ─────────────────────────────────

    record SessionSandboxKey(long tenantId, String sessionId) {

        /** 拒绝无法标识租户会话的键。 */
        public void validate() {
            if (tenantId == 0 || sessionId == null || sessionId.strip().isEmpty()) {
                throw new IllegalArgumentException("sandbox binding requires tenant and session");
            }
            if (sessionId.indexOf('{') >= 0 || sessionId.indexOf('}') >= 0) {
                throw new IllegalArgumentException("sandbox binding session must not contain braces");
            }
            for (int i = 0; i < sessionId.length(); i++) {
                if (Character.isISOControl(sessionId.charAt(i))) {
                    throw new IllegalArgumentException(
                            "sandbox binding session must not contain control characters");
                }
            }
        }
    }

    // ── SessionSandboxBinding ─────────────────────────────────────────────
    // 绑定本体已提升为同包顶层类型 com.ragagent.sandbox.runtime.SessionSandboxBinding
    // （RemoteSessionLifecycle / SessionBoundManager 按简单名引用它）。字段与
    // JSON 契约逐字未动，见该文件。

    // ── 可选的 chat-turn 租约（对照 sessionTurnLeaseStore L141-149） ──────

    /**
     * 绑定存储的 turn-lease 半边。看到 stale 绑定的 resolve 会咨询它，让
     * 进行中的 chat turn 保住沙箱，只有下一 turn 的首次 resolve 重建。
     */
    interface SessionTurnLeaseStore {

        /** 首次增量（refs 0→1）允许下一次 resolve 重建 stale 沙箱。 */
        void beginTurn(SessionSandboxKey key);

        /** 最后一次释放撤销租约，此后的 resolve 可立即重建 stale 沙箱。 */
        void endTurn(SessionSandboxKey key);

        /** turn 是否开着、其首次 resolve 是否还能重建。 */
        TurnLeaseState turnState(SessionSandboxKey key);

        /** 花掉当前 turn 允许的那一次重建。 */
        void consumeTurnRebuild(SessionSandboxKey key);
    }

    /** （active, rebuildOnce）二元组。 */
    record TurnLeaseState(boolean active, boolean rebuildOnce) {
    }

    // ── store 专属的 InvalidateByConfig 半边（对照 tenantBindingScanner） ──

    /**
     * 列出 store 持有的某租户全部绑定键。标记前就消失的绑定是预期而非错误；
     * markBindingStale 只在绑定仍指向 expected 的 provider/sandbox 时写 stale_at。
     */
    interface TenantBindingScanner extends SessionSandboxBindingStore {

        List<SessionSandboxKey> listTenantBindingKeys(long tenantId);

        boolean markBindingStale(SessionSandboxKey key, SessionSandboxBinding expected,
                Instant staleAt);
    }

    /** Go 的 errors.Join 形态：既有标记计数又有失败集合，用异常携带。 */
    final class BindingInvalidationException extends RuntimeException {
        public final int marked;
        public final List<String> failures;

        public BindingInvalidationException(int marked, List<String> failures) {
            super(String.join("\n", failures));
            this.marked = marked;
            this.failures = failures;
        }
    }

    /**
     * 对照 invalidateBindingsByConfig（L199-260）：在该会话的生命周期锁内逐会话标记。
     * 锁让标记可靠——resolve 在锁内读绑定、决定替换并重绑，锁外标记会悄悄输给并发的
     * resolve。标记只增字段，从不删绑定，故并发的 resolve 最多丢一个标记、绝不会丢沙箱。
     * 一个会话标记失败不阻止其余会话（镜像指针此刻已移动）；失败随异常上报。
     */
    static int invalidateBindingsByConfig(
            SessionSandboxBindingStore store, long tenantId, String configId,
            int lockWaitSeconds) {
        if (tenantId == 0) {
            throw new IllegalArgumentException("sandbox binding invalidation requires a tenant");
        }
        if (!(store instanceof TenantBindingScanner scanner)) {
            throw new IllegalStateException(
                    "sandbox binding invalidation requires a TenantBindingScanner store");
        }
        List<SessionSandboxKey> keys = scanner.listTenantBindingKeys(tenantId);

        String wanted = RemoteSessionLifecycle.normalizeConfigId(configId);
        int[] marked = {0};
        List<String> failures = new ArrayList<>();
        for (SessionSandboxKey key : keys) {
            try {
                scanner.withLifecycleLock(key, Duration.ofSeconds(lockWaitSeconds), () -> {
                    SessionSandboxBinding binding = scanner.get(key);
                    if (binding == null) {
                        return null;
                    }
                    if (!java.util.Objects.equals(binding.configId, wanted)
                            || binding.staleAt != null) {
                        return null;
                    }
                    boolean wrote = scanner.markBindingStale(key, binding, Instant.now());
                    if (wrote) {
                        marked[0]++;
                    }
                    return null;
                });
            } catch (RuntimeException err) {
                failures.add(String.format("mark session %s stale: %s", key.sessionId(), err));
            }
        }
        if (!failures.isEmpty()) {
            throw new BindingInvalidationException(marked[0], failures);
        }
        return marked[0];
    }

    // ── 共享校验（对照 validateBindingMatch / isRemoteProvider） ──────────

    static void validateBindingMatch(SessionSandboxKey key, String provider, String sandboxId) {
        key.validate();
        if (!SandboxTypes.isNamedSandboxBackendType(provider)) {
            throw new IllegalArgumentException(String.format(
                    "unsupported sandbox binding provider \"%s\"", provider));
        }
        if (sandboxId == null || sandboxId.strip().isEmpty()) {
            throw new IllegalArgumentException("sandbox binding match requires sandbox ID");
        }
    }

    // ── 内存实现（对照 MemorySessionSandboxBindingStore L280-509） ────────

    /**
     * 进程内实现，用于测试与显式配置的单进程部署（= Go Lite 模式的默认）。
     * 生命周期锁用每键 ReentrantLock；等待预算用 tryLock(deadline) 近似 Go 的
     * ctx 取消（线程中断同样能提前放弃等待）。
     */
    final class MemorySessionSandboxBindingStore implements SessionSandboxBindingStore,
            SessionSandboxBindingStore.TenantBindingScanner,
            SessionSandboxBindingStore.SessionTurnLeaseStore {

        private static final class LifecycleLock {
            final ReentrantLock lock = new ReentrantLock(true);
            int users;
        }

        private static final class TurnLease {
            int refs;
            boolean rebuildOnce;
        }

        private final Object mu = new Object();
        private final Map<SessionSandboxKey, SessionSandboxBinding> bindings = new LinkedHashMap<>();
        private final Map<SessionSandboxKey, LifecycleLock> locks = new HashMap<>();
        private final Map<SessionSandboxKey, TurnLease> turns = new HashMap<>();

        @Override
        public SessionSandboxBinding get(SessionSandboxKey key) {
            key.validate();
            synchronized (mu) {
                SessionSandboxBinding binding = bindings.get(key);
                return binding == null ? null : binding.copy();
            }
        }

        @Override
        public boolean create(SessionSandboxKey key, SessionSandboxBinding binding) {
            binding.validate(key);
            synchronized (mu) {
                if (bindings.containsKey(key)) {
                    return false;
                }
                bindings.put(key, binding.copy());
                return true;
            }
        }

        @Override
        public boolean deleteIfMatch(SessionSandboxKey key, String provider, String sandboxId) {
            SessionSandboxBindingStore.validateBindingMatch(key, provider, sandboxId);
            synchronized (mu) {
                SessionSandboxBinding binding = bindings.get(key);
                if (binding == null || !java.util.Objects.equals(binding.provider, provider)
                        || !java.util.Objects.equals(binding.sandboxId, sandboxId)) {
                    return false;
                }
                bindings.remove(key);
                return true;
            }
        }

        @Override
        public boolean replaceTrafficTokenIfMatch(SessionSandboxKey key,
                SessionSandboxBinding expected, String token) {
            SessionSandboxBindingStore.validateBindingMatch(key, expected.provider, expected.sandboxId);
            if (token == null || token.isEmpty()) {
                return false;
            }
            synchronized (mu) {
                SessionSandboxBinding binding = bindings.get(key);
                if (binding == null || !java.util.Objects.equals(binding.provider, expected.provider)
                        || !java.util.Objects.equals(binding.sandboxId, expected.sandboxId)) {
                    return false;
                }
                if (token.equals(binding.trafficAccessToken)) {
                    return false;
                }
                binding.trafficAccessToken = token;
                return true;
            }
        }

        @Override
        public <T> T withLifecycleLock(SessionSandboxKey key, Duration lockWaitTimeout,
                LifecycleAction<T> action) {
            key.validate();
            if (action == null) {
                throw new IllegalArgumentException("sandbox lifecycle lock callback is required");
            }
            LifecycleLock lock;
            synchronized (mu) {
                lock = locks.computeIfAbsent(key, k -> new LifecycleLock());
                lock.users++;
            }
            boolean held = false;
            try {
                if (lockWaitTimeout == null || lockWaitTimeout.isZero() || lockWaitTimeout.isNegative()) {
                    lock.lock.lockInterruptibly();
                    held = true;
                } else {
                    held = lock.lock.tryLock(lockWaitTimeout.toMillis(),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!held) {
                        throw SandboxException.internal(
                                "sandbox lifecycle lock wait exceeded " + lockWaitTimeout);
                    }
                }
                return action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw SandboxException.internal("interrupted while waiting for sandbox lifecycle lock", e);
            } finally {
                if (held) {
                    lock.lock.unlock();
                }
                synchronized (mu) {
                    lock.users--;
                    if (lock.users == 0) {
                        locks.remove(key);
                    }
                }
            }
        }

        @Override
        public int invalidateByConfig(long tenantId, String configId) {
            // 2s = bindingInvalidateLockTimeout：单个会话的锁等待上限
            return SessionSandboxBindingStore.invalidateBindingsByConfig(this, tenantId, configId, 2);
        }

        @Override
        public List<SessionSandboxKey> listTenantBindingKeys(long tenantId) {
            synchronized (mu) {
                List<SessionSandboxKey> keys = new ArrayList<>();
                for (SessionSandboxKey key : bindings.keySet()) {
                    if (key.tenantId() == tenantId) {
                        keys.add(key);
                    }
                }
                return keys;
            }
        }

        @Override
        public boolean markBindingStale(SessionSandboxKey key, SessionSandboxBinding expected,
                Instant staleAt) {
            synchronized (mu) {
                SessionSandboxBinding binding = bindings.get(key);
                if (binding == null || !java.util.Objects.equals(binding.provider, expected.provider)
                        || !java.util.Objects.equals(binding.sandboxId, expected.sandboxId)) {
                    return false;
                }
                binding.staleAt = staleAt;
                return true;
            }
        }

        // ---- turn lease ----

        @Override
        public void beginTurn(SessionSandboxKey key) {
            key.validate();
            synchronized (mu) {
                TurnLease lease = turns.computeIfAbsent(key, k -> new TurnLease());
                if (lease.refs == 0) {
                    lease.rebuildOnce = true;
                }
                lease.refs++;
            }
        }

        @Override
        public void endTurn(SessionSandboxKey key) {
            key.validate();
            synchronized (mu) {
                TurnLease lease = turns.get(key);
                if (lease == null) {
                    return;
                }
                lease.refs--;
                if (lease.refs <= 0) {
                    turns.remove(key);
                }
            }
        }

        @Override
        public TurnLeaseState turnState(SessionSandboxKey key) {
            key.validate();
            synchronized (mu) {
                TurnLease lease = turns.get(key);
                if (lease == null || lease.refs <= 0) {
                    return new TurnLeaseState(false, false);
                }
                return new TurnLeaseState(true, lease.rebuildOnce);
            }
        }

        @Override
        public void consumeTurnRebuild(SessionSandboxKey key) {
            key.validate();
            synchronized (mu) {
                TurnLease lease = turns.get(key);
                if (lease != null) {
                    lease.rebuildOnce = false;
                }
            }
        }
    }

    /** 便捷工厂：对照 NewMemorySessionSandboxBindingStore。 */
    static MemorySessionSandboxBindingStore memoryStore() {
        return new MemorySessionSandboxBindingStore();
    }
}
