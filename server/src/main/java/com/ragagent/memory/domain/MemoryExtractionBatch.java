package com.ragagent.memory.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.web.GoTimeSerializer;

/**
 * {@code ClaimPendingSessions} 的返回值：一批待蒸馏的会话进度
 * （对照 Go {@code types.MemoryExtractionBatch}，internal/types/memory_extraction.go L72-76）。
 *
 * <p><b>注意 Go 的这个类型没有任何 json tag</b>，所以它的 JSON 键是 Go 的**导出字段名**
 * （{@code RetryAt} / {@code Sessions}），不是蛇形。它也不出 HTTP 响应——
 * 只在这条抽取链路上传递，所以 Java 侧不模拟那套驼峰键，
 * 但**语义**逐条照抄：</p>
 * <ul>
 *   <li>{@code retryAt} 非零 → 当前有别人的租约在跑，{@code sessions} 为空，
 *       调用方应等到这个时刻再重投；</li>
 *   <li>{@code retryAt} 为零 + {@code sessions} 为空 → 没有待办；</li>
 *   <li>{@code sessions} 非空 → 这批已被本 worker 租下。</li>
 * </ul>
 * <p>零值判定用 {@link GoTimeSerializer#isGoZero}（Go 的 {@code RetryAt.IsZero()}），
 * 不是 {@code != null}——字段类型是值语义的 {@code time.Time}。</p>
 */
public class MemoryExtractionBatch {

    /** 保持重投的任务活着，直到崩溃 worker 的租约过期。 */
    private OffsetDateTime retryAt = GoTimeSerializer.GO_ZERO_DATE_TIME;

    private List<MemoryExtractionSession> sessions = new ArrayList<>();

    public MemoryExtractionBatch() {
    }

    public static MemoryExtractionBatch retryAt(OffsetDateTime when) {
        MemoryExtractionBatch batch = new MemoryExtractionBatch();
        batch.setRetryAt(when);
        batch.setSessions(new ArrayList<>());
        return batch;
    }

    public static MemoryExtractionBatch of(List<MemoryExtractionSession> sessions) {
        MemoryExtractionBatch batch = new MemoryExtractionBatch();
        batch.setSessions(sessions);
        return batch;
    }

    public OffsetDateTime getRetryAt() { return retryAt; }
    public void setRetryAt(OffsetDateTime v) {
        retryAt = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    public List<MemoryExtractionSession> getSessions() { return sessions; }
    public void setSessions(List<MemoryExtractionSession> v) {
        sessions = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    /** 对照 Go 的 {@code subject.ExtractionState.LeaseUntil.After(now)} 那一侧的 {@code RetryAt} 用途。 */
    public boolean hasSessions() {
        return !sessions.isEmpty();
    }
}
