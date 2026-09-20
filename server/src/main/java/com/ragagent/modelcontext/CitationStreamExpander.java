package com.ragagent.modelcontext;

/**
 * 防止私有的部分 <ref/> 标签漏进 SSE、同时保持其他内容正常流式输出
 * （对照 Go internal/modelcontext/citations.go 的 citationStreamExpander，全文移植）。
 */
final class CitationStreamExpander {

    private final SourceRegistry registry;
    private String pending = "";

    CitationStreamExpander(SourceRegistry registry) {
        this.registry = registry;
    }

    String feed(String chunk) {
        if (chunk == null) {
            chunk = "";
        }
        String data = pending + chunk;
        pending = "";
        StringBuilder out = new StringBuilder();
        while (!data.isEmpty()) {
            int idx = data.indexOf('<');
            if (idx < 0) {
                out.append(data);
                break;
            }
            out.append(data, 0, idx);
            data = data.substring(idx);
            String lower = data.toLowerCase();
            if (isSourceTagPending(lower) && !data.contains(">")) {
                pending = data;
                break;
            }
            if (isRefTagStart(lower)) {
                int end = data.indexOf('>');
                if (end < 0) {
                    pending = data;
                    break;
                }
                String tag = data.substring(0, end + 1);
                if (SourceRegistry.refTagMatches(tag)) {
                    out.append(registry.expandText(tag));
                }
                data = data.substring(end + 1);
                continue;
            }
            if (isNamedTagStart(lower, "kb") || isNamedTagStart(lower, "web")) {
                int end = data.indexOf('>');
                if (end < 0) {
                    pending = data;
                    break;
                }
                data = data.substring(end + 1);
                continue;
            }
            out.append('<');
            data = data.substring(1);
        }
        return out.toString();
    }

    static boolean isRefTagStart(String value) {
        return isNamedTagStart(value, "ref");
    }

    static boolean isNamedTagStart(String value, String name) {
        String prefix = "<" + name;
        if (!value.startsWith(prefix)) {
            return false;
        }
        if (value.length() == prefix.length()) {
            return true;
        }
        char next = value.charAt(prefix.length());
        return next == ' ' || next == '\t' || next == '\r' || next == '\n' || next == '>';
    }

    static boolean isSourceTagPending(String value) {
        for (String name : new String[] {"ref", "kb", "web"}) {
            String prefix = "<" + name;
            if ((value.length() <= prefix.length() && prefix.startsWith(value)) || isNamedTagStart(value, name)) {
                return true;
            }
        }
        return false;
    }

    String flush() {
        String held = pending;
        pending = "";
        String lower = held.toLowerCase();
        if (isSourceTagPending(lower)) {
            return "";
        }
        return registry.expandText(held);
    }
}
