package com.ragagent.session.service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.service.AgentConfigJson;
import com.ragagent.chatpipeline.ChatManage;

/**
 * {@code SessionQaResolution} 的**agent 覆盖簇**（§11.37 第 2 步）：把 custom agent 的配置
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



        int maxCompletionTokens = c.path("max_completion_tokens").asInt(0);



        if (maxCompletionTokens > 0) {



            cm.getSummaryConfig().setMaxCompletionTokens(maxCompletionTokens);



        }



        JsonNode thinking = c.get("thinking");



        cm.getSummaryConfig().setThinking(thinking != null && thinking.isBoolean() ? thinking.asBoolean() : null);



        cm.setCitationEnabled(c.path("citation_enabled").asBoolean(true));







        int embeddingTopK = c.path("embedding_top_k").asInt(0);



        if (embeddingTopK > 0) {



            cm.setEmbeddingTopK(embeddingTopK);



        }



        double keywordThreshold = c.path("keyword_threshold").asDouble(0);



        if (keywordThreshold > 0) {



            cm.setKeywordThreshold(keywordThreshold);



        }



        double vectorThreshold = c.path("vector_threshold").asDouble(0);



        if (vectorThreshold > 0) {



            cm.setVectorThreshold(vectorThreshold);



        }



        int rerankTopK = c.path("rerank_top_k").asInt(0);



        if (rerankTopK > 0) {



            cm.setRerankTopK(rerankTopK);



        }



        cm.setRerankThreshold(c.path("rerank_threshold").asDouble(0));



        String rerankModelId = c.path("rerank_model_id").asText("");



        if (!rerankModelId.isEmpty()) {



            cm.setRerankModelId(rerankModelId);



        }







        cm.setEnableRewrite(c.path("enable_rewrite").asBoolean(false));



        cm.setEnableQueryExpansion(c.path("enable_query_expansion").asBoolean(false));



        String rwSys = c.path("rewrite_prompt_system").asText("");



        if (!rwSys.isEmpty()) {



            cm.setRewritePromptSystem(rwSys);



        }



        String rwUser = c.path("rewrite_prompt_user").asText("");



        if (!rwUser.isEmpty()) {



            cm.setRewritePromptUser(rwUser);



        }



        String quModel = c.path("query_understand_model_id").asText("");



        if (!quModel.isEmpty()) {



            cm.setQueryUnderstandModelId(quModel);



        }







        String fallbackStrategy = c.path("fallback_strategy").asText("");



        if (!fallbackStrategy.isEmpty()) {



            cm.setFallbackStrategy(fallbackStrategy);



        }



        String fallbackResponse = c.path("fallback_response").asText("");



        if (!fallbackResponse.isEmpty()) {



            cm.setFallbackResponse(fallbackResponse);



        }



        String fallbackPrompt = c.path("fallback_prompt").asText("");



        if (!fallbackPrompt.isEmpty()) {



            cm.setFallbackPrompt(fallbackPrompt);



        }







        int webSearchMaxResults = c.path("web_search_max_results").asInt(0);



        if (webSearchMaxResults > 0) {



            cm.setWebSearchMaxResults(webSearchMaxResults);



        }







        int historyTurns = c.path("history_turns").asInt(0);



        if (historyTurns > 0) {



            cm.setMaxRounds(historyTurns);



            log.info("Using custom agent's history_turns: {}", cm.getMaxRounds());



        }



        if (!c.path("multi_turn_enabled").asBoolean(true)) {



            cm.setMaxRounds(0);



            log.info("Multi-turn disabled by custom agent, clearing history");



        }







        cm.setFaqPriorityEnabled(c.path("faq_priority_enabled").asBoolean(false));



        cm.setFaqDirectAnswerThreshold(c.path("faq_direct_answer_threshold").asDouble(0.0));



        cm.setFaqScoreBoost(c.path("faq_score_boost").asDouble(0.0));







        cm.setDataAnalysisEnabled(c.path("data_analysis_enabled").asBoolean(false));







        JsonNode intentPrompts = c.get("intent_prompts");



        if (intentPrompts != null && intentPrompts.isObject() && intentPrompts.size() > 0) {



            Map<String, String> overrides = new LinkedHashMap<>();



            intentPrompts.fields().forEachRemaining(e -> overrides.put(e.getKey(), e.getValue().asText("")));



            cm.setIntentPromptOverrides(overrides);



        }



    }
    SessionQaResolution.Prompts resolveCustomAgentPrompts(



            com.ragagent.agentm.domain.CustomAgentEntity agent, ObjectNode c) {



        if (c == null) {



            return new SessionQaResolution.Prompts("", "");



        }



        String system = c.path("system_prompt").asText("");



        String context = c.path("context_template").asText("");



        boolean agentMode = SessionKnowledgeQaService.isAgentMode(c);



        String systemId = c.path("system_prompt_id").asText("");



        if (system.isEmpty() && !systemId.isEmpty()) {



            String content = SessionQaResolution.templateContentByIdAndFile(systemId,



                    agentMode ? "agent_system_prompt.yaml" : "system_prompt.yaml");



            if (content != null) {



                system = content;



            }



        }



        String contextId = c.path("context_template_id").asText("");



        if (context.isEmpty() && !contextId.isEmpty()) {



            String content = SessionQaResolution.templateContentByIdAndFile(contextId, "context_template.yaml");



            if (content != null) {



                context = content;



            }



        }



        return new SessionQaResolution.Prompts(system, context);



    }
}
