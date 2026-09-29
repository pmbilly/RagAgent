package com.ragagent.agentm.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.CustomAgentService.Result;

/**
 * agents CRUD 家族的响应构造（JSON 是契约）。
 *
 * <p>CustomAgent struct 序：id → name → description → avatar → is_builtin →
 * tenant_id → created_by → config → created_at → updated_at → deleted_at →
 * creator_name(omitempty)。config 走 {@link #agentConfigMap}
 * （jsonb→struct 序重排；原 OrgResponses 实现，org 裁撤后内联至此）。</p>
 */
public final class AgentResponses {

    /** Go time.Time 零值的 JSON 形态（注册表内建 agent 无 DB 行）。 */
    public static final String GO_ZERO_TIME = "0001-01-01T00:00:00Z";

    private AgentResponses() {}

    public static Map<String, Object> agent(Result r) {
        return agent(r.row(), r.config());
    }

    public static Map<String, Object> agent(CustomAgentEntity row, Object config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("name", nz(row.getName()));
        m.put("description", nz(row.getDescription()));
        m.put("avatar", nz(row.getAvatar()));
        m.put("is_builtin", row.isBuiltin());
        m.put("tenant_id", row.getTenantId() == null ? 0L : row.getTenantId());
        m.put("created_by", nz(row.getCreatedBy()));
        m.put("config", agentConfigMap(asTree(config)));
        m.put("created_at", row.getCreatedAt() == null ? GO_ZERO_TIME : row.getCreatedAt());
        m.put("updated_at", row.getUpdatedAt() == null ? GO_ZERO_TIME : row.getUpdatedAt());
        m.put("deleted_at", null);
        if (row.getCreatorName() != null && !row.getCreatorName().isEmpty()) {
            m.put("creator_name", row.getCreatorName());
        }
        return m;
    }

    /** 列表信封（gin.H 字母序：data < disabled_own_agent_ids < success）。 */
    public static Map<String, Object> listEnvelope(List<?> agents,
            List<String> disabledOwnIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", agents);
        m.put("disabled_own_agent_ids", disabledOwnIds == null ? List.of() : disabledOwnIds);
        m.put("success", true);
        return m;
    }

    /** {"data":..., "success":true}（data < success）。 */
    public static Map<String, Object> dataEnvelope(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", data);
        m.put("success", true);
        return m;
    }

