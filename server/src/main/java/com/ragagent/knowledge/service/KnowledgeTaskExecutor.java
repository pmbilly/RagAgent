package com.ragagent.knowledge.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * 知识库域的后台任务执行器。
 *
 * <p>此前各服务自行 {@code Thread.ofVirtual().start(...)} 起线程：没有统一的命名、关停与
 * 观测入口，读代码时也很难判断"这个域到底会起哪些后台任务"。本类把这些收敛到一处：
 * 每个任务一条虚拟线程（与既有语义一致，都是 fire-and-forget 的轻量任务），
 * 可带任务名便于线程转储定位，容器关闭时统一停止接收新任务。</p>
 *
 * <p>注意：任务内部仍遵循本仓约定——跨虚拟线程显式拷贝租户/用户上下文
 * （{@code TenantContext} 不共享 ThreadLocal）。</p>
 */
@Component
public class KnowledgeTaskExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 提交一个后台任务（无命名）。 */
    public void submit(Runnable task) {
        executor.submit(task);
    }

    /**
     * 提交一个带名字的后台任务。
     *
     * @param taskName 任务名（写入线程名，便于线程转储/诊断时区分）
     */
    public void submit(String taskName, Runnable task) {
        executor.submit(() -> {
            Thread.currentThread().setName(taskName);
            task.run();
        });
    }

    /** 容器关闭：停止接收新任务（已提交的虚拟线程任务自然跑完）。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
