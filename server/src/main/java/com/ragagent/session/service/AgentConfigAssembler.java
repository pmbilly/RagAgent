package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ragagent.agent.tools.ToolDefinitions;
import com.ragagent.agent.management.service.AgentConfigJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * {@code SessionAgentQaService} 的**配置装配簇**：从请求 + 知识库/文档信息构造
 * {@code QaAgentConfig}（系统提示词、per-request skill/MCP 范围收敛、rerank 模型按需装配），
 * 以及 skill 模板文件的读取（{@code templateContentByIdAndFile} + YAML 前置元数据）。
 *
 * <p>为什么单独一类：这一簇只管"把请求装配成引擎配置"，与引擎创建、工具注册、历史装配三簇无交集。
 * 两个共享项随构造器注入：{@code knowledgeQa}（KB 解析/检索面）与 {@code hostSkillDirs}（skill 模板
 * 目录）。边界按调用点定——{@code stringListOf} 与 {@code YAML_JSON} 虽在文件尾部，调用点全在
 * {@code buildAgentConfig}，故随本簇走。</p>
 */
final class AgentConfigAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigAssembler.class);

    private final SessionKnowledgeQaService knowledgeQa;

    private final List<String> hostSkillDirs;

    AgentConfigAssembler(SessionKnowledgeQaService knowledgeQa, List<String> hostSkillDirs) {
        this.knowledgeQa = knowledgeQa;
        this.hostSkillDirs = hostSkillDirs;
    }

    QaAgentConfig buildAgentConfig(QaSupport.QaRequest req, long agentTenantId) {
        ObjectNode c = AgentConfigJson.ensureDefaults(req.agentConfig);
        QaAgentConfig ac = new QaAgentConfig();
        ac.setMaxIterations(c.path("maxIterations").asInt(0));
        // temperature==0 视为未配置（0 不单独出键）。内建 agent 的 0.7 来自
        // agent_type_presets.yaml 显式配置，不靠此缺省。
        ac.setTemperature(c.path("temperature").asDouble(0.0));
        ac.setWebSearchEnabled(c.path("webSearchEnabled").asBoolean(false) && req.webSearchEnabled);
        ac.setWebSearchMaxResults(c.path("webSearchMaxResults").asInt(0));
        ac.setWebSearchProviderId(c.path("webSearchProviderId").asText(""));
        ac.setMultiTurnEnabled(c.path("multiTurnEnabled").asBoolean(true));
        ac.setHistoryTurns(c.path("historyTurns").asInt(0));
        ac.setMemoryEnabled(c.path("memoryEnabled").asBoolean(false));
        ac.setMcpSelectionMode(c.path("mcpSelectionMode").asText(""));
        // MCPServices 直取 agent config 的 mcp_services
        // （mode=selected 时按 ID 列表注册；mode=all 由注册处列全租户）
        ac.setMcpServices(stringListOf(c.get("mcpServices")));
        ac.setMcpAuthWaitTimeout(c.path("mcpAuthWaitTimeout").asInt(0));
        JsonNode thinking = c.get("thinking");
        ac.setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);
        ac.setCitationEnabled(c.path("citationEnabled").asBoolean(true));
        ac.setRetrieveKbOnlyWhenMentioned(c.path("retrieveKbOnlyWhenMentioned").asBoolean(false));
        ac.setLlmCallTimeout(c.path("llmCallTimeout").asInt(0));
        ac.setMaxCompletionTokens(c.path("maxCompletionTokens").asInt(0));
        ac.setRetainRetrievalHistory(c.path("retainRetrievalHistory").asBoolean(false));
        ac.setSharedAgentReadOnly(req.sharedAgentReadOnly);

        // skills 配置（configureSkillsFromAgent，Go L616-647）；指令型数据源 =
        // 宿主技能目录（选项 B），不再有沙箱镜像技能集
        String skillsMode = c.path("skillsSelectionMode").asText("");
        switch (skillsMode) {
            case "all" -> {
                ac.setSkillsEnabled(true);
                ac.setAllowedSkills(null);
                log.info("SkillsSelectionMode=all: using installed sandbox skills");
            }
            case "selected" -> {
                List<String> selected = stringListOf(c.get("selectedSkills"));
                if (!selected.isEmpty()) {
                    ac.setSkillsEnabled(true);
                    ac.setAllowedSkills(selected);
                    log.info("SkillsSelectionMode=selected: enabled {} selected skills: {}", selected.size(), selected);
                } else {
                    ac.setSkillsEnabled(false);
                    log.info("SkillsSelectionMode=selected but no skills selected: skills disabled");
                }
            }
            case "none", "" -> {
                ac.setSkillsEnabled(false);
                log.info("SkillsSelectionMode={}: skills disabled", skillsMode);
            }
            default -> {
                ac.setSkillsEnabled(false);
                log.warn("Unknown SkillsSelectionMode={}: skills disabled", skillsMode);
            }
        }

        // 指令型技能（选项 B）：目录来自 weknora.skills.host-dirs，Loader 扫描
        // SKILL.md；allowedSkills 即 selected_skills 过滤
        ac.setSkillDirs(hostSkillDirs);

        // Resolve knowledge bases
        var kb = knowledgeQa.resolveKnowledgeBases(req);
        ac.setKnowledgeBases(kb.kbIds());
        ac.setKnowledgeIds(kb.knowledgeIds());

        // Allowed tools
        List<String> allowed = stringListOf(c.get("allowedTools"));
        ac.setAllowedTools(allowed.isEmpty()
                ? new ArrayList<>(com.ragagent.agent.tools.ToolDefinitions.defaultAllowedTools())
                : allowed);

        // Per-request skill/MCP scope（Go L364-366）
        applyPerRequestSkillScope(ac, skillsMode, req.skillNames);
        applyPerRequestMcpScope(ac, stringListOf(c.get("mcpServices")),
                req.sharedAgentReadOnly, req.mcpServiceIds);

        // Custom system prompt（Go L369-372）
        Prompts prompts = resolveAgentPrompts(req);
        if (!prompts.system().isEmpty()) {
            ac.setUseCustomSystemPrompt(true);
            ac.setSystemPrompt(prompts.system());
        }

        log.info("Custom agent config applied: MaxIterations={}, Temperature={}, AllowedTools={}, WebSearchEnabled={}",
                ac.getMaxIterations(), ac.getTemperature(), ac.getAllowedTools(), ac.isWebSearchEnabled());

        // Web search max results（tenant 兜底 5）
        if (ac.getWebSearchMaxResults() == 0) {
            ac.setWebSearchMaxResults(5);
        }
        // web_search provider 默认解析（Go L387-390）由 handler 层 web repo 完成，dev 空。

        log.info("Merged agent config from tenant {} and session {}", agentTenantId, req.session.getId());

        // Search targets（Go L407-424）
        List<SessionKnowledgeQaService.SearchTargetView> targets;
        try {
            targets = knowledgeQa.buildSearchTargets(agentTenantId, ac.getKnowledgeBases(),
                    ac.getKnowledgeIds(), req.tagScopes);
        } catch (RuntimeException e) {
            throw new RuntimeException("build search targets: " + e.getMessage(), e);
        }
        ac.setSearchTargets(new SearchTargets(SessionKnowledgeQaService.SearchTargetView.toPipeline(targets)));
        log.info("Agent search targets built: {} targets", targets.size());

        return ac;
    }
    private record Prompts(String system, String context) {}
    private Prompts resolveAgentPrompts(QaSupport.QaRequest req) {
        ObjectNode c = req.agentConfig;
        String system = c.path("systemPrompt").asText("");
        String context = c.path("contextTemplate").asText("");
        boolean agentMode = SessionKnowledgeQaService.isAgentMode(c);
        if (system.isEmpty()) {
            String id = c.path("systemPromptId").asText("");
            if (!id.isEmpty()) {
                String resolved = templateContentByIdAndFile(id,
                        agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");
                if (resolved != null) {
                    system = resolved;
                }
            }
        }
        if (context.isEmpty()) {
            String id = c.path("contextTemplateId").asText("");
            if (!id.isEmpty()) {
                String resolved = templateContentByIdAndFile(id, "context_template.yaml");
                if (resolved != null) {
                    context = resolved;
                }
            }
        }
        return new Prompts(system, context);
    }
    /** applyPerRequestSkillScope（Go L464-487）。 */
    private static void applyPerRequestSkillScope(QaAgentConfig ac, String skillsMode, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        if ("none".equals(skillsMode) || skillsMode.isEmpty()) {
            log.warn("Ignoring @skill mention: agent skills selection is disabled (mode={})", skillsMode);
            return;
        }
        if (!ac.isSkillsEnabled()) {
            return;
        }
        List<String> allowed = ac.getAllowedSkills() == null ? new ArrayList<>() : ac.getAllowedSkills();
        ac.setPinnedSkillNames(pinPreservingRequestOrder(requested, allowed));
        log.info("Applied per-request @skill scope: requested={} effective={} pinned={}",
                requested, allowed, ac.getPinnedSkillNames());
    }
    /** applyPerRequestMCPScope（Go L492-518）。 */
    private static void applyPerRequestMcpScope(QaAgentConfig ac, List<String> agentPresetMcps,
            boolean isSharedAgent, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        if ("none".equals(ac.getMcpSelectionMode())) {
            log.warn("Ignoring @MCP mention: agent MCP selection is disabled (mode=none)");
            return;
        }
        List<String> mentioned = dedupPreservingOrder(requested);
        var scope = resolvePerRequestMcpScope(mentioned, agentPresetMcps, ac.getMcpSelectionMode(), isSharedAgent);
        if (scope.effective().isEmpty()) {
            log.warn("Ignoring @MCP scope outside agent preset: requested={} agent={} shared={}",
                    requested, agentPresetMcps, isSharedAgent);
            return;
        }
        ac.setPinnedMcpServiceIds(scope.effective());
        log.info("Applied per-request @MCP priority: requested={} mode={} pinned={}",
                requested, ac.getMcpSelectionMode(), scope.effective());
    }
    private record McpScope(List<String> effective, String mode) {}
    private static McpScope resolvePerRequestMcpScope(List<String> mentioned, List<String> agentMcps,
            String selectionMode, boolean isSharedAgent) {
        if (mentioned.isEmpty()) {
            return new McpScope(new ArrayList<>(), selectionMode);
        }
        if (isSharedAgent) {
            mentioned = intersectPreservingRequestOrder(mentioned, agentMcps);
            if (mentioned.isEmpty()) {
                return new McpScope(new ArrayList<>(), selectionMode);
            }
        }
        List<String> effective = new ArrayList<>();
        switch (selectionMode) {
            case "none" -> {
                return new McpScope(new ArrayList<>(), selectionMode);
            }
            case "selected" -> effective = intersectPreservingRequestOrder(mentioned, agentMcps);
            case "all", "" -> effective = new ArrayList<>(mentioned);
            default -> effective = new ArrayList<>(mentioned);
        }
        if (effective.isEmpty()) {
            return new McpScope(new ArrayList<>(), selectionMode);
        }
        return new McpScope(effective, "selected");
    }
    private static List<String> intersectPreservingRequestOrder(List<String> requested, List<String> allowed) {
        var allowedSet = new java.util.LinkedHashSet<>(allowed);
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : requested) {
            if (value.isEmpty() || seen.contains(value) || !allowedSet.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }
    private static List<String> pinPreservingRequestOrder(List<String> requested, List<String> allowed) {
        boolean allowedAll = allowed.isEmpty();
        var allowedSet = new java.util.LinkedHashSet<>(allowed);
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : requested) {
            if (value.isEmpty() || seen.contains(value)) {
                continue;
            }
            if (!allowedAll && !allowedSet.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }
    private static List<String> dedupPreservingOrder(List<String> values) {
        List<String> result = new ArrayList<>();
        var seen = new java.util.LinkedHashSet<String>();
        for (String value : values) {
            if (value.isEmpty() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }
    /** agentRequiresRerankModel（org 包 AgentShareService L368 同源；agent/tools 的判定）。 */
    static boolean agentRequiresRerankModel(ObjectNode c) {
        List<String> allowed = new ArrayList<>();
        JsonNode arr = c.get("allowedTools");
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> allowed.add(n.asText()));
        }
        if (allowed.isEmpty()) {
            return true; // DefaultAllowedTools 含 knowledge_search
        }
        return allowed.contains(ToolDefinitions.TOOL_KNOWLEDGE_SEARCH);
    }
    private static List<String> stringListOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper YAML_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static String templateContentByIdAndFile(String id, String file) {
        try (java.io.InputStream in = SessionAgentQaService.class.getClassLoader()
                .getResourceAsStream("agent/management/prompt_templates/" + file)) {
            if (in == null) {
                return null;
            }
            Object raw = new org.yaml.snakeyaml.Yaml().load(in);
            com.fasterxml.jackson.databind.JsonNode root = YAML_JSON.valueToTree(raw);
            com.fasterxml.jackson.databind.JsonNode list = root.get("templates");
            if (list == null || !list.isArray()) {
                return null;
            }
            for (com.fasterxml.jackson.databind.JsonNode t : list) {
                if (id.equals(t.path("id").asText(""))) {
                    return t.path("content").asText("");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
