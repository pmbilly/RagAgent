package com.ragagent.retrieval.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.FakeEngineService;
import com.ragagent.retrieval.engine.RetrievalEngineTestSupport.FakeOwnership;

/**
 * 工厂函数：{@code createForKb} / {@code createFromPayload} /
 * {@code verifyBinding} 的全部哨兵分支 + 取消/超时不被折成 store 判定。
 *
 * <p>「当前租户的有效引擎」显式传入（{@code null} = 未绑定租户），取消用
 * {@code CancellationException} 表达（与 {@code ImFormat.isCanceledOrDeadline} 同约定）。</p>
 */
class RetrieveEngineFactoriesTest {

    private static final String STORE_A = "store-A";

    /** 预置 byStoreID + byEngineType 两张表的注册表。 */
    private static EngineRegistry registry(List<FakeEngineService> stores,
                                           List<FakeEngineService> engineTypes) {
        EngineRegistry registry = new EngineRegistry(null, null);
        for (FakeEngineService svc : engineTypes) {
            registry.register(svc);
        }
        for (FakeEngineService svc : stores) {
            registry.registerWithStoreId(STORE_A, svc);
        }
        return registry;
    }

    private static EngineRegistry registryWithStore(String storeId, FakeEngineService svc) {
        EngineRegistry registry = new EngineRegistry(null, null);
        registry.registerWithStoreId(storeId, svc);
        return registry;
    }

    private static List<RetrieverEngineParams> params(String retrieverType, String engineType) {
        return List.of(new RetrieverEngineParams(retrieverType, engineType));
    }

    // ── CreateRetrieveEngineForKB ───────────────────────────────────────────

    @Nested
    @DisplayName("CreateRetrieveEngineForKB")
    class CreateForKb {

        @Test
        @DisplayName("无绑定（null 与空串）→ 用租户有效引擎，且不查归属")
        void unboundDoesNotTouchOwnership() {
            FakeEngineService postgres = new FakeEngineService("postgres", "keywords", "vector");
            EngineRegistry registry = registry(List.of(), List.of(postgres));
            FakeOwnership ownership = new FakeOwnership();
            List<RetrieverEngineParams> tenantEngines = params("vector", "postgres");

            for (String storeId : new String[] {null, ""}) {
                CompositeRetrieveEngine engine = RetrieveEngineFactories.createForKb(
                        registry, ownership, 1L, storeId, tenantEngines);
                assertNotNull(engine);
                assertEquals(1, engine.engineCount(), "无绑定路径用租户有效引擎");
                assertSame(postgres, engine.engineAt(0));
                assertEquals(0, ownership.callCount(), "无绑定路径不得查归属");
            }
        }

