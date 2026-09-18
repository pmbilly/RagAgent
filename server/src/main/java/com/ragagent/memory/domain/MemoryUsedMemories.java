package com.ragagent.memory.domain;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.session.domain.UsedMemory;

/**
 * {@code memory_items} → 客户端可见形态的投影
 * （对照 Go internal/types/memory.go 的 {@code UsedMemoriesFromItems} L1125-1135 与
 * {@code MergeUsedMemories} L1172-1195）。
 *
 * <p>放在 memory 包而不是 session 包：这两个函数在 Go 里属于 {@code internal/types/memory.go}，
 * 消费方（{@code session_agent_qa.go}、{@code chat_pipeline/memory_recall.go}）是它们的调用者。
 * {@link UsedMemory} 类型本身已经在 {@code com.ragagent.session.domain}（阶段 5.1 落地），
 * 这里只借类型、不改那个包。</p>
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
    public static List<UsedMemory> usedMemoriesFromItems(List<MemoryItem> items) {
        List<UsedMemory> used = new ArrayList<>(items == null ? 0 : items.size());
        if (items == null) {
            return used;
        }
        for (MemoryItem item : items) {
            if (item == null) {
                continue;
            }
            UsedMemory one = new UsedMemory();
            one.setId(item.getId());
            one.setKind(item.getKind());
            one.setContent(item.getContent());
            used.add(one);
        }
        return used;
    }

    /**
     * 对照 Go {@code MergeUsedMemories}：合并两份已展示的记忆，每个 id 保留**第一次**出现。
     *
     * <p>一条记忆可以影响一轮两次——一次塑造检索、一次被引在答案里——用户只该看到它列一次。</p>
     */
    public static List<UsedMemory> mergeUsedMemories(List<UsedMemory> existing, List<UsedMemory> additional) {
        return MemoryText.mergeUsedMemories(existing, additional, UsedMemory::getId);
    }
}
