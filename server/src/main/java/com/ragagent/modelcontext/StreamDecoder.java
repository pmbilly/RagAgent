package com.ragagent.modelcontext;

/**
 * 流式安全的句柄解码（对照 Go internal/modelcontext/stream.go，全文移植）：
 * 共享的后缀扣留原语、建在其上的每空间解码器，以及按唯一安全顺序应用
 * 全部阶段的组合公开 StreamDecoder。
 */
public final class StreamDecoder {

    private final ResourceStreamDecoder resources;
    private final CitationStreamExpander sources;
    private final HandleStreamDecoder issues;
    private final HandleStreamDecoder mcpServers;
    private final HandleStreamDecoder mcpTools;
    private final OrphanResourceStreamFilter orphans;

    StreamDecoder(ResourceRegistry resources, SourceRegistry sources,
            HandleTable issues, HandleTable mcpServers, HandleTable mcpTools) {
        this.resources = resources == null ? null : new ResourceStreamDecoder(resources);
        this.sources = sources == null ? null : new CitationStreamExpander(sources);
        this.issues = issues == null ? null : new HandleStreamDecoder(issues);
        this.mcpServers = mcpServers == null ? null : new HandleStreamDecoder(mcpServers);
        this.mcpTools = mcpTools == null ? null : new HandleStreamDecoder(mcpTools);
        this.orphans = new OrphanResourceStreamFilter();
    }

    public String feed(String chunk) {
        if (chunk == null) {
            return "";
        }
        if (resources != null) {
            chunk = resources.feed(chunk);
        }
        if (sources != null) {
            chunk = sources.feed(chunk);
        }
        if (issues != null) {
            chunk = issues.feed(chunk);
        }
        if (mcpServers != null) {
            chunk = mcpServers.feed(chunk);
        }
        if (mcpTools != null) {
            chunk = mcpTools.feed(chunk);
        }
        if (orphans != null) {
            chunk = orphans.feed(chunk);
        }
        return chunk;
    }

    public String flush() {
        // 每个阶段的尾部必须先喂过后面的阶段，再让这些阶段 flush 自己的 pending——
        // 否则被早阶段尾部补全的句柄会绕过后续解码
        String tail = "";
        if (resources != null) {
            tail = resources.flush();
        }
        if (sources != null) {
            tail = sources.feed(tail) + sources.flush();
        }
        if (issues != null) {
            tail = issues.feed(tail) + issues.flush();
        }
        if (mcpServers != null) {
            tail = mcpServers.feed(tail) + mcpServers.flush();
        }
        if (mcpTools != null) {
            tail = mcpTools.feed(tail) + mcpTools.flush();
        }
        if (orphans != null) {
            tail = orphans.feed(tail) + orphans.flush();
        }
        return tail;
    }

    /**
     * 共享原语：永远不发半个模型句柄——每次 Feed 扣住可能在下个 provider 分块里
     * 长成句柄的尾部字节串，对已释放文本应用空间专属解码。flush 决定流结束时
     * 仍被扣住的尾缀的去向（对照 streamHold）。
     */
    static final class StreamHold {
        private String pending = "";
        private final java.util.function.ToIntFunction<String> holdLen;
        private final java.util.function.UnaryOperator<String> emit;
        private final java.util.function.UnaryOperator<String> flush;

        StreamHold(java.util.function.ToIntFunction<String> holdLen,
                java.util.function.UnaryOperator<String> emit,
                java.util.function.UnaryOperator<String> flush) {
            this.holdLen = holdLen;
            this.emit = emit;
            this.flush = flush;
        }

        String feed(String chunk) {
            String combined = pending + chunk;
            pending = "";
            if (combined.isEmpty()) {
                return "";
            }
            int hold = holdLen.applyAsInt(combined);
            if (hold > 0 && hold <= combined.length()) {
                pending = combined.substring(combined.length() - hold);
                combined = combined.substring(0, combined.length() - hold);
            }
            return emit.apply(combined);
        }

        String flush() {
            String held = pending;
            pending = "";
            return flush.apply(held);
        }
    }

    /** 跨 provider 分块还原 res:// 句柄的解码器（resourceStreamDecoder）。 */
    static final class ResourceStreamDecoder {
        private final StreamHold hold;

