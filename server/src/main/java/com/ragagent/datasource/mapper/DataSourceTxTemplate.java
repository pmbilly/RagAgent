package com.ragagent.datasource.mapper;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务模板：仓储里的多步写（{@code Create} 的插入 + 回写 {@code sync_deletions}、
 * {@code update} 的多列更新 + 回写 {@code sync_deletions}）都要包在同一个事务里。
 *
 * <h2>为什么单独一个 bean 而不是仓储的私有方法</h2>
 * <p><b>Spring 的自调用不走代理</b>：仓储内部直接调自己的
 * {@code @Transactional} 方法，注解根本不生效——两步写会跑在各自独立的自动提交事务里。
 * 对 {@code Create} 来说后果是"插入成功但 {@code sync_deletions} 回写失败"会留下
 * 一行半成品；对 {@code update} 是同样的部分更新。所以提成独立 bean，
 * 让调用穿过代理。</p>
 *
 * <p>这与 memory 模块的 {@code MemoryTxTemplate} 是同一处置（那里为了 {@code FOR UPDATE}
 * 的行锁，这里为了"两步写要么都成要么都不成"）。</p>
 *
 * <h2>回调里为什么能直接用别的 mapper</h2>
 * <p>Spring 的事务是**线程绑定**的：回调在同一个线程里执行，各 mapper 的 SqlSession
 * 会加入同一个事务（{@code DataSourceTransactionManager} + MyBatis 的
 * {@code SpringManagedTransaction}）。</p>
 */
@Component
public class DataSourceTxTemplate {

    /**
     * 在事务里执行 {@code work}。
     *
     * @param work 事务体；抛出 {@link RuntimeException} 即回滚
     */
    @Transactional
    public void inTransaction(Runnable work) {
        work.run();
    }

    /** {@link #inTransaction(Runnable)} 的带返回值版本。 */
    @Transactional
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
