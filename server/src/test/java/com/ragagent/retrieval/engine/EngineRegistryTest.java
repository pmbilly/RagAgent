package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.FakeEngineService;
import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.FakeStoreRepo;

/**
 * 注册表（W5γ4.5）对照 Go {@code service/retriever/registry_test.go}（237 行）+
 * {@code registry_rehydrate_test.go}（410 行）：两张表、双表隔离、并发安全，
 * 以及按需重建的六道语义（折叠单次构建、失败冷却、代数、panic 兜底、
 * 数据库故障 vs store 不存在、缺依赖时的降级）。
 *
 * <p>与 Go 的差异：Go 的「leader 取消不毒化等待者」用到 {@code context.WithoutCancel}
 * 与 {@code DoChan} 的 select 语义；本仓无请求级取消（构建恒为共享航班），因此改以
 * 「等待者确实加入同一次构建（{@code shared=true}，工厂只被调用一次）」验证同一意图。</p>
 */
class EngineRegistryTest {

    private static final String STORE_ID = "00000000-0000-0000-0000-0000000000aa";

    private static FakeEngineService engine(String type, String... support) {
        return new FakeEngineService(type, support);
    }

    // ── 两张表的基础语义 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Register / Get（byEngineType）")
    class ByEngineType {

        @Test
        @DisplayName("注册成功；重复注册报错")
        void registerAndDuplicate() {
            EngineRegistry registry = new EngineRegistry(null, null);
            registry.register(engine("postgres"));

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> registry.register(engine("postgres")));
            assertTrue(e.getMessage().contains("already registered"));
        }

