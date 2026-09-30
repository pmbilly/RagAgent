package com.ragagent.wiki.service.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分块引用的短句柄表：真实 chunk UUID ⟷ {@code c000}、{@code c001}…
 *
 * <p><b>存在理由</b>：引用 prompt 里用短句柄代替原始 UUID。句柄表是<b>调用局部的</b>——在任何结果进入
 * 应用状态<b>之前</b>，模型输出里的句柄就已经被翻译回稳定的 chunk ID。</p>
 *
 * <h2>语义（逐条）</h2>
 * <ul>
 *   <li>{@code register(key)}：首次使用才分配，之后稳定返回同一个句柄
 *       （条目<b>永不删除</b>，因此编号在表生命周期内稳定）；</li>
 *   <li>空 key → 空句柄；</li>
 *   <li>编号从 0 起、零填充到 3 位：{@code c000}、{@code c001}…；</li>
 *   <li>{@code resolve(handle)}：句柄 → 真实值的反查，未知句柄返回 {@code null}。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：虽然每个批次私有一张表，
 * 但同一批次内的多个引用批次是<b>并行</b>跑的（{@code maxCitationBatchConcurrency}），
 * 因此保留 {@code synchronized}。</p>
 */
public final class WikiChunkHandleTable {

    /** 句柄前缀 */
    public static final String PREFIX = WikiBatchConstants.CHUNK_HANDLE_PREFIX;

    /** 编号零填充宽度 */
    public static final int WIDTH = WikiBatchConstants.CHUNK_HANDLE_WIDTH;

    private final Map<String, String> handleByKey = new LinkedHashMap<>();
    private final Map<String, String> keyByHandle = new LinkedHashMap<>();
    private int next = 0;

    /**
     * 返回 key 对应的句柄，
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
     * 句柄 → 真实值。未知句柄返回 {@code null}。
     */
    public synchronized String resolve(String handle) {
        if (handle == null || handle.isEmpty()) {
            return null;
        }
        return keyByHandle.get(handle);
    }

    /**
     * 查已有句柄但<b>不分配</b>。
     */
    public synchronized String handleForKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return handleByKey.get(key);
    }

    /** 已分配句柄数 */
    public synchronized int size() {
        return keyByHandle.size();
    }

    /** 供测试/调试：句柄 → 真实 chunk ID 的只读快照（按分配顺序） */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(keyByHandle);
    }

    /**
     * 十进制 + 零填充到 width。
     * 数值超过 width 位时不截断（只补齐、不裁剪）。
     */
    private static String pad(int value) {
        String digits = Integer.toString(value);
        if (digits.length() >= WIDTH) {
            return digits;
        }
        return "0".repeat(WIDTH - digits.length()) + digits;
    }
}
