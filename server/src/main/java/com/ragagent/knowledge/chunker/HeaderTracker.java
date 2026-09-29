package com.ragagent.knowledge.chunker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上下文表头追踪。
 * <p>大 Markdown 表格跨多个 chunk 时，第一个 chunk 之后的 chunk 会丢失表头上下文。
 * HeaderTracker 检测表格表头并通知 merge 逻辑把它前置到后续 chunk。</p>
 */
final class HeaderTracker {

    /** startPattern 命中 → 该文本成为 active header，直到 endPattern 命中。 */
    private record Hook(Pattern startPattern, Pattern endPattern, int priority) {
    }

    private static final List<Hook> DEFAULT_HOOKS = List.of(
            new Hook(
                    // Markdown 表头行 + 分隔行（如 "| A | B |\n| --- | --- |\n"）
                    Pattern.compile("(?si)^\\s*(?:\\|[^|\\n]*)+[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?[\\r\\n]+$"),
                    // 空/空白行，或不以 | 或空白开头的行
                    Pattern.compile("(?si)^\\s*$|^\\s*[^|\\s].*$"),
                    15));

    static final int MARKDOWN_TABLE_HOOK_PRIORITY = 15;

    private final List<Hook> hooks;
    private final Map<Integer, String> activeHeaders = new HashMap<>();
    private final Map<Integer, Boolean> endedHeaders = new HashMap<>();
    private final Map<Integer, Boolean> pendingExtend = new HashMap<>();
    /** 表行单元以段落分隔结尾时空白行被 \n\n 切走；表头保持激活直到看见下一个单元。 */
    private boolean pendingTableBreak;
    /** 新表格开始时（列不匹配或 pendingTableBreak + 表行）通知 mergeUnits 在当前单元前 flush。 */
    private boolean headerEndedThisUnit;

    HeaderTracker() {
        this.hooks = DEFAULT_HOOKS;
    }

    boolean isHeaderEndedThisUnit() {
        return headerEndedThisUnit;
    }

    void update(String split) {
        headerEndedThisUnit = false;

        if (pendingTableBreak) {
            pendingTableBreak = false;
            if (activeHeaders.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)) {
                if (firstTableRowColumnCount(split) > 0) {
                    clearTableHeader();
                    headerEndedThisUnit = true;
                } else {
                    clearTableHeader();
                }
            }
        }

        // 1. 当前 active 表头中的 end 标记
        for (Hook hook : hooks) {
            if (activeHeaders.containsKey(hook.priority()) && hook.endPattern().matcher(split).find()) {
                endedHeaders.put(hook.priority(), true);
                activeHeaders.remove(hook.priority());
                pendingExtend.remove(hook.priority());
            }
        }

