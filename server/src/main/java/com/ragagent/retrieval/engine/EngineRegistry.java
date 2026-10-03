package com.ragagent.retrieval.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.mapper.VectorStoreRepository;

/**
 * 检索引擎注册表——两张表 + 按需重建。
 *
 * <h2>两张表 + 按需重建</h2>
 * <ul>
 *   <li>{@code byEngineType}：{@code RETRIEVE_DRIVER} 注册的 env-store；</li>
 *   <li>{@code byStoreID}：{@code vector_stores} 表注册的 DB-store。注册表<b>是进程内的</b>：
 *       在别的实例上注册的引擎在本实例上就是缺的，启动期建失败的引擎重启后仍缺——
 *       按需重建让这两种情况不必靠运维重新发布就能自愈。</li>
 * </ul>
 *
 * <h2>重建路径的四道闸</h2>
 * <ol>
 *   <li><b>冷却</b>（{@code rebuildCooldown=30s}）：刚建失败的 store 不再花一次超时——
 *       否则后端持续宕机时每个请求都赔一次完整构建超时（singleflight 只帮并发调用者，
 *       串行调用者各自开新航班）。</li>
 *   <li><b>代数</b>（{@code storeGen}）：构建前采样，发布前复核；构建期间落地的注册/注销
 *       不会被这次构建"撤销"（否则已交给调用方的引擎被换掉、旧连接无人持有）。</li>
 *   <li><b>singleflight</b>：同一 store 的并发 miss 折叠成一次构建（构建要拨号，
 *       每请求一次就是把冷 store 变成连接风暴）；航班按 {@code tenantID:storeID} 分键，
 *       没有归属校验的调用者也无法蹭到别的租户的航班。</li>
 *   <li><b>panic 兜底</b>：构建里调第三方客户端构造函数，未兜住的异常/错误会带走整个进程
 *       而不是失败一个请求——折成可重试哨兵（且<b>不设冷却</b>）。</li>
 * </ol>
 *
 * <h2>实现说明</h2>
 * <ul>
 *   <li><b>无请求级取消</b>：构建恒为"共享航班"，等待者只等结果——即使发起方先离开，
 *       其余等待者也不受牵连；cancel/超时语义只保留在 {@code ENGINE_BUILD_TIMEOUT} 一处
 *       （用虚拟线程 + {@code CompletableFuture.get(timeout)} 实现，超时即取消构建线程）。</li>
 *   <li><b>两表用 {@code LinkedHashMap}</b>：{@code getAllRetrieveEngineServices}
 *       的返回顺序是确定的（注册序）。</li>
 *   <li>日志走 slf4j，带结构化字段。</li>
 * </ul>
 */
