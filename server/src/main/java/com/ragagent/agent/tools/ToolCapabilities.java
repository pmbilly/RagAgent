package com.ragagent.agent.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内建工具对 KB 能力的要求（与 {@code frontend/src/utils/tool-capabilities.ts}
 * 镜像，两者必须同步）。
 *
 * <p>前端用它置灰工具、过滤 agent 编辑器与 {@code @} 菜单里的 KB；后端把它作为
 * 检索管线的最后防线——绕过前端过滤的客户端（旧标签页、curl、恶意插件）不能把
 * 不兼容的 KB/文件塞给只会静默跳过它们的工具。</p>
 */
public final class ToolCapabilities {

    /** KB 能力位（值为 KB 能力 JSON 键）。 */
    public enum KbCapability {
        VECTOR("vector"),
        KEYWORD("keyword"),
        WIKI("wiki"),
        GRAPH("graph"),
        FAQ("faq");

        public final String value;

        KbCapability(String value) {
            this.value = value;
        }
    }

    /** KB 能力快照（五个 bool）。 */
    public record KbCaps(boolean vector, boolean keyword, boolean wiki, boolean graph, boolean faq) {

        public static final KbCaps NONE = new KbCaps(false, false, false, false, false);
    }

    /**
     * 工具对 KB 作用域的要求：
     * anyOf = 作用域至少暴露一个列出的能力；allOf = 全部暴露；consumesFiles =
     * 工具从 knowledge_ids 读用户文件引用（聊天输入据此决定是否给 @file 列表）。
     * null/零值 = 无 KB 依赖——永远可用且不关心文件。
     */
    public record ToolRequirement(List<KbCapability> anyOf, List<KbCapability> allOf, boolean consumesFiles) {

        public static final ToolRequirement NONE = new ToolRequirement(List.of(), List.of(), false);

        public static ToolRequirement chunkRetrieval() {
            return new ToolRequirement(List.of(KbCapability.VECTOR, KbCapability.KEYWORD), List.of(), true);
        }

        public static ToolRequirement wikiOnly() {
            return new ToolRequirement(List.of(), List.of(KbCapability.WIKI), false);
        }
    }

    /**
     * 工具名 → 能力要求（不在表里的工具默认
     * "无要求"，视为永远可用/可能消费文件（宽容回退：未知 MCP 工具不应静默坏掉））。
     * 与 frontend/src/utils/tool-capabilities.ts 保持对齐。
     */
    public static final Map<String, ToolRequirement> TOOL_CAPABILITY_REQUIREMENTS = buildRequirements();

    private static Map<String, ToolRequirement> buildRequirements() {
        Map<String, ToolRequirement> m = new LinkedHashMap<>();
        // ---- 基础/推理（无 KB 依赖，不消费文件）----
        m.put("thinking", ToolRequirement.NONE);
        m.put("todo_write", ToolRequirement.NONE);
        // ---- RAG / chunk 检索（需要至少一个建了 chunk 索引的 KB）----
        m.put("knowledge_search", ToolRequirement.chunkRetrieval());
        m.put("grep_chunks", ToolRequirement.chunkRetrieval());
        m.put("list_knowledge_chunks", ToolRequirement.chunkRetrieval());
        m.put("query_knowledge_graph", ToolRequirement.chunkRetrieval());
        m.put("get_document_info", ToolRequirement.chunkRetrieval());
        m.put("database_query", ToolRequirement.chunkRetrieval());
        // ---- Wiki（操作 wiki 页面；不消费任意文件 ID）----
        m.put("wiki_search", ToolRequirement.wikiOnly());
        m.put("wiki_read_page", ToolRequirement.wikiOnly());
        m.put("wiki_read_source_doc", ToolRequirement.wikiOnly());
        m.put("wiki_flag_issue", ToolRequirement.wikiOnly());
        m.put("wiki_write_page", ToolRequirement.wikiOnly());
        m.put("wiki_replace_text", ToolRequirement.wikiOnly());
        m.put("wiki_rename_page", ToolRequirement.wikiOnly());
        m.put("wiki_delete_page", ToolRequirement.wikiOnly());
        m.put("wiki_read_issue", ToolRequirement.wikiOnly());
        m.put("wiki_update_issue", ToolRequirement.wikiOnly());
        // ---- 数据分析（读 RAG ingest 出的表格摘要/列 chunk）----
        m.put("data_analysis", ToolRequirement.chunkRetrieval());
        m.put("data_schema", ToolRequirement.chunkRetrieval());
        return Map.copyOf(m);
    }