        @Test
        @DisplayName("无绑定且 ctx 无 TenantInfo → TENANT_INFO_MISSING")
        void unboundMissingTenant() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, null, null));
            assertSame(RetrieveEngineException.TENANT_INFO_MISSING, e);
        }

        @Test
        @DisplayName("有绑定 → 单引擎复合，承载该店支持的全部检索类型")
        void storeBound() {
            FakeEngineService es = new FakeEngineService("elasticsearch", "keywords", "vector");
            EngineRegistry registry = registryWithStore(STORE_A, es);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            CompositeRetrieveEngine engine = RetrieveEngineFactories.createForKb(
                    registry, ownership, 1L, STORE_A, null);

            assertNotNull(engine);
            assertEquals(1, engine.engineCount());
            assertSame(es, engine.engineAt(0));
            assertEquals(List.of("keywords", "vector"), engine.retrieverTypesAt(0),
                    "绑店的知识库使用该店支持的全部检索类型");
        }

        @Test
        @DisplayName("跨租户 → FORBIDDEN，且哨兵文案不含 store UUID")
        void crossTenant() {
            FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
            EngineRegistry registry = registryWithStore(STORE_A, es);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 2L); // store 属于租户 2，不是 1

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, STORE_A,
                            null));
            assertSame(RetrieveEngineException.VECTOR_STORE_FORBIDDEN, e);
            assertFalse(e.getMessage().contains(STORE_A), "哨兵不得向调用方暴露 store UUID");
        }

        @Test
        @DisplayName("归属为真但注册表没有 → NOT_FOUND（DB 有行、启动期建失败的形态）")
        void storeNotRegistered() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, STORE_A,
                            null));
            assertSame(RetrieveEngineException.VECTOR_STORE_NOT_FOUND, e);
        }

        @Test
        @DisplayName("归属查询基础设施故障 → UNAVAILABLE（可重试，不是永久 not-found）")
        void ownershipLookupError() {
            FakeEngineService pg = new FakeEngineService("postgres");
            EngineRegistry registry = registryWithStore(STORE_A, pg);
            FakeOwnership ownership = new FakeOwnership();
            ownership.error = new RuntimeException("db connection refused");

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, STORE_A,
                            null));
            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, e,
                    "数据库故障说明不了 store 存不存在，必须可重试");
            assertFalse(RetrieveEngineException.isKind(e,
                    RetrieveEngineException.Kind.VECTOR_STORE_NOT_FOUND),
                    "异步 worker 见 not-found 就停重试，等于丢任务");
        }
    }

    // ── CreateRetrieveEngineFromPayload ─────────────────────────────────────

    @Nested
    @DisplayName("CreateRetrieveEngineFromPayload（异步任务变体）")
    class CreateFromPayload {

        @Test
        @DisplayName("四种\"无绑定\"写法都回落载荷里的 effectiveEngines")
        void legacyUnbound() {
            FakeEngineService pg = new FakeEngineService("postgres", "vector");
            EngineRegistry registry = registry(List.of(), List.of(pg));
            FakeOwnership ownership = new FakeOwnership();
            List<RetrieverEngineParams> engines = params("vector", "postgres");

            // 载荷的四种"没有绑定"形态：字段缺失 / 显式 null / 空串 / 程序内 null
            for (String storeId : new String[] {null, "", null, null}) {
                CompositeRetrieveEngine engine = RetrieveEngineFactories.createFromPayload(
                        registry, ownership, 1L, engines, storeId);
                assertNotNull(engine);
                assertEquals(1, engine.engineCount());
                assertSame(pg, engine.engineAt(0));
                assertEquals(0, ownership.callCount(), "无绑定载荷不得触发归属查询");
            }
        }

        @Test
        @DisplayName("有绑定 → 走归属校验后的解析")
        void bound() {
            FakeEngineService qdrant = new FakeEngineService("qdrant", "vector");
            EngineRegistry registry = registryWithStore("qd-1", qdrant);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put("qd-1", 42L);

            CompositeRetrieveEngine engine = RetrieveEngineFactories.createFromPayload(
                    registry, ownership, 42L, List.of(), "qd-1");
            assertNotNull(engine);
            assertEquals(1, engine.engineCount());
            assertSame(qdrant, engine.engineAt(0));
        }

        @Test
        @DisplayName("载荷被篡改的跨租户 → FORBIDDEN")
        void tamperedCrossTenant() {
            FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
            EngineRegistry registry = registryWithStore(STORE_A, es);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 99L);

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createFromPayload(registry, ownership, 1L,
                            List.of(), STORE_A));
            assertSame(RetrieveEngineException.VECTOR_STORE_FORBIDDEN, e);
        }
    }

    // ── VerifyBinding ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("VerifyBinding")
    class VerifyBinding {

        @Test
        @DisplayName("归属基础设施错误原样返回")
        void infraErrorVerbatim() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();
            ownership.error = new RuntimeException("db boom");

            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> RetrieveEngineFactories.verifyBinding(registry, ownership, 1L, STORE_A));
            assertEquals("db boom", e.getMessage());
        }

        @Test
        @DisplayName("不属于该租户 → FORBIDDEN")
        void notOwned() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();

            assertSame(RetrieveEngineException.VECTOR_STORE_FORBIDDEN,
                    assertThrows(RetrieveEngineException.class, () -> RetrieveEngineFactories
                            .verifyBinding(registry, ownership, 1L, STORE_A)));
        }

        @Test
        @DisplayName("属于但未注册 → NOT_FOUND")
        void ownedButUnregistered() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            assertSame(RetrieveEngineException.VECTOR_STORE_NOT_FOUND,
                    assertThrows(RetrieveEngineException.class, () -> RetrieveEngineFactories
                            .verifyBinding(registry, ownership, 1L, STORE_A)));
        }

        @Test
        @DisplayName("属于且已注册 → 通过")
        void ownedAndRegistered() {
            FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
            EngineRegistry registry = registryWithStore(STORE_A, es);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            RetrieveEngineFactories.verifyBinding(registry, ownership, 1L, STORE_A);
        }

        @Test
        @DisplayName("跨租户报 FORBIDDEN 而不是 NOT_FOUND")
        void crossTenantIsForbiddenNotNotFound() {
            FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
            EngineRegistry registry = registryWithStore(STORE_A, es);
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 2L);

            assertSame(RetrieveEngineException.VECTOR_STORE_FORBIDDEN,
                    assertThrows(RetrieveEngineException.class, () -> RetrieveEngineFactories
                            .verifyBinding(registry, ownership, 1L, STORE_A)));
        }
    }

    // ── 取消 —— 取消不是"对 store 的判定" ──────────────────────────────────

    /** 对照 {@code cancellingRegistry}：查表仍 miss，但解析路径报"调用方放弃"。 */
    static class CancellingRegistry extends EngineRegistry {
        CancellingRegistry() {
            super(null, null);
        }

        @Override
        public RetrieveEngineService getOrLoadByStoreId(long tenantId, String storeId) {
            throw new CancellationException("context canceled");
        }
    }

    @Nested
    @DisplayName("取消/超时")
    class Cancellation {

        @Test
        @DisplayName("创建路径：取消原样到达调用方，不折成 NOT_FOUND")
        void cancellationIsNotReportedAsMissingStore() {
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            CancellationException e = assertThrows(CancellationException.class,
                    () -> RetrieveEngineFactories.createForKb(new CancellingRegistry(), ownership,
                            1L, STORE_A, null));
            assertFalse(RetrieveEngineException.isKind(e,
                    RetrieveEngineException.Kind.VECTOR_STORE_NOT_FOUND),
                    "异步处理把该哨兵映射成 SkipRetry 会丢任务");
            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("VerifyBinding：取消同样原样到达")
        void verifyBindingCancellation() {
            FakeOwnership ownership = new FakeOwnership();
            ownership.owned.put(STORE_A, 1L);

            assertThrows(CancellationException.class, () -> RetrieveEngineFactories.verifyBinding(
                    new CancellingRegistry(), ownership, 1L, STORE_A));
        }

        @Test
        @DisplayName("归属查询报取消 → 原样透传（既不是 NOT_FOUND 也不是 UNAVAILABLE）")
        void ownershipCancellationIsNotAStoreVerdict() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();
            ownership.error = new CancellationException("context canceled");

            CancellationException e = assertThrows(CancellationException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, STORE_A,
                            null));
            assertFalse(RetrieveEngineException.isKind(e,
                    RetrieveEngineException.Kind.VECTOR_STORE_NOT_FOUND));
            assertFalse(RetrieveEngineException.isKind(e,
                    RetrieveEngineException.Kind.VECTOR_STORE_UNAVAILABLE),
                    "取消不是对 store 的陈述");
        }

        @Test
        @DisplayName("归属查询超时 → UNAVAILABLE（可重试）")
        void ownershipOutageIsRetryable() {
            EngineRegistry registry = new EngineRegistry(null, null);
            FakeOwnership ownership = new FakeOwnership();
            ownership.error = new RuntimeException("connection refused");

            RetrieveEngineException e = assertThrows(RetrieveEngineException.class,
                    () -> RetrieveEngineFactories.createForKb(registry, ownership, 1L, STORE_A,
                            null));
            assertSame(RetrieveEngineException.VECTOR_STORE_UNAVAILABLE, e);
        }
    }

    // ── 并发 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("工厂函数无共享状态：16 线程并发调用全绿")
    void parallelInvocation() throws Exception {
        FakeEngineService es = new FakeEngineService("elasticsearch", "vector");
        EngineRegistry registry = registryWithStore(STORE_A, es);
        FakeOwnership ownership = new FakeOwnership();
        ownership.owned.put(STORE_A, 1L);

        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        for (int i = 0; i < callers; i++) {
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    CompositeRetrieveEngine engine = RetrieveEngineFactories.createForKb(
                            registry, ownership, 1L, STORE_A, null);
                    assertSame(es, engine.engineAt(0));
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发调用超时");
        assertEquals(null, firstError.get(), () -> "并发调用失败: " + firstError.get());
    }
}
