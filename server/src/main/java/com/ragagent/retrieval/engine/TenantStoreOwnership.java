package com.ragagent.retrieval.engine;

/**
 * 工厂函数用来校验"某个向量店 ID 属于某个租户"的查表口。
 *
 * <p>生产实现包 {@code VectorStoreRepository}（见 {@link VectorStoreRepoOwnership}）；
 * 测试注入内存假件，好把各条归属分支覆盖到而不碰数据库。</p>
 *
 * <p>基础设施故障以抛异常表达（仓储的 {@code getByID} 异常原样冒泡）。</p>
 */
@FunctionalInterface
public interface TenantStoreOwnership {

    /**
     * store 是否属于该租户。
     *
     * <p>store 不存在时返回 {@code false}（<b>不是</b>异常）；异常只留给
     * 数据库连不上这类基础设施故障。</p>
     */
    boolean storeOwnedBy(String storeId, long tenantId);
}
