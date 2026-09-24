package com.ragagent.retrieval.engine;

/**
 * 工厂函数用来校验"某个向量店 ID 属于某个租户"的查表口——对照 Go
 * {@code TenantStoreOwnership}（service/retriever/factory.go L44-56）。
 *
 * <p>生产实现包 {@code VectorStoreRepository}（见 {@link VectorStoreRepoOwnership}）；
 * 测试注入内存假件，好把各条归属分支覆盖到而不碰数据库。</p>
 *
 * <p>Java 差异（备案）：Go 的 {@code ctx} 去掉；Go 的 {@code (bool, error)} → Java 用
 * 返回 boolean + 抛异常表达基础设施故障（{@code GetByID} 的异常原样冒泡）。</p>
 */
@FunctionalInterface
public interface TenantStoreOwnership {

    /**
     * store 是否属于该租户。
     *
     * <p>store 不存在时返回 {@code false}（<b>不是</b>异常）——与 Go 一致：异常只留给
     * 数据库连不上这类基础设施故障。</p>
     */
    boolean storeOwnedBy(String storeId, long tenantId);
}
