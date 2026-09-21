package com.ragagent.im.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * IM 工具步骤显示（对照 Go internal/im/tool_display.go 全文，波 5 W5γ1 逐行翻译；
 * 文案与 Web 端 agentStream/RagPipelineProgress 对齐）。字节契约：
 * contracts/w5g1-im-foundation.tsv（overlay 探针 2026-09-21）。
 */
public final class ToolDisplay {

    private ToolDisplay() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一个工具调用的 IM 显示状态（tool_display.go L10-18）。 */
    public static final class IMToolStep {
        public String toolCallId = "";
        public String toolName = "";
        public boolean pending;
        public boolean success;
        public Map<String, Object> arguments;
        public Map<String, Object> data;
        public String output = "";

        public IMToolStep() {
        }

        public IMToolStep(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }

        public IMToolStep pending() {
            this.pending = true;
            return this;
        }

        public IMToolStep success() {
            this.success = true;
            return this;
        }

        public IMToolStep args(Map<String, Object> args) {
            this.arguments = args;
            return this;
        }

        public IMToolStep data(Map<String, Object> data) {
            this.data = data;
            return this;
        }
    }

    /** zh-CN 工具名（对齐前端 agentStream.tools；tool_display.go L21-60）。 */
    static String imLocalizedToolName(String toolName) {
        String name = IM_TOOL_NAME_LABELS.get(toolName);
        if (name != null) {
            return name;
        }
        if (toolName.startsWith("mcp_")) {
            return formatMCPToolName(toolName);
        }
        return toolName;
    }

    private static final Map<String, String> IM_TOOL_NAME_LABELS = buildLabels();