    private static boolean hasCap(KbCaps caps, KbCapability c) {
        return switch (c) {
            case VECTOR -> caps.vector();
            case KEYWORD -> caps.keyword();
            case WIKI -> caps.wiki();
            case GRAPH -> caps.graph();
            case FAQ -> caps.faq();
        };
    }

    /** 派生的 "KB 至少暴露其中一个能力" 过滤器（anyOf 为 null/空 = 无约束）。 */
    public record KbFilter(List<KbCapability> anyOf) {

        public static final KbFilter EMPTY = new KbFilter(List.of());

        public boolean isEmpty() {
            return anyOf == null || anyOf.isEmpty();
        }
    }

    /**
     * 从工具集派生能力过滤器：一个 KB 通过当且仅当至少一个放行工具的要求被它满足。
     * 无 KB 要求的工具不贡献——只含这类工具时返回空过滤器（全部放行）。
     */
    public static KbFilter deriveKbFilterFromTools(Collection<String> allowedTools) {
        Set<KbCapability> seen = new LinkedHashSet<>();
        for (String t : allowedTools) {
            ToolRequirement req = TOOL_CAPABILITY_REQUIREMENTS.get(t);
            if (req == null) {
                continue;
            }
            seen.addAll(req.anyOf());
            seen.addAll(req.allOf());
        }
        if (seen.isEmpty()) {
            return KbFilter.EMPTY;
        }
        return new KbFilter(new ArrayList<>(seen));
    }

    /**
     * 单个 KB 是否与 agent 的工具集兼容：KB 暴露了 {@code allowedTools} 中某工具需要的
     * 至少一个能力。工具集没有任何 KB 要求时，一切 KB 兼容。
     */
    public static boolean kbSatisfiesToolRequirements(KbCaps caps, Collection<String> allowedTools) {
        KbFilter f = deriveKbFilterFromTools(allowedTools);
        if (f.isEmpty()) {
            return true;
        }
        for (KbCapability c : f.anyOf()) {
            if (hasCap(caps, c)) {
                return true;
            }
        }
        return false;
    }

    /** "quick-answer"（RAG）agent 模式的隐式能力要求：纯靠 vector/keyword chunk 搜索。 */
    private static final KbFilter QUICK_ANSWER_KB_FILTER =
            new KbFilter(List.of(KbCapability.VECTOR, KbCapability.KEYWORD));

    /**
     * 给定 agent 配置派生有效 KB 过滤器：
     * agentMode 的隐式约束（quick-answer 强制 vector|keyword）与工具派生过滤器的
     * <b>并集</b>——沿用 any_of 语义：KB 至少暴露其一即通过。
     */
    public static KbFilter deriveKbFilterForAgent(String agentMode, Collection<String> allowedTools) {
        Set<KbCapability> seen = new LinkedHashSet<>();
        if ("quick-answer".equals(agentMode)) {
            seen.addAll(QUICK_ANSWER_KB_FILTER.anyOf());
        }
        seen.addAll(deriveKbFilterFromTools(allowedTools).anyOf());
        if (seen.isEmpty()) {
            return KbFilter.EMPTY;
        }
        return new KbFilter(new ArrayList<>(seen));
    }

    /** agent 感知版：同时强制 agentMode 的隐式能力约束。 */
    public static boolean kbSatisfiesAgentRequirements(KbCaps caps, String agentMode, Collection<String> allowedTools) {
        KbFilter f = deriveKbFilterForAgent(agentMode, allowedTools);
        if (f.isEmpty()) {
            return true;
        }
        for (KbCapability c : f.anyOf()) {
            if (hasCap(caps, c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 放行工具列表里是否有工具能使用用户文件引用，
     * 用于聊天输入的 @file 列表门控。空列表视为"未知 → 宽容"；未知工具
     * （MCP 工具、未登记的新内建）同样按可能消费文件处理——避免刚加了自定义工具
     * 的用户突然找不到文件选择器。
     */
    public static boolean toolsConsumeFiles(Collection<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return true;
        }
        for (String t : allowedTools) {
            ToolRequirement req = TOOL_CAPABILITY_REQUIREMENTS.get(t);
            if (req == null) {
                return true;
            }
            if (req.consumesFiles()) {
                return true;
            }
        }
        return false;
    }

    private ToolCapabilities() {
    }
}