        @Test
        @DisplayName("按类型取；未注册报 not found")
        void getService() {
            EngineRegistry registry = new EngineRegistry(null, null);
            registry.register(engine("postgres"));

            assertNotNull(registry.getRetrieveEngineService("postgres"));
            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> registry.getRetrieveEngineService("qdrant"));
            assertTrue(e.getMessage().contains("not found"));
        }

        @Test
        @DisplayName("getAll 只返回 byEngineType；改动返回值不影响注册表")
        void getAllReturnsCopy() {
            EngineRegistry registry = new EngineRegistry(null, null);
            registry.register(engine("postgres"));
            registry.register(engine("elasticsearch"));

            List<RetrieveEngineService> all = registry.getAllRetrieveEngineServices();
            assertEquals(2, all.size());
            all.add(engine("qdrant"));
            assertEquals(2, registry.getAllRetrieveEngineServices().size());
        }
    }

    @Nested
    @DisplayName("RegisterWithStoreID / GetByStoreID / Unregister（byStoreID）")
    class ByStoreId {

        @Test
        @DisplayName("upsert 覆盖；同一类型不同 store 各自成条")
        void upsertAndMultiInstance() {
            EngineRegistry registry = new EngineRegistry(null, null);
            registry.registerWithStoreId("store-1", engine("postgres"));
            assertNotNull(registry.getByStoreId("store-1"));

            registry.registerWithStoreId("store-1", engine("elasticsearch"));
            assertEquals("elasticsearch", registry.getByStoreId("store-1").engineType());

            registry.registerWithStoreId("es-hot", engine("elasticsearch"));
            registry.registerWithStoreId("es-warm", engine("elasticsearch"));
            assertNotSame(registry.getByStoreId("es-hot"), registry.getByStoreId("es-warm"));
        }

        @Test
        @DisplayName("未注册报 not found；注销幂等")
        void notFoundAndIdempotentUnregister() {
            EngineRegistry registry = new EngineRegistry(null, null);
            assertThrows(RetrieveEngineException.class, () -> registry.getByStoreId("nope"));

            registry.registerWithStoreId("store-1", engine("postgres"));
            registry.unregisterByStoreId("store-1");
            assertThrows(RetrieveEngineException.class, () -> registry.getByStoreId("store-1"));
            registry.unregisterByStoreId("store-1"); // 不抛
        }

        @Test
        @DisplayName("双表隔离：byStoreID 不影响 byEngineType 的查找与注销")
        void dualMapIsolation() {
            EngineRegistry registry = new EngineRegistry(null, null);
            registry.register(engine("postgres"));
            registry.registerWithStoreId("store-pg", engine("postgres"));
            registry.registerWithStoreId("store-es", engine("elasticsearch"));

            assertEquals(1, registry.getAllRetrieveEngineServices().size());
            assertThrows(RetrieveEngineException.class,
                    () -> registry.getRetrieveEngineService("elasticsearch"));

            registry.unregisterByStoreId("store-pg");
            assertNotNull(registry.getRetrieveEngineService("postgres"));
        }

        @Test
        @DisplayName("并发读写注销不炸")
        void concurrentAccess() throws Exception {
            EngineRegistry registry = new EngineRegistry(null, null);
            int workers = 10;
            CountDownLatch done = new CountDownLatch(workers * 3);
            for (int i = 0; i < workers; i++) {
                String storeId = "store-" + i;
                Thread.ofVirtual().start(() -> {
                    registry.registerWithStoreId(storeId, engine("postgres"));
                    done.countDown();
                });
                Thread.ofVirtual().start(() -> {
                    try {
                        registry.getByStoreId(storeId);
                    } catch (RuntimeException ignored) {
                        // miss 是预期分支之一
                    }
                    done.countDown();
                });
                Thread.ofVirtual().start(() -> {
                    registry.unregisterByStoreId(storeId);
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }
    }

    // ── 按需重建（GetOrLoadByStoreID） ──────────────────────────────────────

    @Nested
    @DisplayName("GetOrLoadByStoreID（按需重建）")
    class Rehydrate {

        /** 对照 {@code blockingFactory}：构建会阻塞（模拟拨号），可被逐个观测。 */
        private static final class BlockingFactory implements StoreEngineFactory {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public RetrieveEngineService build(com.ragagent.vectorstore.domain.VectorStore store) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the build");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return engine("elasticsearch");
            }
        }

        private EngineRegistry registryWith(StoreEngineFactory factory) {
            return new EngineRegistry(new FakeStoreRepo(
                    RetrievalEngineTestSupport.store(STORE_ID)), factory);
        }

        @Test
        @DisplayName("并发 miss 只构建一次，所有调用方拿到同一个引擎（等待者共享航班）")
        void concurrentMissesBuildOnce() throws Exception {
            BlockingFactory factory = new BlockingFactory();
            EngineRegistry registry = registryWith(factory);
            List<Boolean> sharedFlags = new CopyOnWriteArrayList<>();
            registry.flightObserver = sharedFlags::add;

            int callers = 16;
            CountDownLatch attached = new CountDownLatch(callers);
            registry.onFlightJoin = attached::countDown;
            CountDownLatch done = new CountDownLatch(callers);
            List<RetrieveEngineService> services = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> firstError = new AtomicReference<>();

            for (int i = 0; i < callers; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        services.add(registry.getOrLoadByStoreId(1L, STORE_ID));
                    } catch (Throwable e) {
                        firstError.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(factory.entered.await(10, TimeUnit.SECONDS), "构建应已开始");
            assertTrue(attached.await(10, TimeUnit.SECONDS), "16 个调用方都应挂上航班");
            factory.release.countDown();

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(null, firstError.get(), () -> "调用失败: " + firstError.get());
            assertEquals(1, factory.calls.get(), "整个突发只构建一次");
            assertEquals(callers, services.size());
            for (RetrieveEngineService svc : services) {
                assertSame(services.get(0), svc, "每个调用方拿到同一个引擎");
            }
            assertTrue(sharedFlags.stream().anyMatch(Boolean::booleanValue),
                    "必须有人确实等了别人的构建（折叠是这条路径的全部意义）");
            assertNotNull(registry.getByStoreId(STORE_ID), "构建完成后引擎应已注册");
        }

        @Test
        @DisplayName("构建失败进冷却：第二次请求由冷却兜住；注销清冷却")
        void failedBuildEntersCooldown() {
            AtomicInteger calls = new AtomicInteger();
            EngineRegistry registry = registryWith(store -> {
                calls.incrementAndGet();
                throw new RuntimeException("backend down");
            });

            RetrieveEngineException first = assertThrows(RetrieveEngineException.class,
                    () -> registry.getOrLoadByStoreId(1L, STORE_ID));
            RetrieveEngineException second = assertThrows(RetrieveEngineException.class,
                    () -> registry.getOrLoadByStoreId(1L, STORE_ID));

            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, first);
            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, second,
                    "原始构建错误不得越过哨兵");
            assertEquals(1, calls.get(), "第二次由冷却兜住，不开新构建");

            registry.unregisterByStoreId(STORE_ID);
            assertThrows(RetrieveEngineException.class,
                    () -> registry.getOrLoadByStoreId(1L, STORE_ID));
            assertEquals(2, calls.get(), "注销必须清掉冷却");
        }

        @Test
        @DisplayName("构建\"炸了\"（Error）折成可重试哨兵，且不留残留")
        void factoryPanicDoesNotCrashProcess() {
            EngineRegistry registry = registryWith(store -> {
                throw new AssertionError("client constructor exploded");
            });

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> registry.getOrLoadByStoreId(1L, STORE_ID));
            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, e,
                    "炸掉的构建报成可重试");
            assertThrows(RetrieveEngineException.class, () -> registry.getByStoreId(STORE_ID),
                    "炸掉的构建不得留下任何注册");
        }

        @Test
        @DisplayName("构建期间被注销：不得复活")
        void unregisterDuringBuildIsNotUndone() throws Exception {
            BlockingFactory factory = new BlockingFactory();
            EngineRegistry registry = registryWith(factory);
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            Thread.ofVirtual().start(() -> {
                try {
                    registry.getOrLoadByStoreId(1L, STORE_ID);
                } catch (Throwable e) {
                    error.set(e);
                } finally {
                    done.countDown();
                }
            });

            assertTrue(factory.entered.await(10, TimeUnit.SECONDS));
            registry.unregisterByStoreId(STORE_ID); // 注销落在构建中途
            factory.release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));

            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, error.get(),
                    "被注销追上的构建不得算成功");
            assertThrows(RetrieveEngineException.class, () -> registry.getByStoreId(STORE_ID),
                    "已注销的 store 不得回到注册表");
        }

        @Test
        @DisplayName("构建不得覆盖并发的注册（代数）")
        void buildDoesNotOverwriteConcurrentRegistration() throws Exception {
            BlockingFactory factory = new BlockingFactory();
            EngineRegistry registry = registryWith(factory);
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try {
                    registry.getOrLoadByStoreId(1L, STORE_ID);
                } catch (RuntimeException ignored) {
                    // 本用例只关心最终在线的那条
                } finally {
                    done.countDown();
                }
            });

            assertTrue(factory.entered.await(10, TimeUnit.SECONDS));
            FakeEngineService registered = engine("postgres");
            registry.registerWithStoreId(STORE_ID, registered);
            factory.release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));

            assertSame(registered, registry.getByStoreId(STORE_ID),
                    "注册发布的引擎必须在构建结束后仍然在线");
        }

        @Test
        @DisplayName("缺 repo/factory → 退化为普通查表")
        void withoutRepoOrFactoryIsPlainLookup() {
            EngineRegistry registry = new EngineRegistry(null, null);

            assertSame(RetrieveEngineException.VECTOR_STORE_NOT_FOUND,
                    assertThrows(RetrieveEngineException.class,
                            () -> registry.getOrLoadByStoreId(1L, STORE_ID)));

            FakeEngineService registered = engine("elasticsearch");
            registry.registerWithStoreId(STORE_ID, registered);
            assertSame(registered, registry.getOrLoadByStoreId(1L, STORE_ID),
                    "命中不得去查数据库");
            assertFalse(registry.canRebuildStores());
        }

        @Test
        @DisplayName("数据库故障可重试；store 确实不存在才永久")
        void databaseFailureIsRetryableAndAbsentIsNotFound() {
            FakeStoreRepo failing = new FakeStoreRepo(null);
            failing.error = new RuntimeException("connection refused");
            EngineRegistry broken = new EngineRegistry(failing, store -> {
                throw new AssertionError("store 加载失败时不得构建引擎");
            });

            RetrieveEngineException unavailable = assertThrows(RetrieveEngineException.class,
                    () -> broken.getOrLoadByStoreId(1L, STORE_ID));
            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, unavailable,
                    "停机是可重试的");
            assertFalse(RetrieveEngineException.isKind(unavailable,
                    RetrieveEngineException.Kind.VECTOR_STORE_NOT_FOUND),
                    "异步 worker 见 not-found 就停重试");

            EngineRegistry absent = new EngineRegistry(
                    new FakeStoreRepo(null), store -> {
                        throw new AssertionError("store 不存在时不得构建引擎");
                    });
            RetrieveEngineException notFound = assertThrows(RetrieveEngineException.class,
                    () -> absent.getOrLoadByStoreId(1L, STORE_ID));
            assertSame(RetrieveEngineException.VECTOR_STORE_NOT_FOUND, notFound);
        }

        @Test
        @DisplayName("构建超时 → UNAVAILABLE（对照 EngineBuildTimeout）")
        void buildTimeoutIsRetryable() {
            EngineRegistry registry = new EngineRegistry(
                    new FakeStoreRepo(RetrievalEngineTestSupport.store(STORE_ID)),
                    store -> {
                        try {
                            Thread.sleep(2_000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return engine("elasticsearch");
                    },
                    Duration.ofMillis(50), Duration.ofSeconds(30));

            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE,
                    assertThrows(RetrieveEngineException.class,
                            () -> registry.getOrLoadByStoreId(1L, STORE_ID)));
        }
    }
}
