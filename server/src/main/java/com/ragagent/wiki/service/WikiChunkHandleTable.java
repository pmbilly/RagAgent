package com.ragagent.wiki.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 分块引用的短句柄表：真实 chunk UUID ⟷ {@code c000}、{@code c001}…
 * （对照 Go {@code modelcontext.NewHandleTable("c", 3, 0)}，
 * internal/modelcontext/handle_table.go + cite L178/L200/L241/L314/L329）。
 *
 * <p><b>存在理由</b>（照搬 Go {@code splitChunksIntoCitationBatches} 的注释）：
 * 引用 prompt 里用短句柄代替原始 UUID。句柄表是<b>调用局部的</b>——在任何结果进入
 * 应用状态<b>之前</b>，模型输出里的句柄就已经被翻译回稳定的 chunk ID。</p>
 *
 * <h2>与 Go {@code handleTable} 的语义对齐（逐条）</h2>
 * <ul>
 *   <li>{@code register(key)}：首次使用才分配，之后稳定返回同一个句柄
 *       （Go 的注释：条目<b>永不删除</b>，因此编号在表生命周期内稳定）；</li>
 *   <li>空 key → 空句柄（Go {@code register} 的 {@code key == ""} 分支）；</li>
 *   <li>编号从 {@code start=0} 起、零填充到 {@code width=3}：{@code c000}、{@code c001}…；</li>
 *   <li>{@code resolve(handle)}：句柄 → 真实值的反查，未知句柄返回 {@code null}
 *       （Go 的 {@code resolve} 返回 {@code ok=false}）。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：Go 用 {@code sync.RWMutex}。虽然每个批次私有一张表，
 * 但同一批次内的多个引用批次是<b>并行</b>跑的（{@code maxCitationBatchConcurrency}），
 * 因此 Java 侧保留 {@code synchronized}。</p>
 *
 * <p><b>为什么不去翻 modelcontext 包</b>：Go 的 {@code HandleTable} 是一个纯内存句柄
 * 分配器，wiki ingest 只用到 {@code Register/Handle/Resolve/Len} 四个操作。
 * Java 侧只在本类内实现等价的 {@code cNNN} 分配器，语义逐条对齐。</p>
 */
public final class WikiChunkHandleTable {

    /** 对照 Go {@code NewHandleTable("c", 3, 0)} 的前缀 */
    public static final String PREFIX = WikiBatchConstants.CHUNK_HANDLE_PREFIX;

    /** 对照 Go {@code width=3}：编号零填充宽度 */
    public static final int WIDTH = WikiBatchConstants.CHUNK_HANDLE_WIDTH;

    private final Map<String, String> handleByKey = new LinkedHashMap<>();
    private final Map<String, String> keyByHandle = new LinkedHashMap<>();
    private int next = 0;

    /**
     * 对照 Go {@code handleTable.register(key, ...)}：返回 key 对应的句柄，
     * 首次使用时分配下一个。空 key 返回 ""。
     */
    public synchronized String register(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String existing = handleByKey.get(key);
        if (existing != null) {
            return existing;
        }
        String handle = PREFIX + pad(next);
        next++;
        handleByKey.put(key, handle);
        keyByHandle.put(handle, key);
        return handle;
    }

    /**
     * 对照 Go {@code handleTable.resolve(handle)}：句柄 → 真实值。
     * 未知名返回 {@code null}（Go 的 {@code ok=false}）。
     */
    public synchronized String resolve(String handle) {
        if (handle == null || handle.isEmpty()) {
            return null;
        }
        return keyByHandle.get(handle);
    }

    /**
     * 对照 Go {@code handleTable.handleForKey}：查已有句柄但<b>不分配</b>。
     */
    public synchronized String handleForKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return handleByKey.get(key);
    }

    /** 对照 Go {@code handleTable.size()} / 测试里的 {@code b.handles.Len()} */
    public synchronized int size() {
        return keyByHandle.size();
    }

    /** 供测试/调试：句柄 → 真实 chunk ID 的只读快照（按分配顺序） */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(keyByHandle);
    }

    /**
     * 对照 Go {@code fmt.Sprintf("%0*d", width, next)}：十进制 + 零填充到 width。
     * 数值超过 width 位时不截断（Go 的 {@code %0*d} 同样只补齐、不裁剪）。
     */
    private static String pad(int value) {
        String digits = Integer.toString(value);
        if (digits.length() >= WIDTH) {
            return digits;
        }
        return "0".repeat(WIDTH - digits.length()) + digits;
    }
}
