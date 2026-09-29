package com.ragagent.knowledge.repository;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>为什么单独一个 bean：Spring 的自调用不走代理——仓储内部直接调自己的
 * {@code @Transactional} 方法注解不生效（与 {@code DataSourceTxTemplate} /
 * {@code MemoryTxTemplate} 同一族处置）。回调在同一个线程里执行，各 mapper 的
 */
@Component
public class ChunkTxTemplate {

    /**
     * 在事务里执行 {@code work}。
     *             {@code func(tx) error} 返回非 nil——如乐观锁冲突）
     */
    @Transactional
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
