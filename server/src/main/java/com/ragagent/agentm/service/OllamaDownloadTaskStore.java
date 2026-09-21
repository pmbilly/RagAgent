package com.ragagent.agentm.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Ollama 模型下载任务存储（对照 Go internal/handler/initialization.go 的包级全局
 * {@code downloadTasks = make(map[string]*DownloadTask)} + {@code tasksMutex}）。
 *
 * <p><b>状态存哪</b>：Go 是<b>进程内存</b>——不落 DB、不落文件，重启即空（dev 两侧同形）。
 * Java 用进程级单例 ConcurrentHashMap 等价承载，因此不引入新表（TestSchema 不动）。
 * key 是 taskId（uuid），value 的字段与 Go 的 DownloadTask struct 一一对应：
 * id/modelName/status/progress/message/startTime/endTime（endTime 仅 completed/failed 时写）。</p>
 *
 * <p>Go 的 map 迭代序随机 → {@link #list()} 顺序不可依赖（A/B 单任务场景规避）。
 * 并发控制用 ConcurrentHashMap 自身（Go 的 RWMutex 只保护 map 结构，任务字段更新
 * 也整锁——Java 侧任务字段为 volatile 读可见，进度覆盖写无累加，无丢失窗口）。</p>
 */
@Component
public class OllamaDownloadTaskStore {

    /** 下载任务（对照 Go DownloadTask，字段语义同源；JSON 由控制器按 struct 序手组）。 */
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

    /** 对照 Go 的包级 downloadTasks（进程全局；测试用 resetAll 清态）。 */
    private static final Map<String, DownloadTask> TASKS = new ConcurrentHashMap<>();

    public DownloadTask create(String taskId, String modelName, OffsetDateTime startTime) {
        DownloadTask task = new DownloadTask(taskId, modelName, startTime);
        TASKS.put(taskId, task);
        return task;
    }

    public DownloadTask get(String taskId) {
        return TASKS.get(taskId);
    }

    /** 对照 Go ListDownloadTasks 的全量收集（map 序随机 → 顺序不承诺）。 */
    public List<DownloadTask> list() {
        return new ArrayList<>(TASKS.values());
    }

    /** 对照 Go updateTaskStatus（终态补 endTime）。 */
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

    /** 对照 Go "已有同模型任务在途" 的扫描（pending/downloading）。 */
    public DownloadTask findActiveByModel(String modelName) {
        for (DownloadTask task : TASKS.values()) {
            if (task.modelName.equals(modelName)
                    && ("pending".equals(task.status) || "downloading".equals(task.status))) {
                return task;
            }
        }
        return null;
    }

    /** 测试隔离用（对照 Go 测试也无法跨用例清包级 map 的现实——这里显式给出口）。 */
    public static void resetAll() {
        TASKS.clear();
    }
}
