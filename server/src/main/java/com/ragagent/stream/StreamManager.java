package com.ragagent.stream;

import java.util.List;
import java.util.Map;

/**
 * 流管理器——极简的 append-only 设计（对照 Go {@code interfaces.StreamManager}，
 * internal/types/interfaces/stream_manager.go:23-74）。
 *
 * <p>所有流状态都通过事件承载：元信息、引用、完成标记等等，没有单独的元数据存储。</p>
 *
 * <p>实现有两个：{@link MemoryStreamManager}（单进程，Go 的 Lite 模式）与
 * {@link RedisStreamManager}（多副本共享）。由 {@code StreamManagerConfig} 按
 * {@code STREAM_MANAGER_TYPE} 选择。</p>
 *
 * <p>两套 API 用同一份方法名与语义，但 {@code steer} 那组是**控制面**：
 * 它们落在一个独立子列表上，永不出现在用户可见的 SSE 流里。生产方是"往运行中的
 * 轮次追加消息"的 HTTP 请求，唯一的消费者是正在跑的那一轮自己。</p>
 */
public interface StreamManager {

    /** 追加单个事件。时间戳为空时由实现补当前时刻。 */
    void appendEvent(String sessionId, String messageId, StreamEvent event);

    /** 从 offset 起增量读取事件。 */
    StreamBatch getEvents(String sessionId, String messageId, int fromOffset);

    /** 追加控制事件（steer 指令）到独立的子列表；同 ID 重复追加被去重。 */
    void appendSteerEvents(String sessionId, String messageId, List<StreamEvent> events);

    /** 从 offset 起读取 steer 子列表；列表不存在是空结果，不是错误。 */
    StreamBatch getSteerEvents(String sessionId, String messageId, int fromOffset);

    /**
     * 把键合并进某个排队的 steer 事件的 data（提升为 inject、标记 consumed）。
     *
     * @return 事件不存在时 false。实现必须原子地完成这次修改，
     *         否则并发的 {@link #appendSteerEvents} 会被丢
     */
    boolean updateSteerEventData(String sessionId, String messageId, String eventId, Map<String, Object> data);

    /**
     * 按 ID 删除排队的 steer 事件（用户关掉浮层条目时用）。
     *
     * @return 事件不存在时 false
     */
    boolean deleteSteerEvent(String sessionId, String messageId, String eventId);

    /**
     * 记录某会话当前正在生成的 assistant 消息。
     *
     * <p><b>排他</b>：已有另一个 assistant 在跑就抛 {@link LiveRunExistsException}，
     * 好让 executeQA 起不了第二个引擎。对同一个 assistant 重复调用是幂等的。
     * 需要接手的后续轮次用 {@link #claimLiveRun}。</p>
     */
    void setLiveRun(String sessionId, String assistantMessageId, String requestId);

    /** 覆盖 live-run 标记。用于"即将结束的一轮把会话交给后续轮次"的交接。 */
    void claimLiveRun(String sessionId, String assistantMessageId, String requestId);

    /** 该会话正在生成的 assistant 消息；没有 live run 时返回 {@link LiveRun#NONE}。 */
    LiveRun getLiveRun(String sessionId);

    /** 丢弃 live-run 标记——**但仅在它仍指向 assistantMessageId 时**：后续轮次可能已经改写了它。 */
    void clearLiveRun(String sessionId, String assistantMessageId);
}
