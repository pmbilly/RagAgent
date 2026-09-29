package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Ollama 模型下载任务存储。
 *
 * <p><b>状态存哪</b>：<b>进程内存</b>——不落 DB、不落文件，重启即空。
 * 进程级单例 ConcurrentHashMap 承载，因此不引入新表（TestSchema 不动）。
 * key 是 taskId（uuid），value 字段为：
 * id/modelName/status/progress/message/startTime/endTime（endTime 仅 completed/failed 时写）。</p>
 *
 * <p>map 迭代序不定 → {@link #list()} 顺序不可依赖（单任务场景规避）。
 * 并发控制用 ConcurrentHashMap 自身（任务字段为 volatile 保证读可见；
 * 进度为覆盖写、无累加，无丢失窗口）。</p>
 */
@Component
public class OllamaDownloadTaskStore {

    /** 下载任务；JSON 由控制器按固定字段序手组。 */
    public static final class DownloadTask {
        public final String id;
        public final String modelName;
        public volatile String status;
        public volatile double progress;
        public volatile String message;
        public final OffsetDateTime startTime;
        public volatile OffsetDateTime endTime;

        public DownloadTask(String id, String modelName, OffsetDateTime startTime) {
            this.id = id;
            this.modelName = modelName;
            this.status = "pending";
            this.progress = 0.0;
            this.message = "准备下载";
            this.startTime = startTime;
        }
    }

    /** 进程全局任务表；测试用 resetAll 清态。 */
    private static final Map<String, DownloadTask> TASKS = new ConcurrentHashMap<>();

    public DownloadTask create(String taskId, String modelName, OffsetDateTime startTime) {
        DownloadTask task = new DownloadTask(taskId, modelName, startTime);
        TASKS.put(taskId, task);
        return task;
    }

    public DownloadTask get(String taskId) {
        return TASKS.get(taskId);
    }

    /** 全量收集（顺序不承诺）。 */
    public List<DownloadTask> list() {
        return new ArrayList<>(TASKS.values());
    }

    /** 更新任务状态（终态补 endTime）。 */
    public void updateStatus(String taskId, String status, double progress, String message,
            OffsetDateTime now) {
        DownloadTask task = TASKS.get(taskId);
        if (task == null) {
            return;
        }
        task.status = status;
        task.progress = progress;
        task.message = message;
        if ("completed".equals(status) || "failed".equals(status)) {
            task.endTime = now;
        }
    }

    /** 扫描 "已有同模型任务在途"（pending/downloading）。 */
    public DownloadTask findActiveByModel(String modelName) {
        for (DownloadTask task : TASKS.values()) {
            if (task.modelName.equals(modelName)
                    && ("pending".equals(task.status) || "downloading".equals(task.status))) {
                return task;
            }
        }
        return null;
    }

    /** 测试隔离用：显式清空任务表的出口。 */
    public static void resetAll() {
        TASKS.clear();
    }
}
