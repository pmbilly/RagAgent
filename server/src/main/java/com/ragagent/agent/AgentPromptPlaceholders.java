package com.ragagent.agent;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 提示词占位符的统一渲染（对照 Go internal/types/placeholder.go L85-230）。
 *
 * <p>波 4.2 只需要 agent 模式用到的子集：{@code PlaceholdersByField} 的
 * agent_system_prompt 分支与 {@code RenderPromptPlaceholders}。完整的字段表已在
 * agentm 的 {@code AgentPlaceholders}（HTTP 面）落地，勿重复。</p>
 *
 * <p><b>自动值</b>（调用方未提供时补齐）：{{current_time}} 只用日期不用时钟——
 * 秒级变化会打断 provider 前缀缓存；{{current_week}} 星期名；{{yesterday}} 昨日日期。
 * 未知占位符原样保留。Go 侧 autoFill 会写入传入的 map；Java 侧同样原地写
 * （调用方传 {@link LinkedHashMap} 时语义一致）。</p>
 */
public final class AgentPromptPlaceholders {

    /** 占位符定义（对照 PromptPlaceholder：name/label/description）。 */
    public record PromptPlaceholder(String name, String label, String description) {

        public PromptPlaceholder {
            name = name == null ? "" : name;
            label = label == null ? "" : label;
            description = description == null ? "" : description;
        }
    }

    // 对照 placeholder.go 的 agent 分支占位符（L74-101）
    public static final PromptPlaceholder PLACEHOLDER_KNOWLEDGE_BASES = new PromptPlaceholder(
            "knowledge_bases", "知识库列表",
            "自动格式化的知识库列表，包含名称、描述、文档数量等信息");
    public static final PromptPlaceholder PLACEHOLDER_WEB_SEARCH_STATUS = new PromptPlaceholder(
            "web_search_status", "网络搜索状态",
            "网络搜索工具是否启用的状态（Enabled 或 Disabled）");
    public static final PromptPlaceholder PLACEHOLDER_CURRENT_TIME = new PromptPlaceholder(
            "current_time", "当前时间",
            "当前日期（ISO 格式：2006-01-02）。只用日期、不用时钟，避免秒级变化打断 provider 前缀缓存。");
    public static final PromptPlaceholder PLACEHOLDER_LANGUAGE = new PromptPlaceholder(
            "language", "用户语言",
            "用户界面的语言偏好，如 Chinese (Simplified)、English、Korean 等，用于控制 LLM 回答语言");

    /** agent_system_prompt 字段的可用占位符（对照 PlaceholdersByField 的 agent 分支）。 */
    public static List<PromptPlaceholder> placeholdersByFieldAgentSystemPrompt() {
        return List.of(
                PLACEHOLDER_KNOWLEDGE_BASES,
                PLACEHOLDER_WEB_SEARCH_STATUS,
                PLACEHOLDER_CURRENT_TIME,
                PLACEHOLDER_LANGUAGE);
    }

    private AgentPromptPlaceholders() {
    }

    /**
     * 把模板里所有 {@code {{key}}} 替换成 vals 对应的值（对照 RenderPromptPlaceholders）。
     * 未知占位符原样保留。
     */
    public static String renderPromptPlaceholders(String template, Map<String, String> vals) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        // 调用方没提供时补自动值
        autoFill(template, vals, "current_time",
                LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        autoFill(template, vals, "current_week",
                LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        autoFill(template, vals, "yesterday",
                LocalDate.now().minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));

        String result = template;
        for (Map.Entry<String, String> e : vals.entrySet()) {
            String placeholder = "{{" + e.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, e.getValue());
            }
        }
        return result;
    }

    /** 对照 autoFill：键不存在且模板引用了该占位符时才写入。 */
    private static void autoFill(String template, Map<String, String> vals, String key, String value) {
        if (!vals.containsKey(key) && template.contains("{{" + key + "}}")) {
            vals.put(key, value);
        }
    }
}
