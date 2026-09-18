package com.ragagent.stream;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内的流管理器（对照 Go {@code MemoryStreamManager}，internal/stream/memory_manager.go）。
 *
 * <p><b>只适合单副本部署</b>：live-run 标记是本进程的 map，多副本下
 * {@code /steer} 会被路由到没有这一轮的副本。这正是 Go 把它标为 Lite 模式、
 * 由 {@code STREAM_MANAGER_TYPE=redis} 切到 {@link RedisStreamManager} 的原因。</p>
 *
 * <p>锁结构与 Go 逐一对齐：外层一把读写锁护住两张表（streams / liveRuns），
 * 每条流自己一把读写锁护住事件列表。加锁顺序恒为「先外后内」。</p>
 */
public class MemoryStreamManager implements StreamManager {

    /** 一条流的事件与 steer 子列表（对照 Go 的 memoryStreamData）。 */
    private static final class StreamData {
        final List<StreamEvent> events = new ArrayList<>();
        final List<StreamEvent> steerEvents = new ArrayList<>();
        /** 最后写入时刻。Go 侧字段存在但当前无人读取（留给将来的清理任务）。 */
        volatile OffsetDateTime lastUpdated = OffsetDateTime.now();
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    }

    /** 会话当前正在生成的那一轮（对照 Go 的 liveRunMarker）。 */
    private record LiveRunMarker(String assistantMessageId, String requestId) {
    }

    /** sessionId -> messageId -> 流数据 */
    private final Map<String, Map<String, StreamData>> streams = new ConcurrentHashMap<>();
    /** sessionId -> 当前生成中的一轮 */
    private final Map<String, LiveRunMarker> liveRuns = new HashMap<>();
    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();

    // ── 内部取用 ────────────────────────────────────────────────────────────

    private StreamData getOrCreateStream(String sessionId, String messageId) {
        mu.writeLock().lock();
        try {
            Map<String, StreamData> sessionMap = streams.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
            return sessionMap.computeIfAbsent(messageId, k -> new StreamData());
        } finally {
            mu.writeLock().unlock();
        }
    }

    private StreamData getStream(String sessionId, String messageId) {
        mu.readLock().lock();
        try {
            Map<String, StreamData> sessionMap = streams.get(sessionId);
            return sessionMap == null ? null : sessionMap.get(messageId);
        } finally {
            mu.readLock().unlock();
        }
    }

    // ── 事件流 ──────────────────────────────────────────────────────────────

    @Override
    public void appendEvent(String sessionId, String messageId, StreamEvent event) {
        StreamData stream = getOrCreateStream(sessionId, messageId);
        stream.lock.writeLock().lock();
        try {
            // 拷贝：Go 收的是值，补时间戳不会写回调用方的对象。
            StreamEvent stored = event.copy();
            if (stored.getTimestamp() == null) {
                stored.setTimestamp(OffsetDateTime.now());
            }
            stream.events.add(stored);
            stream.lastUpdated = OffsetDateTime.now();
        } finally {
            stream.lock.writeLock().unlock();
        }
    }

    @Override
    public StreamBatch getEvents(String sessionId, String messageId, int fromOffset) {
        StreamData stream = getStream(sessionId, messageId);
        if (stream == null) {
            // 流还不存在
            return StreamBatch.empty(fromOffset);
        }
        stream.lock.readLock().lock();
        try {
            if (fromOffset >= stream.events.size()) {
                return StreamBatch.empty(fromOffset);
            }
            List<StreamEvent> out = new ArrayList<>(stream.events.size() - fromOffset);
            for (StreamEvent e : stream.events.subList(fromOffset, stream.events.size())) {
                // 元素级拷贝（data 仍是共享引用）——与 Go 的 `copy(eventsCopy, events)` 同语义
                out.add(e.copy());
            }
            return new StreamBatch(out, stream.events.size());
        } finally {
            stream.lock.readLock().unlock();
        }
    }

    // ── steer 控制面 ────────────────────────────────────────────────────────

