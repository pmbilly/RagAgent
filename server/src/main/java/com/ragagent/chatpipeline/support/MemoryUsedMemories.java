package com.ragagent.chatpipeline.support;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.common.session.PipelineUsedMemoryView;
import com.ragagent.memory.domain.MemoryItem;
import com.ragagent.memory.domain.MemoryText;

/**
 * {@code memory_items} → 客户端可见形态的投影
 * （对照 Go internal/types/memory.go 的 {@code UsedMemoriesFromItems} L1125-1135 与
 * {@code MergeUsedMemories} L1172-1195）。
 *
 * <p>放在 chatpipeline 包：这两个函数在 Go 里属于 {@code internal/types/memory.go}，
 * 消费方（session 的 agent QA、{@code chat_pipeline/memory_recall.go}）是它们的调用者。
 * 投影产物是跨域载荷 {@link PipelineUsedMemoryView}（common），
 * 会话侧落库时自行映射回 {@code session.domain.UsedMemory}。</p>
 */
public final class MemoryUsedMemories {

    private MemoryUsedMemories() {}

    /**
     * 对照 Go {@code UsedMemoriesFromItems}：把条目投影成客户端形态。
     *
     * <p>{@code nil} 条目被**跳过**（Go 的循环里有 {@code if item == nil { continue }}），
     * 且返回值恒是**非 nil** 的列表（Go 用 {@code make(UsedMemories, 0, len(items))}）
     * ——落库时它会写成 {@code []} 而不是 {@code null}。</p>
     */
    public static List<PipelineUsedMemoryView> usedMemoriesFromItems(List<MemoryItem> items) {
        List<PipelineUsedMemoryView> used = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return used;
        }
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            used.add(new PipelineUsedMemoryView(item.getId(), item.getKind(), item.getContent()));
        }
        return used;
    }

    /**
     * 对照 Go {@code MergeUsedMemories}：合并两份已展示的记忆，每个 id 保留**第一次**出现。
     *
     * <p>一条记忆可以影响一轮两次——一次塑造检索、一次被引在答案里——用户只该看到它列一次。</p>
     */
    public static List<PipelineUsedMemoryView> mergeUsedMemories(
            List<PipelineUsedMemoryView> existing, List<PipelineUsedMemoryView> additional) {
        return MemoryText.mergeUsedMemories(existing, additional, PipelineUsedMemoryView::id);
    }
}
