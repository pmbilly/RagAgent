package com.ragagent.retrieval.engine;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.vectorstore.domain.VectorStore;

/**
 * 从 {@code VectorStore} 配置建引擎的函数口。
 *
 * <p>声明成函数式接口是为了打断装配 ↔ 服务实现的循环依赖：注册表
 * （{@link EngineRegistry}）只依赖本口，真实构造留在
 * {@link EngineFactory#createFromStore}。</p>
 *
 * <p>失败即抛 {@code RuntimeException}（{@link EngineFactory.EngineNotSupportedException} 等）。</p>
 */
@FunctionalInterface
public interface StoreEngineFactory {

    /**
     * 建一条引擎服务。失败直接抛——注册表会把失败折成
     * {@link RetrieveEngineException#VECTOR_STORE_UNAVAILABLE} 并进入重建冷却。
     */
    RetrieveEngineService build(VectorStore store);

    /**
     * 生产装配：把 {@link EngineFactory} 的静态工厂（含 SSRF 地址策略）适配成本口。
     * {@code guard} 为空 = 测试口（跳过地址校验，同 {@code createFromStore} 的既有约定）。
     */
    static StoreEngineFactory withGuard(SsrfGuard guard) {
        return store -> EngineFactory.createFromStore(store, guard);
    }
}