    @Override
    public void appendSteerEvents(String sessionId, String messageId, List<StreamEvent> events) {
        StreamData stream = getOrCreateStream(sessionId, messageId);
        stream.lock.writeLock().lock();
        try {
            Set<String> seen = new HashSet<>(stream.steerEvents.size());
            for (StreamEvent e : stream.steerEvents) {
                seen.add(e.getId());
            }
            for (StreamEvent event : events) {
                if (!event.getId().isEmpty() && seen.contains(event.getId())) {
                    continue;
                }
                seen.add(event.getId());
                // Go 在这里直接改 events[i]：切片底层数组与调用方共享，所以补上的
                // 时间戳调用方**看得见**。Java 侧同样就地改入参，保持这一可见性。
                if (event.getTimestamp() == null) {
                    event.setTimestamp(OffsetDateTime.now());
                }
                // 但存进管理器的是**拷贝**——Go 的 append 也是把 struct 值复制进切片，
                // 之后调用方再改 id/content 不会影响已入列的事件（data 仍是共享引用）。
                stream.steerEvents.add(event.copy());
            }
            stream.lastUpdated = OffsetDateTime.now();
        } finally {
            stream.lock.writeLock().unlock();
        }
    }

    @Override
    public StreamBatch getSteerEvents(String sessionId, String messageId, int fromOffset) {
        StreamData stream = getStream(sessionId, messageId);
        if (stream == null) {
            return StreamBatch.empty(fromOffset);
        }
        stream.lock.readLock().lock();
        try {
            if (fromOffset >= stream.steerEvents.size()) {
                return StreamBatch.empty(fromOffset);
            }
            List<StreamEvent> out = new ArrayList<>(stream.steerEvents.size() - fromOffset);
            for (StreamEvent e : stream.steerEvents.subList(fromOffset, stream.steerEvents.size())) {
                out.add(e.copy());
            }
            return new StreamBatch(out, stream.steerEvents.size());
        } finally {
            stream.lock.readLock().unlock();
        }
    }

    @Override
    public boolean updateSteerEventData(String sessionId, String messageId, String eventId, Map<String, Object> data) {
        StreamData stream = getStream(sessionId, messageId);
        if (stream == null) {
            return false;
        }
        stream.lock.writeLock().lock();
        try {
            for (StreamEvent event : stream.steerEvents) {
                if (!event.getId().equals(eventId)) {
                    continue;
                }
                // 先拷贝再改：GetSteerEvents 交出去的是浅拷贝，调用方可能还握着旧的 Data map。
                event.mergeData(data);
                stream.lastUpdated = OffsetDateTime.now();
                return true;
            }
            return false;
        } finally {
            stream.lock.writeLock().unlock();
        }
    }

    @Override
    public boolean deleteSteerEvent(String sessionId, String messageId, String eventId) {
        StreamData stream = getStream(sessionId, messageId);
        if (stream == null) {
            return false;
        }
        stream.lock.writeLock().lock();
        try {
            for (int i = 0; i < stream.steerEvents.size(); i++) {
                StreamEvent event = stream.steerEvents.get(i);
                if (!event.getId().equals(eventId)) {
                    continue;
                }
                Map<String, Object> d = event.getData();
                if (d != null && Boolean.TRUE.equals(d.get("consumed"))) {
                    return false;
                }
                stream.steerEvents.remove(i);
                stream.lastUpdated = OffsetDateTime.now();
                return true;
            }
            return false;
        } finally {
            stream.lock.writeLock().unlock();
        }
    }

    // ── live run ────────────────────────────────────────────────────────────

    @Override
    public void setLiveRun(String sessionId, String assistantMessageId, String requestId) {
        mu.writeLock().lock();
        try {
            LiveRunMarker existing = liveRuns.get(sessionId);
            if (existing != null && !existing.assistantMessageId().equals(assistantMessageId)) {
                throw new LiveRunExistsException();
            }
            liveRuns.put(sessionId, new LiveRunMarker(assistantMessageId, requestId));
        } finally {
            mu.writeLock().unlock();
        }
    }

    @Override
    public void claimLiveRun(String sessionId, String assistantMessageId, String requestId) {
        mu.writeLock().lock();
        try {
            liveRuns.put(sessionId, new LiveRunMarker(assistantMessageId, requestId));
        } finally {
            mu.writeLock().unlock();
        }
    }

    @Override
    public LiveRun getLiveRun(String sessionId) {
        mu.readLock().lock();
        try {
            LiveRunMarker marker = liveRuns.get(sessionId);
            return marker == null ? LiveRun.NONE : new LiveRun(marker.assistantMessageId(), marker.requestId());
        } finally {
            mu.readLock().unlock();
        }
    }

    @Override
    public void clearLiveRun(String sessionId, String assistantMessageId) {
        mu.writeLock().lock();
        try {
            LiveRunMarker marker = liveRuns.get(sessionId);
            if (marker != null && marker.assistantMessageId().equals(assistantMessageId)) {
                liveRuns.remove(sessionId);
            }
        } finally {
            mu.writeLock().unlock();
        }
    }
}
