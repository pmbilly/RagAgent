package com.ragagent.retrieval.engine;

import com.ragagent.vectorstore.domain.VectorStore;
import com.ragagent.vectorstore.mapper.VectorStoreRepository;

/**
 * {@link TenantStoreOwnership} 的生产实现——对照 Go
 * {@code vectorStoreRepoOwnership}（service/retriever/ownership.go，35 行）。
 *
 * <p>仓储的 {@code getByID} 自带租户范围（{@code WHERE id = ? AND tenant_id = ?}），
 * 所以"在该租户下存在"就等于"属于该租户"——返回行上不需要再比一次 tenant id。</p>
 */
public class VectorStoreRepoOwnership implements TenantStoreOwnership {

    private final VectorStoreRepository repo;

    public VectorStoreRepoOwnership(VectorStoreRepository repo) {
        this.repo = repo;
    }

    /**
     * 对照 {@code StoreOwnedBy}：store 非空即"属于"。仓储异常原样冒泡
     * （对应 Go 的 {@code return false, err}）。
     */
    @Override
    public boolean storeOwnedBy(String storeId, long tenantId) {
        VectorStore store = repo.getByID(tenantId, storeId);
        return store != null;
    }
}
