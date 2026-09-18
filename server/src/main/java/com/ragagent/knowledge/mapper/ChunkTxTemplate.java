package com.ragagent.knowledge.mapper;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对照 Go {@code chunkRepository.SaveChunkRevision} 里的
 * {@code r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error { … })}。
 *
 * <p>为什么单独一个 bean：Spring 的自调用不走代理——仓储内部直接调自己的
 * {@code @Transactional} 方法注解不生效（与 {@code DataSourceTxTemplate} /
 * {@code MemoryTxTemplate} 同一族处置）。回调在同一个线程里执行，各 mapper 的
 * SqlSession 自动加入同一事务，等价于 Go 把 {@code tx} 传下去。</p>
 */
@Component
public class ChunkTxTemplate {

    /**
     * 在事务里执行 {@code work}。
     *
     * @param work 事务体；抛出 {@link RuntimeException} 即回滚（对应 Go 的
     *             {@code func(tx) error} 返回非 nil——如乐观锁冲突）
     */
    @Transactional
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