        ResourceStreamDecoder(ResourceRegistry registry) {
            this.hold = new StreamHold(
                    combined -> {
                        int holdLen = 0;
                        for (String handle : registry.handles()) {
                            // provider 可能在任意字节边界切分，包括 "re" + "s://0001"。
                            // 最多扣住短的匹配后缀是保证请求局部句柄不外泄的唯一办法
                            for (int n = 1; n < handle.length(); n++) {
                                if (n > holdLen && combined.endsWith(handle.substring(0, n))) {
                                    holdLen = n;
                                }
                            }
                        }
                        return holdLen;
                    },
                    registry::decodeText,
                    registry::decodeText);
        }

        String feed(String chunk) {
            return hold.feed(chunk);
        }

        String flush() {
            return hold.flush();
        }
    }

    /** 不外泄跨分块句柄地还原 HandleTable 值（HandleStreamDecoder）。 */
    public static final class HandleStreamDecoder {
        private final HandleTable table;
        private final StreamHold hold;

        HandleStreamDecoder(HandleTable table) {
            this.table = table;
            String prefix = table.store().prefix();
            this.hold = new StreamHold(
                    combined -> {
                        int start = combined.length();
                        while (start > 0 && isHandleTokenByte(combined.charAt(start - 1))) {
                            start--;
                        }
                        String tail = combined.substring(start);
                        // 前缀构造后不可变，无需加锁
                        if (couldBeNumericHandle(tail, prefix)) {
                            return tail.length();
                        }
                        return 0;
                    },
                    table::decodeKnownText,
                    table::decodeKnownText);
        }

        String feed(String chunk) {
            return hold.feed(chunk);
        }

        String flush() {
            return hold.flush();
        }
    }

    static boolean isHandleTokenByte(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || value == '-' || value == '_';
    }

    static boolean couldBeNumericHandle(String value, String prefix) {
        if (value.isEmpty() || prefix.isEmpty()) {
            return false;
        }
        if (prefix.startsWith(value)) {
            return true;
        }
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            return false;
        }
        for (int i = prefix.length(); i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 在已注册资源解码器有机会还原已知句柄之后才移除未解析句柄的过滤器
     * （orphanResourceStreamFilter）：缓冲可能的句柄尾缀，provider 分块边界
     * 不能把半个内部 token 漏给 UI。
     */
    static final class OrphanResourceStreamFilter {
        private final StreamHold hold;

        OrphanResourceStreamFilter() {
            this.hold = new StreamHold(
                    StreamDecoder::orphanResourceHoldLen,
                    released -> ResourceRegistry.RESOURCE_HANDLE_SHAPE.matcher(released).replaceAll(""),
                    StreamDecoder::orphanResourceFlush);
        }

        String feed(String chunk) {
            return hold.feed(chunk);
        }

        String flush() {
            return hold.flush();
        }
    }

    static int orphanResourceHoldLen(String combined) {
        String prefix = "res://";
        int holdAt = -1;
        // 未知句柄必须在每种 provider 切分下都安全，包括 "re" + "s://9999"。
        // 这至多推迟几个普通字符到下一分块；Flush 会在它们是普通 prose 时保留
        for (int n = 1; n < prefix.length(); n++) {
            if (combined.endsWith(prefix.substring(0, n))) {
                holdAt = combined.length() - n;
            }
        }
        int idx = combined.lastIndexOf(prefix);
        if (idx >= 0) {
            String suffix = combined.substring(idx + prefix.length());
            if (suffix.isEmpty() || allDigits(suffix)) {
                holdAt = idx;
            }
        }
        if (holdAt < 0) {
            return 0;
        }
        return combined.length() - holdAt;
    }

    static String orphanResourceFlush(String pending) {
        // 流在 token 中间结束不能把 model-context 协议片段漏出去。
        // 普通 r/re/res prose 保留，但已经进入保留 URL 语法的尾缀丢弃
        if ("res://".startsWith(pending) && pending.length() >= "res:".length()) {
            return "";
        }
        if (pending.startsWith("res://")) {
            String digits = pending.substring("res://".length());
            if (digits.isEmpty() || allDigits(digits)) {
                return "";
            }
        }
        return ResourceRegistry.RESOURCE_HANDLE_SHAPE.matcher(pending).replaceAll("");
    }

    static boolean allDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
