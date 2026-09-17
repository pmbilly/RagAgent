package com.ragagent.wiki.service;

import java.time.Duration;

/**
 * 一个待执行的 wiki 后台任务（对照 Go 的 {@code asynq.NewTask(...)} 调用点，
 * wiki_ingest.go L583-588 / L654-659 / L807-813 / L830-835 / L1022-1028 / L1064-1070）。
 *
 * <p>asynq 的 {@code Task} 只携带 (type, payload) 与一串 <b>option</b>；Java 侧把
 * 用到的 option 显式建模成字段——这样"哪些语义被保留"在类型上就是自明的，而不是藏在一串
 * 变参里。</p>
 *
 * @param type      asynq 任务类型：{@code "wiki:ingest"} / {@code "wiki:finalize"}
 * @param payload   JSON 载荷（{@link WikiIngestPayload} 的序列化结果）
 * @param processIn 对照 asynq {@code ProcessIn(d)}：延迟多久才可被执行
 * @param maxRetry  对照 asynq {@code MaxRetry(n)}：失败最多重试几次
 * @param timeout   对照 asynq {@code Timeout(d)}：单次执行的时限，超时视为失败
 * @param taskId    对照 asynq {@code TaskID(id)}：非空时<b>同 id 的任务在队列中会被合并</b>
 *                  （第二个入队返回冲突，调用方据此判断"已有同任务在排队/运行"）。
 *                  空串 = 不合并。
 */
public record WikiIngestTask(
        String type,
        String payload,
        Duration processIn,
        int maxRetry,
        Duration timeout,
        String taskId) {

    /** 对照 Go {@code types.TypeWikiIngest} */
    public static final String TYPE_WIKI_INGEST = "wiki:ingest";

    /** 对照 Go {@code types.TypeWikiFinalize} */
    public static final String TYPE_WIKI_FINALIZE = "wiki:finalize";

    /** 对照 Go {@code types.QueueWiki}：wiki 有独立队列，便于与主池的容量隔离。 */
    public static final String QUEUE_WIKI = "wiki";

    public WikiIngestTask {
        processIn = processIn == null ? Duration.ZERO : processIn;
        timeout = timeout == null ? Duration.ZERO : timeout;
        taskId = taskId == null ? "" : taskId;
    }

    /** 对照 asynq 的"没设 TaskID"（不合并） */
    public boolean hasTaskId() {
        return !taskId.isEmpty();
    }
}
