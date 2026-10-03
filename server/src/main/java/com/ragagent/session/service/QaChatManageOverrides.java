package com.ragagent.session.service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agent.management.service.AgentConfigJson;
import com.ragagent.chatpipeline.ChatManage;

/**
 * {@code SessionQaResolution} 的**agent 覆盖簇**：把 custom agent 的配置
 * （system_prompt / context / 采样与检索参数 / 各能力开关）覆盖到 {@code ChatManage} 上，
 * 以及从 agentRow + 配置读出提示词（{@code resolveCustomAgentPrompts}）。
 *
 * <p>公共嵌套类型 {@code SessionQaResolution.Prompts} 留门面（类型不能委托）；已迁协作者按字段转发；
 * 门面对每个搬走成员留一行薄委托。</p>
 */
final class QaChatManageOverrides {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(QaChatManageOverrides.class);

    private final SessionKnowledgeQaService service;

    QaChatManageOverrides(SessionKnowledgeQaService service) {
        this.service = service;
    }

    void applyAgentOverridesToChatManage(QaSupport.QaRequest req, ChatManage cm) {
        if (req.agentConfig == null) {
            return;
        }
        ObjectNode c = AgentConfigJson.ensureDefaults(req.agentConfig);
        SessionQaResolution.Prompts prompts = resolveCustomAgentPrompts(req.agentRow, c);
        if (!prompts.system().isEmpty()) {
            cm.getSummaryConfig().setPrompt(prompts.system());
            log.info("Using custom agent's system_prompt");
        }
        if (!prompts.context().isEmpty()) {
            cm.getSummaryConfig().setContextTemplate(prompts.context());
            log.info("Using custom agent's context_template");
        }
        double temperature = c.path("temperature").asDouble(-1);
        if (temperature >= 0) {
            cm.getSummaryConfig().setTemperature(temperature);
        }
        int maxCompletionTokens = c.path("maxCompletionTokens").asInt(0);
        if (maxCompletionTokens > 0) {
            cm.getSummaryConfig().setMaxCompletionTokens(maxCompletionTokens);
        }
        JsonNode thinking = c.get("thinking");
        cm.getSummaryConfig().setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);
        cm.setCitationEnabled(c.path("citationEnabled").asBoolean(true));
        int embeddingTopK = c.path("embeddingTopK").asInt(0);
        if (embeddingTopK > 0) {
            cm.setEmbeddingTopK(embeddingTopK);
        }
        double keywordThreshold = c.path("keywordThreshold").asDouble(0);
        if (keywordThreshold > 0) {
            cm.setKeywordThreshold(keywordThreshold);
        }
        double vectorThreshold = c.path("vectorThreshold").asDouble(0);
        if (vectorThreshold > 0) {
            cm.setVectorThreshold(vectorThreshold);
        }
        int rerankTopK = c.path("rerankTopK").asInt(0);
        if (rerankTopK > 0) {
            cm.setRerankTopK(rerankTopK);
        }
        cm.setRerankThreshold(c.path("rerankThreshold").asDouble(0));
        String rerankModelId = c.path("rerankModelId").asText("");
        if (!rerankModelId.isEmpty()) {
            cm.setRerankModelId(rerankModelId);
        }
        cm.setEnableRewrite(c.path("enableRewrite").asBoolean(false));
        cm.setEnableQueryExpansion(c.path("enableQueryExpansion").asBoolean(false));
        String rwSys = c.path("rewritePromptSystem").asText("");
        if (!rwSys.isEmpty()) {
            cm.setRewritePromptSystem(rwSys);
        }
        String rwUser = c.path("rewritePromptUser").asText("");
        if (!rwUser.isEmpty()) {
            cm.setRewritePromptUser(rwUser);
        }
        String quModel = c.path("queryUnderstandModelId").asText("");
        if (!quModel.isEmpty()) {
            cm.setQueryUnderstandModelId(quModel);
        }
        String fallbackStrategy = c.path("fallbackStrategy").asText("");
        if (!fallbackStrategy.isEmpty()) {
            cm.setFallbackStrategy(fallbackStrategy);
        }
        String fallbackResponse = c.path("fallbackResponse").asText("");
        if (!fallbackResponse.isEmpty()) {
            cm.setFallbackResponse(fallbackResponse);
        }
        String fallbackPrompt = c.path("fallbackPrompt").asText("");
        if (!fallbackPrompt.isEmpty()) {
            cm.setFallbackPrompt(fallbackPrompt);
        }
        int webSearchMaxResults = c.path("webSearchMaxResults").asInt(0);
        if (webSearchMaxResults > 0) {
            cm.setWebSearchMaxResults(webSearchMaxResults);
        }
        int historyTurns = c.path("historyTurns").asInt(0);
        if (historyTurns > 0) {
            cm.setMaxRounds(historyTurns);
            log.info("Using custom agent's history_turns: {}", cm.getMaxRounds());
        }
        if (!c.path("multiTurnEnabled").asBoolean(true)) {
            cm.setMaxRounds(0);
            log.info("Multi-turn disabled by custom agent, clearing history");
        }
        cm.setFaqPriorityEnabled(c.path("faqPriorityEnabled").asBoolean(false));
        cm.setFaqDirectAnswerThreshold(c.path("faqDirectAnswerThreshold").asDouble(0.0));
        cm.setFaqScoreBoost(c.path("faqScoreBoost").asDouble(0.0));
        cm.setDataAnalysisEnabled(c.path("dataAnalysisEnabled").asBoolean(false));
        JsonNode intentPrompts = c.get("intentPrompts");
        if (intentPrompts != null && intentPrompts.isObject() && intentPrompts.size() > 0) {
            Map<String, String> overrides = new LinkedHashMap<>();
            intentPrompts.fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue().asText("")));
            cm.setIntentPromptOverrides(overrides);
        }
    }
    SessionQaResolution.Prompts resolveCustomAgentPrompts(
            com.ragagent.agent.management.domain.CustomAgentEntity agent, ObjectNode c) {
        if (c == null) {
            return new SessionQaResolution.Prompts("", "");
        }
        String system = c.path("systemPrompt").asText("");
        String context = c.path("contextTemplate").asText("");
        boolean agentMode = SessionKnowledgeQaService.isAgentMode(c);
        String systemId = c.path("systemPromptId").asText("");
        if (system.isEmpty() && !systemId.isEmpty()) {
            String content = SessionQaResolution.templateContentByIdAndFile(systemId,
                    agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");
            if (content != null) {
                system = content;
            }
        }
        String contextId = c.path("contextTemplateId").asText("");
        if (context.isEmpty() && !contextId.isEmpty()) {
            String content = SessionQaResolution.templateContentByIdAndFile(contextId, "context_template.yaml");
            if (content != null) {
                context = content;
            }
        }
        return new SessionQaResolution.Prompts(system, context);
    }
}
