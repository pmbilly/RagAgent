package com.ragagent.memory.mapper;

import java.util.function.Function;

import com.ragagent.memory.domain.MemoryScope;
import com.ragagent.memory.domain.MemorySubject;
import com.ragagent.memory.domain.MemorySubjectMissingException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行锁事务模板：在一个事务里锁住本 scope 的主体行，再把回调跑完。
 *
 * <h2>为什么单独一个 bean 而不是 {@link MemoryRepository} 的私有方法</h2>
 * <p><b>Spring 的自调用不走代理</b>：{@code MemoryRepository} 内部直接调用自己的
 * {@code withSubject(...)}，{@code @Transactional} 根本不生效——所有写方法都会跑在
 * 各自独立的自动提交事务里，行锁当场释放，"租约 CAS"随之失效，
 * 而且**测试全绿**（单线程下看不出差别）。所以这里把它提成一个独立 bean：
 * 调用穿过代理，{@code FOR UPDATE} 的锁真的保持到回调结束。</p>
 *
 * <h2>回调里为什么能直接用别的 mapper</h2>
 * <p>Spring 的事务是**线程绑定**的：回调在同一个线程里执行，各 mapper 的
 * SqlSession 会加入同一个事务（{@code DataSourceTransactionManager} + MyBatis 的
 * {@code SpringManagedTransaction}），等价于把事务句柄沿调用链显式传下去。</p>
 *
 * <h2>找不到主体时</h2>
 * <p>主体行不存在时**抛** {@link MemorySubjectMissingException}（不是静默返回 null）。
 * 悄悄当成"没有主体"继续，会在一行不存在的基础上做写入。</p>
 */
@Component
public class MemoryTxTemplate {

    private final MemorySubjectMapper subjectMapper;

    public MemoryTxTemplate(MemorySubjectMapper subjectMapper) {
        this.subjectMapper = subjectMapper;
    }

    /**
     * 在事务里锁住本 scope 的主体行，再把 {@code work} 跑完。
     *
     * @throws MemorySubjectMissingException 主体行不存在
     */
    @Transactional
    public <T> T withSubject(MemoryScope scope, Function<MemorySubject, T> work) {
        MemorySubject subject = subjectMapper.selectByScopeForUpdate(scope.tenantId(), scope.subjectId());
        if (subject == null) {
            throw new MemorySubjectMissingException();
        }
        return work.apply(subject);
    }
}
