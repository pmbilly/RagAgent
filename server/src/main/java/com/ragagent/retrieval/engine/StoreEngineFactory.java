package com.ragagent.retrieval.engine;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 从 {@code VectorStore} 配置建引擎的函数口——对照 Go {@code interfaces.EngineFactory}
 * （types/interfaces/vectorstore.go L22-24：
 * {@code type EngineFactory func(ctx context.Context, store types.VectorStore) (RetrieveEngineService, error)}）。
 *
 * <p>Go 声明成函数类型是为了打断 container ↔ service 的循环依赖；Java 用函数式接口
 * 达到同一目的：注册表（{@link EngineRegistry}）只依赖本口，真实构造留在
 * {@link EngineFactory#createFromStore}。</p>
 *
 * <p>Java 差异（备案）：Go 的 {@code ctx} 去掉；Go 的 {@code error} 返回值 → Java 失败即抛
 * {@code RuntimeException}（{@link EngineFactory.EngineNotSupportedException} 等）。</p>
 */
@FunctionalInterface
public interface StoreEngineFactory {

    /**
     * 建一条引擎服务。失败直接抛（对照 Go 的非 nil error）——注册表会把失败折成
     * {@link RetrieveEngineException#VECTOR_STORE_UNAVAILABLE} 并进入重建冷却。
     */
    RetrieveEngineService build(VectorStore store);

    /**
     * 生产装配：把 container 侧的静态工厂（含 SSRF 地址策略）适配成本口。
     * {@code guard} 为空 = 测试口（跳过地址校验，照 {@code createFromStore} 的既有约定）。
     */
    static StoreEngineFactory withGuard(SsrfGuard guard) {
        return store -> EngineFactory.createFromStore(store, guard);
    }
}
