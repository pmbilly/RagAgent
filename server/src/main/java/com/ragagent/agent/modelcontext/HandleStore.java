package com.ragagent.agent.modelcontext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * model 句柄空间的双向原语。
 *
 * <p>把持久去重键映射到顺序分配的句柄，并在同一把锁下保存持久值与可选元数据，
 * 因此「可解析的句柄必然带着元数据」。key 与 value 可以不同（web 引用按规范化
 * URL 去重、但解码回原始 URL）。条目永不删除，分配计数因此等于历史条目总数，
 * 句柄编号在表的生命周期内稳定。</p>
 *
 * <p>{@code wordBounded} 在注册时编译一次：解码对每个流式分块都要跑，
 * 逐句柄逐分块重编译曾是性能热点。</p>
 *
 * <p>已知差异（备案）：遍历用插入序（LinkedHashMap）+ 稳定排序；「等长值」
 * 并列处的顺序不确定。</p>
 */
final class HandleStore<M> {

    /** 一次注册的合并回调：重复注册时把 src 折叠进 dst。 */
    interface Merger<M> {
        void merge(M dst, M src);
    }

    /** 文本编解码（压缩/解码）用的快照行。 */
    static final class Pair<M> {
        final String value;
        final String handle;
        final Pattern wordBounded;

        Pair(String value, String handle, Pattern wordBounded) {
            this.value = value;
            this.handle = handle;
            this.wordBounded = wordBounded;
        }
    }

    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    private final String prefix;
    private final int width;
    private int next;
    private final Map<String, String> handleByKey = new LinkedHashMap<>();
    private final Map<String, Entry<M>> entryByHandle = new LinkedHashMap<>();

    private static final class Entry<M> {
        final String value;
        final M meta;
        final Pattern wordBounded;

        Entry(String value, M meta, Pattern wordBounded) {
            this.value = value;
            this.meta = meta;
            this.wordBounded = wordBounded;
        }
    }

    HandleStore(String prefix, int width, int start) {
        this.prefix = prefix;
        this.width = width;
        this.next = start;
    }

    /**
     * 返回 key 分配到的句柄，首次使用时分配下一个。value 是句柄解码回的目标；
     * merge 非空时把重复注册的元数据折进既有条目。
     */
    String register(String key, String value, M meta, Merger<M> merge) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        mu.writeLock().lock();
        try {
            String existing = handleByKey.get(key);
            if (existing != null) {
                if (merge != null) {
                    // 合并回调就地修改元数据（ChunkReference/WebMeta 为可变对象），
                    // 条目引用不变
                    mergeMeta(existing, meta, merge);
                }
                return existing;
            }
            String number = Integer.toString(next);
            if (width > 0) {
                number = String.format("%0" + width + "d", next);
            }
            next++;
            String handle = prefix + number;
            handleByKey.put(key, handle);
            entryByHandle.put(handle, new Entry<>(value, meta, compileWordBounded(handle)));
            return handle;
        } finally {
            mu.writeLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeMeta(String handle, M src, Merger<M> merge) {
        Entry<M> entry = entryByHandle.get(handle);
        // 元数据要么是不可变占位（null/Void），要么是可变对象（ChunkReference/WebMeta），
        // merge 就地修改后条目引用保持不变
        merge.merge(entry.meta, src);
    }

    /** 已有句柄的查询，不分配。 */
    String handleForKey(String key) {
        if (key == null) {
            return null;
        }
        mu.readLock().lock();
        try {
            return handleByKey.get(key);
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 句柄是否存在。 */
    boolean has(String handle) {
        mu.readLock().lock();
        try {
            return entryByHandle.containsKey(handle);
        } finally {
            mu.readLock().unlock();
        }
    }

    int size() {
        mu.readLock().lock();
        try {
            return entryByHandle.size();
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 已知句柄 → (持久值, 元数据)。 */
    M resolve(String handle, StringBuilder valueOut) {
        mu.readLock().lock();
        try {
            Entry<M> entry = entryByHandle.get(handle);
            if (entry == null) {
                return null;
            }
            if (valueOut != null) {
                valueOut.setLength(0);
                valueOut.append(entry.value);
            }
            return entry.meta;
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 句柄 → 持久值（忽略元数据）。 */
    String resolveValue(String handle) {
        mu.readLock().lock();
        try {
            Entry<M> entry = entryByHandle.get(handle);
            return entry == null ? null : entry.value;
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 值/句柄快照，顺序由调用方负责。 */
    List<Pair<M>> pairs() {
        mu.readLock().lock();
        try {
            List<Pair<M>> out = new ArrayList<>(entryByHandle.size());
            for (Map.Entry<String, Entry<M>> e : entryByHandle.entrySet()) {
                out.add(new Pair<>(e.getValue().value, e.getKey(), e.getValue().wordBounded));
            }
            return out;
        } finally {
            mu.readLock().unlock();
        }
    }

    /** 句柄前缀（HandleStreamDecoder 用；构造后不可变，无需锁）。 */
    String prefix() {
        return prefix;
    }

    /** 正则元字符转义（RE2 元字符集）。 */
    private static String quoteMeta(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Pattern compileWordBounded(String handle) {
        return Pattern.compile("\\b" + quoteMeta(handle) + "\\b");
    }

    /** 便利：对一段文本应用 wordBounded 替换（DecodeKnownText 的内循环）。 */
    static String replaceAll(Pattern pattern, String value, java.util.function.Function<String, String> replacer) {
        Matcher m = pattern.matcher(value);
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m.group())));
        }
        if (!any) {
            return value;
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
