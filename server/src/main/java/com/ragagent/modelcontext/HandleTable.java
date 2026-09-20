package com.ragagent.modelcontext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 有类型、调用局部的双向映射（对照 Go internal/modelcontext/handles.go 的
 * {@code HandleTable}，全文移植）。当提示词需要为持久值分配紧凑句柄时使用
 * （wiki issue 的 iN、ingest 的 ref-N、c000 引用批次句柄）。句柄绝不持久化；
 * Resolve 先把模型输出转回持久值。它是 handleTable 的导出词边界感知包装。
 */
public final class HandleTable {

    private final HandleStore<Void> table;

    /**
     * 创建一个句柄空间：c000 用 (prefix="c", width=3, start=0)；
     * ref-1 用 (prefix="ref-", width=0, start=1)。
     */
    public HandleTable(String prefix, int width, int start) {
        this.table = new HandleStore<>(prefix, width, start);
    }

    HandleTable(HandleStore<Void> store) {
        this.table = store;
    }

    /** 返回 value 在本表中分配到的稳定句柄（对照 Register）。 */
    public String register(String value) {
        if (value == null) {
            return "";
        }
        value = value.strip();
        if (value.isEmpty()) {
            return "";
        }
        return table.register(value, value, null, null);
    }

    /** 已注册句柄的查询，不新建（对照 Handle；Go 零值 ""）。 */
    public String handle(String value) {
        String handle = table.handleForKey(value);
        return handle == null ? "" : handle;
    }

    /** (handle, ok) 二元组（Go 多返回值的 Java 形态）。 */
    public record Resolved(String value, boolean ok) {
    }

    /** 把已知句柄转回持久值（对照 Resolve）。 */
    public Resolved resolve(String handle) {
        if (handle == null) {
            return new Resolved("", false);
        }
        String value = table.resolveValue(handle.strip());
        return value == null ? new Resolved("", false) : new Resolved(value, true);
    }

    public boolean empty() {
        return table.size() == 0;
    }

    public int len() {
        return table.size();
    }

    /**
     * 把已注册的持久值替换为句柄（对照 EncodeKnownText）。
     * 长值先处理，避免子串遮蔽。
     */
    public String encodeKnownText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<HandleStore.Pair<Void>> pairs = new ArrayList<>(table.pairs());
        pairs.sort(Comparator.comparingInt((HandleStore.Pair<Void> p) -> p.value.length()).reversed());
        for (HandleStore.Pair<Void> item : pairs) {
            value = value.replace(item.value, item.handle);
        }
        return value;
    }

    /**
     * 在完整文本中还原已注册句柄（对照 DecodeKnownText）。与资源编解码不同，
     * 替换是词边界感知的——i1 这类短句柄不会在普通单词中间误触发。
     */
    public String decodeKnownText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<HandleStore.Pair<Void>> pairs = new ArrayList<>(table.pairs());
        pairs.sort(Comparator.comparingInt((HandleStore.Pair<Void> p) -> p.handle.length()).reversed());
        for (HandleStore.Pair<Void> item : pairs) {
            if (item.wordBounded == null) {
                continue;
            }
            // 持久值可能合法地含 '$'——不能走正则展开语义，用字面回调替换
            String durable = item.value;
            value = HandleStore.replaceAll(item.wordBounded, value, m -> durable);
        }
        return value;
    }

    HandleStore<Void> store() {
        return table;
    }
}
