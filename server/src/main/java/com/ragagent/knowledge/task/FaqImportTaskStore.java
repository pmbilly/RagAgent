package com.ragagent.knowledge.task;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.ragagent.knowledge.dto.faq.FaqImportProgress;
import org.springframework.stereotype.Component;

/**
 * FAQ 导入任务进度与并发锁的进程内存储。
 *
 * <p>三个并发面：按 taskId 的进度快照、按 kbId 的 running 锁（含实例标识以便多实例部署时
 * 区分持有者）、按 key 的创建互斥。均为单实例语义——未启用分布式存储时，重启即清空；
 * 线程安全由 {@link ConcurrentHashMap} 与并发键集保证。</p>
 */
@Component
public class FaqImportTaskStore {

    /** running 锁的持有者信息；instanceId 为空表示不区分实例。 */
    public record RunningInfo(String taskId, long enqueuedAt, String instanceId) {
    }

    private final ConcurrentHashMap<String, FaqImportProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RunningInfo> running = new ConcurrentHashMap<>();
    private final Set<String> createGuards = ConcurrentHashMap.newKeySet();

    public FaqImportProgress getProgress(String taskId) {
        return progress.get(taskId);
    }

    public void saveProgress(FaqImportProgress p) {
        progress.put(p.taskId(), p);
    }

    public String getRunningTaskId(String kbId) {
        RunningInfo info = running.get(kbId);
        return info == null ? "" : info.taskId();
    }

    public void setRunningInfo(String kbId, RunningInfo info) {
        running.put(kbId, info);
    }

    public void clearRunningInfoIfMatches(String kbId, String taskId, String instanceId, long enqueuedAt) {
        RunningInfo info = running.get(kbId);
        if (info != null && info.taskId().equals(taskId)
                && (info.instanceId().isEmpty() || instanceId.isEmpty()
                || info.instanceId().equals(instanceId))) {
            running.remove(kbId);
        }
    }

    public boolean acquireCreateGuard(String key) {
        return createGuards.add(key);
    }

    public void releaseCreateGuard(String key) {
        createGuards.remove(key);
    }
}