    /** 删除信封（message < success）。 */
    public static Map<String, Object> deletedEnvelope() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "Agent deleted successfully");
        m.put("success", true);
        return m;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode asTree(Object config) {
        if (config instanceof com.fasterxml.jackson.databind.JsonNode n) {
            return n;
        }
        return MAPPER.valueToTree(config);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static Map<String, Object> agentConfigMap(JsonNode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null || c.isNull()) {
            c = MAPPER.createObjectNode();
        }
        m.put("agent_mode", text(c, "agent_mode"));
        ifStr(c, "agent_type", m);
        m.put("system_prompt", text(c, "system_prompt"));
        ifStr(c, "system_prompt_id", m);
        m.put("context_template", text(c, "context_template"));
        ifStr(c, "context_template_id", m);
        m.put("model_id", text(c, "model_id"));
        m.put("rerank_model_id", text(c, "rerank_model_id"));
        m.put("temperature", goNumber(c, "temperature"));
        m.put("max_completion_tokens", intOf(c, "max_completion_tokens"));
        m.put("thinking", boolPtr(c, "thinking"));
        m.put("citation_enabled", boolPtr(c, "citation_enabled"));
        m.put("max_iterations", intOf(c, "max_iterations"));
        ifIntNonZero(c, "llm_call_timeout", m);
        m.put("allowed_tools", strSlice(c, "allowed_tools"));
        m.put("mcp_selection_mode", text(c, "mcp_selection_mode"));
        m.put("mcp_services", strSlice(c, "mcp_services"));
        ifIntNonZero(c, "mcp_auth_wait_timeout", m);
        m.put("skills_selection_mode", text(c, "skills_selection_mode"));
        m.put("selected_skills", strSlice(c, "selected_skills"));
        m.put("kb_selection_mode", text(c, "kb_selection_mode"));
        m.put("knowledge_bases", strSlice(c, "knowledge_bases"));
        m.put("retrieve_kb_only_when_mentioned", boolOf(c, "retrieve_kb_only_when_mentioned"));
        m.put("retain_retrieval_history", boolOf(c, "retain_retrieval_history"));
        m.put("image_upload_enabled", boolOf(c, "image_upload_enabled"));
        m.put("vlm_model_id", text(c, "vlm_model_id"));
        m.put("audio_upload_enabled", boolOf(c, "audio_upload_enabled"));
        m.put("asr_model_id", text(c, "asr_model_id"));
        m.put("image_storage_provider", text(c, "image_storage_provider"));
        m.put("supported_file_types", strSlice(c, "supported_file_types"));
        ifArrayNonEmpty(c, "chat_parser_engine_rules", m);
        m.put("attachment_image_understanding", boolOf(c, "attachment_image_understanding"));
        ifIntNonZero(c, "attachment_ocr_max_pages", m);
        ifIntNonZero(c, "attachment_parse_wait_timeout_sec", m);
        m.put("data_analysis_enabled", boolOf(c, "data_analysis_enabled"));
        m.put("faq_priority_enabled", boolOf(c, "faq_priority_enabled"));
        m.put("faq_direct_answer_threshold", goNumber(c, "faq_direct_answer_threshold"));
        m.put("faq_score_boost", goNumber(c, "faq_score_boost"));
        m.put("web_search_enabled", boolOf(c, "web_search_enabled"));
        m.put("web_search_max_results", intOf(c, "web_search_max_results"));
        ifStr(c, "web_search_provider_id", m);
        m.put("web_fetch_enabled", boolOf(c, "web_fetch_enabled"));
        ifIntNonZero(c, "web_fetch_top_n", m);
        m.put("multi_turn_enabled", boolOf(c, "multi_turn_enabled"));
        m.put("history_turns", intOf(c, "history_turns"));
        ifBoolPtrNonNil(c, "memory_enabled", m);
        m.put("embedding_top_k", intOf(c, "embedding_top_k"));
        m.put("keyword_threshold", goNumber(c, "keyword_threshold"));
        m.put("vector_threshold", goNumber(c, "vector_threshold"));
        m.put("rerank_top_k", intOf(c, "rerank_top_k"));
        m.put("rerank_threshold", goNumber(c, "rerank_threshold"));
        m.put("enable_query_expansion", boolOf(c, "enable_query_expansion"));
        m.put("enable_rewrite", boolOf(c, "enable_rewrite"));
        m.put("rewrite_prompt_system", text(c, "rewrite_prompt_system"));
        m.put("rewrite_prompt_user", text(c, "rewrite_prompt_user"));
        ifStr(c, "query_understand_model_id", m);
        m.put("fallback_strategy", text(c, "fallback_strategy"));
        m.put("fallback_response", text(c, "fallback_response"));
        m.put("fallback_prompt", text(c, "fallback_prompt"));
        ifStrMapNonEmpty(c, "intent_prompts", m);
        ifQuestionSuggestions(c.get("question_suggestions"), m);
        return m;
    }

    private static String text(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    private static void ifStr(JsonNode c, String field, Map<String, Object> m) {
        String v = text(c, field);
        if (!v.isEmpty()) {
            m.put(field, v);
        }
    }

    private static Object goNumber(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull() || !n.isNumber()) {
            return 0;
        }
        double d = n.asDouble();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
            return (long) d;
        }
        return d;
    }

    private static int intOf(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull()) {
            return 0;
        }
        if (n.isNumber()) {
            return n.intValue();
        }
        return 0;
    }

    private static Boolean boolPtr(JsonNode c, String field) {
        JsonNode n = c.get(field);
        if (n == null || n.isNull() || !n.isBoolean()) {
            return null;
        }
        return n.asBoolean();
    }

    private static void ifQuestionSuggestions(JsonNode q, Map<String, Object> m) {
        if (q == null || q.isNull()) {
            return;
        }
        Map<String, Object> outer = new LinkedHashMap<>();
        Map<String, Object> starters = new LinkedHashMap<>();
        JsonNode s = q.get("starters");
        starters.put("enabled", s != null && s.path("enabled").asBoolean(false));
        starters.put("mode", s == null ? "" : text(s, "mode"));
        starters.put("items", s == null ? null : strList(s.get("items")));
        starters.put("count", s == null ? 0 : s.path("count").asInt(0));
        Map<String, Object> fu = new LinkedHashMap<>();
        JsonNode f = q.get("follow_ups");
        fu.put("enabled", f != null && f.path("enabled").asBoolean(false));
        fu.put("mode", f == null ? "" : text(f, "mode"));
        fu.put("count", f == null ? 0 : f.path("count").asInt(0));
        if (f != null && !text(f, "model_id").isEmpty()) {
            fu.put("model_id", text(f, "model_id"));
        }
        if (f != null && !text(f, "additional_instruction").isEmpty()) {
            fu.put("additional_instruction", text(f, "additional_instruction"));
        }
        if (f != null && f.get("categories") != null && f.get("categories").isArray() && f.get("categories").size() > 0) {
            fu.put("categories", strList(f.get("categories")));
        }
        fu.put("max_context_turns", f == null ? 0 : f.path("max_context_turns").asInt(0));
        fu.put("suppress_on_fallback", f != null && f.path("suppress_on_fallback").asBoolean(false));
        fu.put("suppress_when_answer_asks_question", f != null && f.path("suppress_when_answer_asks_question").asBoolean(false));
        fu.put("knowledge_fallback", f != null && f.path("knowledge_fallback").asBoolean(false));
        fu.put("allow_regenerate", f != null && f.path("allow_regenerate").asBoolean(false));
        outer.put("starters", starters);
        outer.put("follow_ups", fu);
        m.put("question_suggestions", outer);
    }

    private static void ifArrayNonEmpty(JsonNode c, String field, Map<String, Object> m) {
        JsonNode n = c.get(field);
        if (n != null && n.isArray() && n.size() > 0) {
            m.put(field, MAPPER.valueToTree(n));
        }
    }

    private static void ifStrMapNonEmpty(JsonNode c, String field, Map<String, Object> m) {
        JsonNode n = c.get(field);
        if (n == null || !n.isObject() || n.size() == 0) {
            return;
        }
        Map<String, Object> sm = new TreeMap<>();
        n.fields().forEachRemaining(e -> sm.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText()));
        m.put(field, sm);
    }

    private static com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy zeroStrategy() {
        return new com.ragagent.knowledge.domain.KnowledgeBaseIndexingStrategy();
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> strList(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            for (JsonNode e : n) {
                out.add(e.isNull() ? null : e.asText());
            }
            return out;
        }
        return out;
    }

    private static List<String> strSlice(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return strList(n);
    }

    private static void ifBoolPtrNonNil(JsonNode c, String field, Map<String, Object> m) {
        Boolean v = boolPtr(c, field);
        if (v != null) {
            m.put(field, v);
        }
    }

    private static void ifIntNonZero(JsonNode c, String field, Map<String, Object> m) {
        int v = intOf(c, field);
        if (v != 0) {
            m.put(field, v);
        }
    }

    private static boolean boolOf(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n != null && n.asBoolean(false);
    }
}
