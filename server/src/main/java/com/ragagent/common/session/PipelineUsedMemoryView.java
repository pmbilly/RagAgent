package com.ragagent.common.session;

/**
 * 「本轮用到的记忆」的客户端可见投影（id / kind / content）。
 *
 * <p>由 chatpipeline 从 memory 召回的条目投影产生，随 {@code memory_recalled} 事件回传会话侧
 * 落库；两边都只认这份载荷，不再传 {@code session.domain.UsedMemory} 实体。</p>
 */
public record PipelineUsedMemoryView(String id, String kind, String content) {

    /**
     * 与实体同约定：三个键都无 omitempty，空值归一为空串
     * （SSE 的 {@code memory_recalled} 事件载荷逐字节依赖这一点）。
     */
    public PipelineUsedMemoryView {
        id = id == null ? "" : id;
        kind = kind == null ? "" : kind;
        content = content == null ? "" : content;
    }
}
