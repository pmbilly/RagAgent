package com.ragagent.agent;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * agent 系统提示词模板的选取与装载。
 *
 * <p>模板文件 vendored 到 {@code agent/management/prompt_templates/agent_system_prompt.yaml}，
 * 启动时从 classpath 装载。yaml 解析沿用 agent.management BuiltinAgentRegistry 的
 * snakeyaml → ObjectNode 组合（classpath 无 jackson-dataformat-yaml）。</p>
 */
public final class AgentPromptTemplates {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单个提示词模板（content/mode/default 为主消费字段）。 */
    public record PromptTemplate(
            String id,
            String name,
            String description,
            String content,
            boolean dflt,
            String mode) {

        public PromptTemplate {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            content = content == null ? "" : content;
            mode = mode == null ? "" : mode;
        }
    }

    /** 模板配置（只消费 agentSystemPrompt 列表）。 */
    public record TemplatesConfig(List<PromptTemplate> agentSystemPrompt) {
    }

    private AgentPromptTemplates() {
    }

    /** 返回列表里第一个标记 default 的模板，否则第一个，否则 null。 */
    public static PromptTemplate defaultTemplate(List<PromptTemplate> templates) {
        if (templates == null) {
            return null;
        }
        for (PromptTemplate t : templates) {
            if (t.dflt()) {
                return t;
            }
        }
        return templates.isEmpty() ? null : templates.get(0);
    }

    /** 按 mode 过滤后的默认模板。 */
    public static PromptTemplate defaultTemplateByMode(List<PromptTemplate> templates, String mode) {
        if (templates != null) {
            for (PromptTemplate t : templates) {
                if (t.mode().equals(mode) && t.dflt()) {
                    return t;
                }
            }
            for (PromptTemplate t : templates) {
                if (t.mode().equals(mode)) {
                    return t;
                }
            }
        }
        return defaultTemplate(templates);
    }

    /**
     * 从 classpath 装载 agent_system_prompt.yaml 的模板列表。
     * 文件缺失/损坏返回空列表。
     */
    public static List<PromptTemplate> loadAgentSystemPromptTemplates() {
        try (var in = AgentPromptTemplates.class.getClassLoader()
                .getResourceAsStream("agent/management/prompt_templates/agent_system_prompt.yaml")) {
            if (in == null) {
                return List.of();
            }
            JsonNode root = MAPPER.valueToTree(new org.yaml.snakeyaml.Yaml().load(in));
            JsonNode list = root.get("templates");
            if (list == null || !list.isArray()) {
                return List.of();
            }
            var out = new java.util.ArrayList<PromptTemplate>();
            for (JsonNode t : list) {
                out.add(new PromptTemplate(
                        t.path("id").asText(""),
                        t.path("name").asText(""),
                        t.path("description").asText(""),
                        t.path("content").asText(""),
                        t.path("default").asBoolean(false),
                        t.path("mode").asText("")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 便捷：从装载的模板列表按 mode 取默认内容；缺 → ""。 */
    public static String defaultContentByMode(List<PromptTemplate> templates, String mode) {
        PromptTemplate t = defaultTemplateByMode(templates, mode);
        return t == null ? "" : t.content();
    }
}
