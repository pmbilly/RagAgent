package com.ragagent.knowledge.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KBCloneProgress;
import com.ragagent.knowledge.dto.KnowledgeTaskDtos.KnowledgeMoveProgress;
import org.springframework.stereotype.Component;

/**
 * move / clone 任务的进度存储（对照 Go knowledgeService 的 Redis 键
 * {@code knowledge_move_progress:<task_id>} / {@code kb_clone_progress:<task_id>}，TTL 24h）。
 *
 * <p>Java 侧按既有取舍用进程内 map（asynq → 进程内虚拟线程，§9 阶段 3 差异 1）：
 * 单实例语义一致，多副本部署无跨进程进度可见性。TTL 在读路径检查
 * （updatedAt 距今超过 24h 视为过期并清除），对照 Redis 的 SET EX 粗粒度等价。</p>
 *
 * <p>两条写入口的语义对照 Go：
 * <ul>
 *   <li>{@code save*Initial}（handler 准入时）= Redis {@code SETNX} → {@link #putIfAbsent}；
 *       只在键不存在时落「Task queued, waiting to start...」的初始进度；</li>
 *   <li>{@code save*}（worker 每步）= Redis {@code SET} → {@link #put}，无条件覆写。
 *       Go worker 构造的新进度对象<b>不带 created_at</b>（零值 0），覆写后
 *       响应里的 {@code created_at} 变成 0——这是源码行为的实录（golden 钉住），别"修好"。</li>
 * </ul></p>
 */
@Component
public class KnowledgeTaskProgressStore {

    private static final long TTL_SECONDS = 24 * 3600L;

    private final ConcurrentHashMap<String, KnowledgeMoveProgress> moveProgress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KBCloneProgress> cloneProgress = new ConcurrentHashMap<>();

    /** 对照 SaveKnowledgeMoveProgress（SETNX）：准入时的初始 pending 进度。 */
    public void saveMoveInitial(KnowledgeMoveProgress progress) {
        moveProgress.putIfAbsent(progress.taskId(), progress);
    }

    /** 对照 saveKnowledgeMoveProgress（SET）：worker 每步覆写。 */
    public void saveMove(KnowledgeMoveProgress progress) {
        moveProgress.put(progress.taskId(), progress);
    }

    /** 对照 GetKnowledgeMoveProgress；过期 → null（调用方转 404）。 */
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

    /** 对照 SaveKBCloneProgress（SETNX）。 */
    public void saveCloneInitial(KBCloneProgress progress) {
        cloneProgress.putIfAbsent(progress.taskId(), progress);
    }

    /** 对照 saveKBCloneProgress（SET）。 */
    public void saveClone(KBCloneProgress progress) {
        cloneProgress.put(progress.taskId(), progress);
    }

    /** 对照 GetKBCloneProgress；过期 → null。 */
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