        // 1b. 段落切分消耗表格间的空行："| last row |\n\n" 后记 break，下一个单元时解析；
        // 新表行列数与 active 表头不同时也结束。
        if (activeHeaders.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)
                && !pendingExtend.containsKey(MARKDOWN_TABLE_HOOK_PRIORITY)) {
            if (splitEndsWithParagraphBreak(split)) {
                pendingTableBreak = true;
            } else {
                endTableHeaderOnColumnMismatch(split);
            }
        }

        // 2. 空列名行的表头（如 "||"）用首行数据重写为正常 Markdown 表头
        var pendingIter = new ArrayList<>(pendingExtend.keySet());
        for (int p : pendingIter) {
            if (activeHeaders.containsKey(p) && ChunkPatterns.TABLE_ROW.matcher(split).matches()) {
                String sep = extractSeparatorLine(activeHeaders.get(p));
                activeHeaders.put(p, split + sep);
            }
            pendingExtend.remove(p);
        }

        // 3. 新表头 start 标记（仅对既未 active 也未 ended 的 hook）
        for (Hook hook : hooks) {
            if (activeHeaders.containsKey(hook.priority()) || endedHeaders.containsKey(hook.priority())) {
                continue;
            }
            var m = hook.startPattern().matcher(split);
            if (m.find()) {
                String loc = m.group();
                activeHeaders.put(hook.priority(), loc);
                if (isEmptyTableHeaderRow(loc)) {
                    pendingExtend.put(hook.priority(), true);
                }
            }
        }

        // 4. 所有表头结束后清空 ended 集合，让后续表格可继续被追踪
        if (activeHeaders.isEmpty()) {
            endedHeaders.clear();
        }
    }

    String getHeaders() {
        if (activeHeaders.isEmpty()) {
            return "";
        }
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(activeHeaders.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));
        List<String> parts = new ArrayList<>();
        for (var e : entries) {
            parts.add(e.getValue());
        }
        return String.join("\n", parts);
    }

    static boolean isEmptyTableHeaderRow(String header) {
        int idx = header.indexOf('\n');
        if (idx < 0) {
            return false;
        }
        String row = header.substring(0, idx).strip();
        for (int cp : Runes.of(row)) {
            if (cp != '|' && cp != ' ' && cp != '\t') {
                return false;
            }
        }
        return true;
    }

    static String extractSeparatorLine(String header) {
        for (String line : header.split("\n", -1)) {
            if (line.contains("---")) {
                return line + "\n";
            }
        }
        return "";
    }

    private void clearTableHeader() {
        endedHeaders.put(MARKDOWN_TABLE_HOOK_PRIORITY, true);
        activeHeaders.remove(MARKDOWN_TABLE_HOOK_PRIORITY);
        pendingExtend.remove(MARKDOWN_TABLE_HOOK_PRIORITY);
    }

    private void endTableHeaderOnColumnMismatch(String split) {
        String header = activeHeaders.get(MARKDOWN_TABLE_HOOK_PRIORITY);
        if (header == null) {
            return;
        }
        int rowCols = firstTableRowColumnCount(split);
        int headerCols = headerTableColumnCount(header);
        if (rowCols > 0 && headerCols > 0 && rowCols != headerCols) {
            clearTableHeader();
            headerEndedThisUnit = true;
        }
    }

    private static boolean splitEndsWithParagraphBreak(String split) {
        String trimmed = trimRight(split, " \t\r");
        return trimmed.endsWith("\n\n") || trimmed.endsWith("\r\n\r\n");
    }

    private static String trimRight(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }

    static int tableRowColumnCount(String line) {
        line = line.strip();
        if (!line.startsWith("|")) {
            return 0;
        }
        String[] parts = line.split("\\|", -1);
        int start = 0;
        int end = parts.length;
        if (end > 0 && parts[0].strip().isEmpty()) {
            start = 1;
        }
        if (end > start && parts[end - 1].strip().isEmpty()) {
            end = end - 1;
        }
        return end - start;
    }

    static int firstTableRowColumnCount(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && ChunkPatterns.TABLE_ROW.matcher(trimmed).matches()) {
                return tableRowColumnCount(trimmed);
            }
        }
        return 0;
    }

    static int headerTableColumnCount(String header) {
        for (String line : header.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.contains("---")) {
                continue;
            }
            int n = tableRowColumnCount(trimmed);
            if (n > 0) {
                return n;
            }
        }
        return 0;
    }

    static boolean headerAlreadyPresent(String headers, String overlapText, String unitText) {
        if (overlapText.contains(headers) || unitText.contains(headers)) {
            return true;
        }
        String colRow = headerColumnRow(headers);
        if (colRow.isEmpty()) {
            return false;
        }
        return overlapText.contains(colRow) || unitText.contains(colRow);
    }

    static String headerColumnRow(String header) {
        for (String line : header.split("\n", -1)) {
            line = line.strip();
            if (line.isEmpty() || line.contains("---")) {
                continue;
            }
            boolean onlyPipes = true;
            for (int cp : Runes.of(line)) {
                if (cp != '|' && cp != ' ' && cp != '\t') {
                    onlyPipes = false;
                    break;
                }
            }
            if (!onlyPipes) {
                return line;
            }
        }
        return "";
    }

    static boolean headerColumnMismatch(String headers, String nextUnit) {
        int headerCols = headerTableColumnCount(headers);
        int rowCols = firstTableRowColumnCount(nextUnit);
        return headerCols > 0 && rowCols > 0 && headerCols != rowCols;
    }
}