    private static Map<String, String> buildLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("search_knowledge", "知识库检索");
        m.put("knowledge_search", "知识库检索");
        m.put("grep_chunks", "搜索关键词");
        m.put("web_search", "网络搜索");
        m.put("web_fetch", "网页抓取");
        m.put("get_document_info", "获取文档信息");
        m.put("list_knowledge_chunks", "查看知识分块");
        m.put("get_related_documents", "查找相关文档");
        m.put("get_document_content", "获取文档内容");
        m.put("wiki_search", "Wiki 搜索");
        m.put("wiki_read_page", "Wiki 阅读");
        m.put("wiki_read_source_doc", "精读源文档");
        m.put("todo_write", "计划管理");
        m.put("knowledge_graph_extract", "知识图谱抽取");
        m.put("thinking", "思考");
        m.put("image_analysis", "查看图片内容");
        m.put("query_understand", "理解问题");
        m.put("query_knowledge_graph", "知识图谱查询");
        m.put("read_skill", "读取技能");
        m.put("execute_skill_script", "执行技能脚本");
        m.put("list_sandbox_files", "列出沙箱文件");
        m.put("read_sandbox_file", "读取沙箱文件");
        m.put("write_sandbox_file", "写入沙箱文件");
        m.put("edit_sandbox_file", "编辑沙箱文件");
        m.put("shell_exec", "执行沙箱命令");
        m.put("data_analysis", "数据分析");
        m.put("data_schema", "数据结构");
        m.put("database_query", "数据库查询");
        return m;
    }

    static String formatMCPToolName(String rawName) {
        String rest = rawName.startsWith("mcp_") ? rawName.substring(4) : rawName;
        if (rest.isEmpty()) {
            return rawName;
        }
        // Go strings.Split + Join：空段也占一位（"a__b" → "A  B" 双空格），逐字保留。
        String[] parts = rest.split("_", -1);
        String[] out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            out[i] = p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1);
        }
        return String.join(" ", out);
    }

    // ── 参数/数据提取（tool_display.go L77-226） ─────────────────────────

    private static List<String> collectQueryStrings(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            String trimmed = s.strip();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (trimmed.startsWith("[")) {
                try {
                    List<?> parsed = JSON.readValue(trimmed, List.class);
                    List<String> out = new ArrayList<>();
                    for (Object item : parsed) {
                        if (item instanceof String str && !str.strip().isEmpty()) {
                            out.add(str.strip());
                        }
                    }
                    return out;
                } catch (Exception ignored) {
                    // fall through：不是 JSON 数组就按单串
                }
            }
            return new ArrayList<>(List.of(trimmed));
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.strip().isEmpty()) {
                    out.add(s.strip());
                }
            }
            return out;
        }
        return null;
    }

    /** args 可以是已解析的 map，也可以是 JSON 字符串（对照 Go 的 any 断言链）。 */
    private static Map<String, Object> asRecord(Object args) {
        if (args instanceof String s) {
            try {
                return JSON.readValue(s, Map.class);
            } catch (Exception e) {
                return null;
            }
        }
        if (args instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return null;
    }

    static String imGetQueryText(Object args) {
        Map<String, Object> record = asRecord(args);
        if (record == null) {
            return "";
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> queries = new ArrayList<>();
        for (String q : listOf(collectQueryStrings(record.get("query")))) {
            if (seen.add(q)) {
                queries.add(q);
            }
        }
        for (String q : listOf(collectQueryStrings(record.get("queries")))) {
            if (seen.add(q)) {
                queries.add(q);
            }
        }
        return String.join("，", queries);
    }

    static String imGetWikiPageText(Object args) {
        Map<String, Object> record = asRecord(args);
        if (record == null) {
            return "";
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> slugs = new ArrayList<>();
        for (String slug : listOf(collectQueryStrings(record.get("slug")))) {
            if (seen.add(slug)) {
                slugs.add(slug);
            }
        }
        for (String slug : listOf(collectQueryStrings(record.get("slugs")))) {
            if (seen.add(slug)) {
                slugs.add(slug);
            }
        }
        return String.join("、", slugs);
    }

    static List<String> imGetGrepPatterns(Object args) {
        Map<String, Object> record = asRecord(args);
        if (record == null) {
            return null;
        }
        List<String> queries = collectQueryStrings(record.get("queries"));
        if (queries != null && !queries.isEmpty()) {
            return queries;
        }
        List<String> patterns = collectQueryStrings(record.get("patterns"));
        if (patterns != null && !patterns.isEmpty()) {
            return patterns;
        }
        String q = imGetQueryText(record);
        if (!q.isEmpty()) {
            return new ArrayList<>(List.of(q));
        }
        if (record.get("pattern") instanceof String pattern && !pattern.strip().isEmpty()) {
            return new ArrayList<>(List.of(pattern.strip()));
        }
        return null;
    }

    private static String imGetWebSearchQuery(IMToolStep step) {
        String q = imGetQueryText(step.arguments);
        if (!q.isEmpty()) {
            return q;
        }
        return imGetQueryText(step.data);
    }

    private static List<String> imGetGrepPatternsFromStep(IMToolStep step) {
        List<String> patterns = imGetGrepPatterns(step.arguments);
        if (patterns != null && !patterns.isEmpty()) {
            return patterns;
        }
        return imGetGrepPatterns(step.data);
    }

    private static List<String> listOf(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static String imAppendQueryTitle(String base, String query) {
        if (query == null || query.isEmpty()) {
            return base;
        }
        return base + "：「" + query + "」";
    }

    private static String imAppendPatternsTitle(String base, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return base;
        }
        List<String> display = patterns;
        String more = "";
        if (patterns.size() > 2) {
            display = patterns.subList(0, 2);
            more = " +" + (patterns.size() - 2);
        }
        return base + "：「" + String.join("、", display) + more + "」";
    }

    // ── 行格式化（tool_display.go L249-307） ─────────────────────────────

    /** agent 工具步一行（无 emoji；对齐 Web getToolTitle）。 */
    public static String formatIMToolLine(IMToolStep step) {
        String title = imAgentToolTitle(step);
        if (title.isEmpty()) {
            return "";
        }
        if (step.pending) {
            return title;
        }
        String summary = imToolResultSummary(step);
        if (!summary.isEmpty()) {
            return title + " · " + summary;
        }
        return title;
    }

    /** quick-QA RAG 管线一行（Web RagPipelineProgress）。 */
    public static String formatIMRagPipelineLine(IMToolStep step) {
        String toolName = step.toolName;
        String query = imGetQueryText(step.arguments);
        if (query.isEmpty()) {
            query = imGetQueryText(step.data);
        }

        switch (toolName) {
            case "query_understand":
                if (step.pending) {
                    return "正在理解问题...";
                }
                return "已完成问题理解";
            case "knowledge_search", "search_knowledge":
                String source = imRetrievalSearchSource(step);
                if (step.pending) {
                    switch (source) {
                        case RETRIEVAL_SOURCE_WEB:
                            return query.isEmpty() ? "正在检索网络..." : "正在检索网络：「" + query + "」";
                        case RETRIEVAL_SOURCE_MIXED:
                            return query.isEmpty() ? "正在检索知识库和网络..." : "正在检索知识库和网络：「" + query + "」";
                        default:
                            return query.isEmpty() ? "正在检索知识库..." : "正在检索知识库：「" + query + "」";
                    }
                }
                String base = imRetrievalDoneTitle(source, step.success);
                String line = imAppendQueryTitle(base, query);
                String summary = imKnowledgeSearchSummary(step.data);
                if (!summary.isEmpty()) {
                    return line + " · " + summary;
                }
                return line;
            default:
                return "";
        }
    }

    // ── 标题族（tool_display.go L309-487） ───────────────────────────────

    private static String imSandboxMutationTitle(IMToolStep step, boolean pending) {
        String name = imLocalizedToolName(step.toolName);
        String path = imSandboxFilePath(step);
        String stat = imSandboxDiffStat(step);
        String base = name;
        if (pending) {
            base = name + "...";
        }
        if (!path.isEmpty()) {
            base = imAppendQueryTitle(name, path);
            if (pending) {
                base += "...";
            }
        }
        if (!stat.isEmpty()) {
            return base + " " + stat;
        }
        return base;
    }

    private static String imSandboxFilePath(IMToolStep step) {
        if (step.data != null && step.data.get("path") instanceof String p && !p.strip().isEmpty()) {
            return p.strip();
        }
        if (step.arguments != null && step.arguments.get("path") instanceof String p && !p.strip().isEmpty()) {
            return p.strip();
        }
        return "";
    }

    private static String imSandboxDiffStat(IMToolStep step) {
        int added = imIntField(step.arguments, "added_lines");
        int removed = imIntField(step.arguments, "removed_lines");
        if (added == 0 && removed == 0) {
            added = imIntField(step.data, "added_lines");
            removed = imIntField(step.data, "removed_lines");
        }
        if (added > 0 && removed > 0) {
            return "+" + added + " -" + removed;
        }
        if (added > 0) {
            return "+" + added;
        }
        if (removed > 0) {
            return "-" + removed;
        }
        return "";
    }

    private static String imAgentToolTitle(IMToolStep step) {
        if (step.pending) {
            switch (step.toolName) {
                case "image_analysis":
                    return "正在查看图片内容...";
                case "wiki_search", "wiki_read_page":
                    return imLocalizedToolName(step.toolName) + "...";
                case "write_sandbox_file", "edit_sandbox_file":
                    return imSandboxMutationTitle(step, true);
                default:
                    return "正在调用 " + imLocalizedToolName(step.toolName) + "...";
            }
        }

        String toolName = step.toolName;
        boolean isSearchTool = toolName.equals("search_knowledge")
                || toolName.equals("knowledge_search") || toolName.equals("wiki_search");
        if (isSearchTool) {
            String base = imToolStatusDescription(step);
            String query = imGetQueryText(step.arguments);
            if (query.isEmpty()) {
                query = imGetQueryText(step.data);
            }
            return imAppendQueryTitle(base, query);
        }

        if (toolName.equals("web_search")) {
            return imAppendQueryTitle(imToolStatusDescription(step), imGetWebSearchQuery(step));
        }

        if (toolName.equals("grep_chunks")) {
            return imAppendPatternsTitle(imToolStatusDescription(step),
                    imGetGrepPatternsFromStep(step));
        }

        if (toolName.equals("wiki_read_page")) {
            String pageLabel = "";
            if (step.data != null && step.data.get("title") instanceof String title) {
                pageLabel = title.strip();
            }
            if (pageLabel.isEmpty()) {
                pageLabel = imGetWikiPageText(step.arguments);
            }
            if (pageLabel.isEmpty()) {
                pageLabel = imGetWikiPageText(step.data);
            }
            return imAppendQueryTitle(imToolStatusDescription(step), pageLabel);
        }

        if (toolName.equals("write_sandbox_file") || toolName.equals("edit_sandbox_file")) {
            return imSandboxMutationTitle(step, false);
        }

        String summary = imToolHeaderSummary(step);
        if (!summary.isEmpty()) {
            return summary;
        }
        return imToolStatusDescription(step);
    }

    private static String imToolStatusDescription(IMToolStep step) {
        boolean success = step.success;
        String toolName = step.toolName;

        switch (toolName) {
            case "search_knowledge", "knowledge_search":
                return success ? "检索知识库" : "检索知识库失败";
            case "wiki_search", "wiki_read_page": {
                String wikiName = imLocalizedToolName(toolName);
                return success ? wikiName : "调用 " + wikiName + " 失败";
            }
            case "web_search":
                return success ? "网络搜索" : "网络搜索失败";
            case "grep_chunks":
                return success ? "搜索关键词" : "搜索关键词失败";
            case "get_document_info":
                return success ? "获取文档信息" : "获取文档信息失败";
            case "get_document_content", "wiki_read_source_doc":
                return success ? "获取文档内容" : "获取文档内容失败";
            case "thinking":
                return success ? "完成思考" : "思考失败";
            case "todo_write":
                return success ? "更新任务列表" : "更新任务列表失败";
            case "image_analysis":
                return success ? "已查看图片内容" : "图片内容查看失败";
            case "query_understand":
                return success ? "已完成问题理解" : "调用 " + imLocalizedToolName(toolName) + " 失败";
            default:
                String name = imLocalizedToolName(toolName);
                return success ? "调用 " + name : "调用 " + name + " 失败";
        }
    }

    private static String imToolHeaderSummary(IMToolStep step) {
        if (step.pending || !step.success) {
            return "";
        }
        String toolName = step.toolName;
        Map<String, Object> data = step.data;

        switch (toolName) {
            case "search_knowledge", "knowledge_search":
                return "";
            case "get_document_info":
                if (data != null && data.get("title") instanceof String title && !title.strip().isEmpty()) {
                    return "获取文档：" + title.strip();
                }
                break;
            case "list_knowledge_chunks":
                if (data != null) {
                    if (data.get("faq_question") instanceof String question && !question.strip().isEmpty()) {
                        return "查看 FAQ：" + question.strip();
                    }
                    if (data.containsKey("fetched_chunks")) {
                        String title = "文档";
                        if (data.get("knowledge_title") instanceof String t && !t.strip().isEmpty()) {
                            title = t.strip();
                        } else if (data.get("knowledge_id") instanceof String id && !id.strip().isEmpty()) {
                            title = id.strip();
                        }
                        return "查看 " + title;
                    }
                }
                break;
            default:
                break;
        }
        return "";
    }

    private static String imToolResultSummary(IMToolStep step) {
        if (step.pending || !step.success) {
            return "";
        }
        switch (step.toolName) {
            case "search_knowledge", "knowledge_search":
                return imKnowledgeSearchSummary(step.data);
            case "web_search":
                return imWebSearchSummary(step.data);
            case "grep_chunks":
                return imGrepSearchSummary(step.data);
            case "list_knowledge_chunks":
                return imKnowledgeChunksSummary(step.data);
            default:
                return briefToolSummary(step.output);
            }
    }

    // ── 汇总行（tool_display.go L542-732） ───────────────────────────────

    static final String RETRIEVAL_SOURCE_KNOWLEDGE = "knowledge";
    static final String RETRIEVAL_SOURCE_WEB = "web";
    static final String RETRIEVAL_SOURCE_MIXED = "mixed";

    private static String imRetrievalSearchSource(IMToolStep step) {
        String fromData = imSearchSourceFromData(step.data);
        if (!fromData.isEmpty()) {
            return fromData;
        }
        if (step.arguments != null && step.arguments.get("search_source") instanceof String source
                && !source.isEmpty()) {
            return source;
        }
        return RETRIEVAL_SOURCE_KNOWLEDGE;
    }

    private static String imSearchSourceFromData(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        if (data.get("search_source") instanceof String source) {
            return source.strip();
        }
        return "";
    }

    private static String imRetrievalDoneTitle(String source, boolean success) {
        switch (source) {
            case RETRIEVAL_SOURCE_WEB:
                return success ? "网络检索" : "网络检索失败";
            case RETRIEVAL_SOURCE_MIXED:
                return success ? "检索知识库和网络" : "检索失败";
            default:
                return success ? "检索知识库" : "检索知识库失败";
        }
    }

    private static int imIntField(Map<String, Object> data, String key) {
        if (data == null) {
            return 0;
        }
        Object v = data.get(key);
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return l.intValue();
        }
        if (v instanceof Double d) {
            return d.intValue();
        }
        return 0;
    }

    static String imKnowledgeSearchSummary(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        int count = imResultCount(data);
        if (count == 0) {
            return "未找到匹配的内容";
        }
        String source = imSearchSourceFromData(data);
        int webCount = imIntField(data, "web_count");
        int docCount = imIntField(data, "doc_count");
        if (source.equals(RETRIEVAL_SOURCE_WEB) || (webCount > 0 && docCount == 0)) {
            return "找到 " + count + " 条网页";
        }
        if (data.get("kb_counts") instanceof Map<?, ?> kbCounts && !kbCounts.isEmpty()) {
            return "找到 " + count + " 个结果，来自 " + kbCounts.size() + " 个文件";
        }
        if (source.equals(RETRIEVAL_SOURCE_MIXED) && docCount > 0 && webCount > 0) {
            return "找到 " + count + " 个结果（" + docCount + " 篇文档，" + webCount + " 条网页）";
        }
        return "找到 " + count + " 个结果";
    }

    private static String imWebSearchSummary(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        int count = imResultCount(data);
        if (count == 0) {
            return "";
        }
        return "找到 " + count + " 个网络搜索结果";
    }

    static String imGrepSearchSummary(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        int totalChunks = 0;
        if (data.get("total_matches") instanceof Number n) {
            totalChunks = n.intValue();
        }
        if (totalChunks == 0) {
            return "未找到匹配的内容";
        }
        int docCount = imGrepDocumentCount(data);
        return "找到 " + totalChunks + " 个匹配片段，来自 " + docCount + " 个文档";
    }

    private static int imGrepDocumentCount(Map<String, Object> data) {
        if (data.get("document_count") instanceof Number n && n.doubleValue() >= 0) {
            return n.intValue();
        }
        if (data.get("knowledge_results") instanceof List<?> kr && !kr.isEmpty()) {
            return kr.size();
        }
        if (data.get("chunk_results") instanceof List<?> cr && !cr.isEmpty()) {
            return cr.size();
        }
        return 0;
    }

    static String imKnowledgeChunksSummary(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        if (!data.containsKey("fetched_chunks")) {
            return "";
        }
        int fetchedN = imNumericValue(data.get("fetched_chunks"));
        int totalN = imNumericValue(data.get("total_chunks"));
        String summary = "已加载 " + fetchedN + " / " + formatIMOptionalInt(totalN, data.get("total_chunks")) + " 个分块";
        int pageSize = imNumericValue(data.get("page_size"));
        if (totalN > pageSize && pageSize > 0) {
            int page = imNumericValue(data.get("page"));
            if (page <= 0) {
                page = 1;
            }
            summary += " · 第 " + page + " 页，每页 " + pageSize + " 个";
        }
        return summary;
    }

    private static int imResultCount(Map<String, Object> data) {
        if (data.get("results") instanceof List<?> results && !results.isEmpty()) {
            return results.size();
        }
        if (data.get("count") instanceof Number n && n.doubleValue() > 0) {
            return n.intValue();
        }
        return 0;
    }

    private static int imNumericValue(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Long l) {
            return l.intValue();
        }
        if (v instanceof Double d) {
            return d.intValue();
        }
        return 0;
    }

    private static String formatIMOptionalInt(int n, Object raw) {
        if (raw == null) {
            return "?";
        }
        if (raw instanceof String && n == 0) {
            return "?";
        }
        if (n == 0) {
            return "?";
        }
        return String.valueOf(n);
    }

    /** 逐步渲染成多行（空行跳过；结尾不留换行）。 */
    static String renderIMToolSteps(List<IMToolStep> steps, Function<IMToolStep, String> format) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (IMToolStep step : steps) {
            String line = format.apply(step);
            if (line.isEmpty()) {
                continue;
            }
            b.append(line).append('\n');
        }
        int end = b.length();
        while (end > 0 && b.charAt(end - 1) == '\n') {
            end--;
        }
        return b.substring(0, end);
    }

    // ── briefToolSummary（service.go L2452-2481，纯函数所以放这里共享） ────

    /** 从工具输出提取一行短摘要；结构化数据（JSON/XML）与空输出返回空。 */
    public static String briefToolSummary(String output) {
        final int maxRunes = 40;
        if (output == null || output.isEmpty()) {
            return "";
        }
        output = output.strip();
        if (output.isEmpty()) {
            return "";
        }
        char first = output.charAt(0);
        if (first == '{' || first == '[' || first == '<') {
            return "";
        }
        int idx = output.indexOf('\n');
        if (idx >= 0) {
            output = output.substring(0, idx).strip();
        }
        if (output.isEmpty()) {
            return "";
        }
        if (output.codePointCount(0, output.length()) > maxRunes) {
            return output.substring(0, output.offsetByCodePoints(0, maxRunes)) + "...";
        }
        return output;
    }
}
