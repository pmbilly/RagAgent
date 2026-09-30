package com.ragagent.knowledge.task;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import com.ragagent.knowledge.dto.kb.KBCloneProgress;
import com.ragagent.knowledge.dto.doc.KnowledgeMoveProgress;
import org.springframework.stereotype.Component;

/**
 * move / clone 任务的进度存储。
 * <p>Java 侧按既有取舍用进程内 map（任务队列 → 进程内虚拟线程，本仓约定）：
 * 单实例语义一致，多副本部署无跨进程进度可见性。TTL 在读路径检查
 * <p>两条写入口的语义
 * <ul>
 *   <li>{@code save*Initial}（handler 准入时）= Redis {@code SETNX} → {@link #putIfAbsent}；
 *       只在键不存在时落「Task queued, waiting to start...」的初始进度；</li>
 *   <li>{@code save*}（worker 每步）= Redis {@code SET} → {@link #put}，无条件覆写。
 *       响应里的 {@code created_at} 变成 0——这是源码行为的实录（契约样例锁定），别"修好"。</li>
 * </ul></p>
 */
@Component
public class KnowledgeTaskProgressStore {

    private static final long TTL_SECONDS = 24 * 3600L;

    private final ConcurrentHashMap<String, KnowledgeMoveProgress> moveProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KBCloneProgress> cloneProgress = new ConcurrentHashMap<>();

    /** 准入时的初始 pending 进度。 */
    public void saveMoveInitial(KnowledgeMoveProgress progress) {
        moveProgress.putIfAbsent(progress.taskId(), progress);
    }

    /** worker 每步覆写。 */
    public void saveMove(KnowledgeMoveProgress progress) {
        moveProgress.put(progress.taskId(), progress);
    }

    /** 过期 → null（调用方转 404）。 */
    public KnowledgeMoveProgress getMove(String taskId) {
        KnowledgeMoveProgress p = moveProgress.get(taskId);
        if (p == null) {
            return null;
        }
        if (expired(p.updatedAt())) {
            moveProgress.remove(taskId);
            return null;
        }
        return p;
    }
    public void saveCloneInitial(KBCloneProgress progress) {
        cloneProgress.putIfAbsent(progress.taskId(), progress);
    }
    public void saveClone(KBCloneProgress progress) {
        cloneProgress.put(progress.taskId(), progress);
    }

    /** 过期 → null。 */
    public KBCloneProgress getClone(String taskId) {
        KBCloneProgress p = cloneProgress.get(taskId);
        if (p == null) {
            return null;
        }
        if (expired(p.updatedAt())) {
            cloneProgress.remove(taskId);
            return null;
        }
        return p;
    }

    private static boolean expired(long updatedAt) {
        if (updatedAt <= 0) {
            return false;
        }
        return Instant.now().getEpochSecond() - updatedAt > TTL_SECONDS;
    }
}