public class EngineRegistry implements RetrieveEngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    /** 单次按需构建的上界。 */
    public static final Duration ENGINE_BUILD_TIMEOUT = Duration.ofSeconds(10);

    /** 重建冷却时长。 */
    public static final Duration REBUILD_COOLDOWN = Duration.ofSeconds(30);

    private final Map<String, RetrieveEngineService> byEngineType = new LinkedHashMap<>();
    private final Map<String, RetrieveEngineService> byStoreId = new LinkedHashMap<>();
    private final Map<String, Long> storeGen = new HashMap<>();
    private final Map<String, Instant> failedUntil = new HashMap<>();
    private final ReadWriteLock mu = new ReentrantReadWriteLock();

    /** 两者任一为空即"不能重建"（按需加载退化为普通查表）。 */
    private final VectorStoreRepository repo;
    private final StoreEngineFactory factory;
    private final SingleFlight sf = new SingleFlight();
    private final Duration buildTimeout;
    private final Duration rebuildCooldown;

    /**
     * 测试口：调用方挂上航班后触发一次。测试需要它把
     * 「第二个调用方」排到正在跑的构建之后——没挂上之前外部无从观察，过早放行会让那个
     * 调用方错过航班、直接读到已完成的引擎（结果断言分辨不出来）。生产恒 {@code null}。
     */
    volatile Runnable onFlightJoin;

    /**
     * 测试口：报告本次调用是否与他人共享了构建。
     * 折叠是这条路径的全部意义，却不在结果里留痕：错过航班后读到已完成引擎的调用方，
     * 与真的等了航班的调用方，从结果上看一模一样。生产恒 {@code null}。
     */
    volatile Consumer<Boolean> flightObserver;

    public EngineRegistry(VectorStoreRepository repo, StoreEngineFactory factory) {
        this(repo, factory, ENGINE_BUILD_TIMEOUT, REBUILD_COOLDOWN);
    }

    /** 供测试注入超时/冷却（默认值同生产常量）。 */
    EngineRegistry(VectorStoreRepository repo, StoreEngineFactory factory, Duration buildTimeout,
                   Duration rebuildCooldown) {
        this.repo = repo;
        this.factory = factory;
        this.buildTimeout = buildTimeout;
        this.rebuildCooldown = rebuildCooldown;
    }

    // ── byEngineType（env-store，向后兼容路径） ──────────────────────────────

    @Override
    public void register(RetrieveEngineService service) {
        mu.writeLock().lock();
        try {
            if (byEngineType.containsKey(service.engineType())) {
                throw new RetrieveEngineException(
                        RetrieveEngineException.Kind.ENGINE_TYPE_ALREADY_REGISTERED,
                        "repository type " + service.engineType() + " already registered");
            }
            byEngineType.put(service.engineType(), service);
        } finally {
            mu.writeLock().unlock();
        }
    }

    @Override
    public RetrieveEngineService getRetrieveEngineService(String engineType) {
        mu.readLock().lock();
        try {
            RetrieveEngineService svc = byEngineType.get(engineType);
            if (svc == null) {
                throw new RetrieveEngineException(
                        RetrieveEngineException.Kind.ENGINE_TYPE_NOT_REGISTERED,
                        "repository of type " + engineType + " not found");
            }
            return svc;
        } finally {
            mu.readLock().unlock();
        }
    }

    @Override
    public List<RetrieveEngineService> getAllRetrieveEngineServices() {
        mu.readLock().lock();
        try {
            return new ArrayList<>(byEngineType.values());
        } finally {
            mu.readLock().unlock();
        }
    }

    // ── byStoreID（DB-store，实例化路径） ───────────────────────────────────

    @Override
    public void registerWithStoreId(String storeId, RetrieveEngineService service) {
        mu.writeLock().lock();
        try {
            byStoreId.put(storeId, service);
            // 与注销一样计数：先前启动的按需构建不得覆盖此处发布的条目——否则这里装上的
            // 引擎会被"孤儿化"（连接开着、无人持有）。
            bumpGenerationLocked(storeId);
        } finally {
            mu.writeLock().unlock();
        }
    }

    @Override
    public RetrieveEngineService getByStoreId(String storeId) {
        RetrieveEngineService svc = lookupByStoreIdOrNull(storeId);
        if (svc == null) {
            throw new RetrieveEngineException(
                    RetrieveEngineException.Kind.STORE_NOT_REGISTERED,
                    "store " + storeId + " not found in registry");
        }
        return svc;
    }

    private RetrieveEngineService lookupByStoreIdOrNull(String storeId) {
        mu.readLock().lock();
        try {
            return byStoreId.get(storeId);
        } finally {
            mu.readLock().unlock();
        }
    }

    @Override
    public void unregisterByStoreId(String storeId) {
        mu.writeLock().lock();
        try {
            byStoreId.remove(storeId);
            bumpGenerationLocked(storeId);
            // 让运维删掉 store 后立刻能重试，而不是干等上一份配置留下的冷却。
            failedUntil.remove(storeId);
        } finally {
            mu.writeLock().unlock();
        }
    }

    @Override
    public boolean canRebuildStores() {
        return repo != null && factory != null;
    }

    // ── 按需重建 ────────────────────────────────────────────────────────────

    @Override
    public RetrieveEngineService getOrLoadByStoreId(long tenantId, String storeId) {
        RetrieveEngineService cached = lookupByStoreIdOrNull(storeId);
        if (cached != null) {
            return cached;
        }
        if (repo == null || factory == null) {
            throw RetrieveEngineException.VECTOR_STORE_NOT_FOUND;
        }
        if (inFailureCooldown(storeId)) {
            // 刚建失败；先别再花一次超时。
            throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
        }
        // 构建开始前采样：此后落地的注销必须阻止这次构建的成果被发布。
        long gen = storeGeneration(storeId);
        // 与 tenant 一起做键：没做归属校验的调用者也蹭不到别的租户的航班。
        String key = tenantId + ":" + storeId;

        SingleFlight.Join<RetrieveEngineService> join =
                sf.doChan(key, () -> buildEngine(tenantId, storeId, gen), onFlightJoin);
        if (flightObserver != null) {
            flightObserver.accept(join.shared());
        }
        if (join.error() != null) {
            // 已是哨兵：构建自己记了原因，原始错误刻意不带回调用方。
            throw RetrieveEngineException.rethrow(join.error());
        }
        return join.value();
    }

    /** 在虚拟线程上跑构建并施加 {@code ENGINE_BUILD_TIMEOUT}。 */
    private RetrieveEngineService buildEngine(long tenantId, String storeId, long gen) {
        CompletableFuture<RetrieveEngineService> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("engine-build-" + storeId).start(() -> {
            try {
                future.complete(buildEngineInner(tenantId, storeId, gen));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(buildTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            markBuildFailed(storeId);
            throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted waiting for engine build");
        } catch (ExecutionException e) {
            throw RetrieveEngineException.rethrow(e.getCause());
        }
    }

    private RetrieveEngineService buildEngineInner(long tenantId, String storeId, long gen) {
        try {
            // 同键的更早一班航班可能在我们 miss 之后完成了。
            RetrieveEngineService published = lookupByStoreIdOrNull(storeId);
            if (published != null) {
                return published;
            }
            VectorStore store;
            try {
                store = repo.getByID(tenantId, storeId);
            } catch (RuntimeException e) {
                // store 很可能存在，只是元数据库答不上来。这里说"not found"会让异步 worker
                // 因为一次短暂故障丢掉任务。
                log.error("[retriever.registry] loading store {} for rebuild failed: {}",
                        storeId, e.toString());
                throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
            }
            if (store == null) {
                throw RetrieveEngineException.VECTOR_STORE_NOT_FOUND;
            }
            RetrieveEngineService svc;
            try {
                svc = factory.build(store);
            } catch (RuntimeException e) {
                // 原因止于这条日志：它带着后端端点，不能回流给调用方。
                log.error("[retriever.registry] rebuilding engine for store {} failed, "
                        + "retrying no sooner than {}: {}", storeId, rebuildCooldown, e.toString());
                markBuildFailed(storeId);
                throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
            }
            if (svc == null) {
                // 发布 null 会让此后每个读这张表的调用方都 NPE。
                log.error("[retriever.registry] engine factory returned no engine for store {}",
                        storeId);
                throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
            }
            if (!registerIfGenUnchanged(storeId, gen, svc)) {
                // 构建期间条目变了：这一条在发布前就过期了，落地的那个才是权威；调用方重试即取到。
                throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
            }
            return svc;
        } catch (Throwable surprise) {
            // 兜底：第三方客户端构造函数抛出的异常/错误不能带走整个进程
            // （常规构建失败路径已在上面各自兜住，这里接的是 Error 一类意外）；此路径**不设冷却**。
            if (surprise instanceof RetrieveEngineException sentinel) {
                throw sentinel;
            }
            log.error("[retriever.registry] engine build failed unexpectedly for store {}: {}",
                    storeId, surprise.toString());
            throw RetrieveEngineException.VECTOR_STORE_UNAVAILABLE;
        }
    }

    // ── 代数（storeGen）与冷却（failedUntil） ────────────────────────────────

    /** 读取 store 的当前代数。 */
    private long storeGeneration(String storeId) {
        mu.readLock().lock();
        try {
            Long gen = storeGen.get(storeId);
            return gen == null ? 0L : gen;
        } finally {
            mu.readLock().unlock();
        }
    }

    /**
     * 仅当条目自采样以来未被触碰时发布 svc。
     * 返回是否发布。
     */
    private boolean registerIfGenUnchanged(String storeId, long gen, RetrieveEngineService svc) {
        mu.writeLock().lock();
        try {
            long current = storeGen.get(storeId) == null ? 0L : storeGen.get(storeId);
            if (current != gen) {
                return false;
            }
            byStoreId.put(storeId, svc);
            failedUntil.remove(storeId);
            return true;
        } finally {
            mu.writeLock().unlock();
        }
    }

    /** 代数自增（调用方须持写锁）。 */
    private void bumpGenerationLocked(String storeId) {
        storeGen.merge(storeId, 1L, Long::sum);
    }

    /** 是否仍在建失败的冷却期内。 */
    private boolean inFailureCooldown(String storeId) {
        mu.readLock().lock();
        try {
            Instant until = failedUntil.get(storeId);
            return until != null && Instant.now().isBefore(until);
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 为建失败的 store 起冷却。 */
    private void markBuildFailed(String storeId) {
        mu.writeLock().lock();
        try {
            failedUntil.put(storeId, Instant.now().plus(rebuildCooldown));
        } finally {
            mu.writeLock().unlock();
        }
    }

    // ── singleflight（按 key 折叠并发构建） ─────────────────────────────────

    /**
     * 同一 key 的并发调用折叠成一次执行，等待者共享结果并标记 {@code shared=true}。
     */
    static final class SingleFlight {

        /** 一次航班的结果（值/错误/是否共享）。 */
        static final class Join<T> {
            private final T value;
            private final Throwable error;
            private final boolean shared;

            Join(T value, Throwable error, boolean shared) {
                this.value = value;
                this.error = error;
                this.shared = shared;
            }

            T value() {
                return value;
            }

            Throwable error() {
                return error;
            }

            boolean shared() {
                return shared;
            }
        }

        private final ConcurrentHashMap<String, Flight<?>> flights = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        <T> Join<T> doChan(String key, Supplier<T> build, Runnable onFlightJoin) {
            Flight<T> mine = new Flight<>();
            Flight<T> running = (Flight<T>) flights.putIfAbsent(key, mine);
            if (running != null) {
                return running.join(onFlightJoin);
            }
            // 挂上航班后立刻回调（leader 与等待者都在"已挂上"时触发）。
            if (onFlightJoin != null) {
                onFlightJoin.run();
            }
            T value = null;
            Throwable failure = null;
            try {
                value = build.get();
            } catch (Throwable t) {
                failure = t;
            } finally {
                // 先摘掉航班再交付结果——此后到达的调用者开新航班。
                flights.remove(key, mine);
            }
            boolean shared = mine.waiters.get() > 0;
            mine.publish(value, failure);
            return new Join<>(value, failure, shared);
        }

        private static final class Flight<T> {
            private final CountDownLatch done = new CountDownLatch(1);
            private final AtomicInteger waiters = new AtomicInteger();
            private volatile T value;
            private volatile Throwable failure;

            Join<T> join(Runnable onFlightJoin) {
                waiters.incrementAndGet();
                if (onFlightJoin != null) {
                    onFlightJoin.run();
                }
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("interrupted waiting for engine build");
                }
                return new Join<>(value, failure, true);
            }

            void publish(T value, Throwable failure) {
                this.value = value;
                this.failure = failure;
                done.countDown();
            }
        }
    }
}
