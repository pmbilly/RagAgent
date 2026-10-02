package com.ragagent.agentm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * CustomAgentConfig 的树级补默认 / 校验。
 *
 * <p>直接在 Jackson 树上做：缺键视为零值，默认值显式写回，
 * 序列化由 agentm/dto/AgentResponses#agentConfigMap 按
 * 固定声明序 + 零值键省略语义输出。</p>
 */
public final class AgentConfigJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String SUGGESTION_CURATED = "curated";
    public static final String SUGGESTION_KNOWLEDGE = "knowledge";
    public static final String SUGGESTION_GENERATED = "generated";
    public static final String SUGGESTION_HYBRID = "hybrid";

    private AgentConfigJson() {}

    /** 树级补默认（原地修改，返回同引用）。 */
    public static ObjectNode ensureDefaults(ObjectNode cfg) {
        JsonNode qs = cfg.get("questionSuggestions");
        if (qs == null || qs.isNull() || !qs.isObject()) {
            cfg.set("questionSuggestions", defaultQuestionSuggestions());
        } else {
            ensureSuggestionDefaults((ObjectNode) qs);
        }
        if (cfg.path("temperature").asDouble(0) < 0) {
            cfg.put("temperature", 0.7);
        }
        int maxIter = cfg.path("maxIterations").asInt(0);
        if (maxIter == 0) {
            cfg.put("maxIterations", 10);
        } else if (maxIter < 0) {
            cfg.put("maxIterations", -1); // UnlimitedMaxIterations
        }
        if (cfg.path("webSearchMaxResults").asInt(0) == 0) {
            cfg.put("webSearchMaxResults", 5);
        }
        if (cfg.path("historyTurns").asInt(0) == 0) {
            cfg.put("historyTurns", 5);
        }
        if (cfg.path("embeddingTopK").asInt(0) == 0) {
            cfg.put("embeddingTopK", 10);
        }
        if (cfg.path("keywordThreshold").asDouble(0) == 0) {
            cfg.put("keywordThreshold", 0.3);
        }
        if (cfg.path("vectorThreshold").asDouble(0) == 0) {
            cfg.put("vectorThreshold", 0.5);
        }
        if (cfg.path("rerankTopK").asInt(0) == 0) {
            cfg.put("rerankTopK", 5);
        }
        if (cfg.path("fallbackStrategy").asText("").isEmpty()) {
            cfg.put("fallbackStrategy", "model");
        }
        if ("smart-reasoning".equals(cfg.path("agentMode").asText(""))) {
            cfg.put("multiTurnEnabled", true);
        }
        JsonNode thinking = cfg.get("thinking");
        if (thinking == null || thinking.isNull() || !thinking.isBoolean()) {
            cfg.put("thinking", false);
        }
        JsonNode citation = cfg.get("citationEnabled");
        if (citation == null || citation.isNull() || !citation.isBoolean()) {
            cfg.put("citationEnabled", true);
        }
        return cfg;
    }

    /** 补默认时物化的默认 QuestionSuggestionConfig（逐字段固定）。 */
    private static ObjectNode defaultQuestionSuggestions() {
        ObjectNode qs = MAPPER.createObjectNode();
        ObjectNode starters = qs.putObject("starters");
        starters.put("enabled", true);
        starters.put("mode", SUGGESTION_HYBRID);
        starters.putArray("items");
        starters.put("count", 6);
        ObjectNode fu = qs.putObject("follow_ups");
        fu.put("enabled", false);
        fu.put("mode", SUGGESTION_HYBRID);
        fu.put("count", 3);
        ArrayNode cats = fu.putArray("categories");
        cats.add("clarify").add("deepen").add("action");
        fu.put("max_context_turns", 2);
        fu.put("suppress_on_fallback", true);
        fu.put("suppress_when_answer_asks_question", true);
        fu.put("knowledge_fallback", true);
        return qs;
    }

    /** QuestionSuggestionConfig 补默认。 */
    private static void ensureSuggestionDefaults(ObjectNode qs) {
        ObjectNode starters = objectAt(qs, "starters");
        if (starters.path("mode").asText("").isEmpty()) {
            starters.put("mode", SUGGESTION_HYBRID);
        }
        if (starters.path("count").asInt(0) <= 0) {
            starters.put("count", 6);
        }
        JsonNode items = starters.get("items");
        if (items == null || items.isNull()) {
            starters.putArray("items");
        }
        ObjectNode fu = objectAt(qs, "follow_ups");
        if (fu.path("mode").asText("").isEmpty()) {
            fu.put("mode", SUGGESTION_HYBRID);
        }
        if (fu.path("count").asInt(0) <= 0) {
            fu.put("count", 3);
        }
        if (fu.path("max_context_turns").asInt(0) <= 0) {
            fu.put("max_context_turns", 2);
        }
        JsonNode cats = fu.get("categories");
        if (cats == null || cats.isNull() || !cats.isArray() || cats.isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            arr.add("clarify").add("deepen").add("action");
            fu.set("categories", arr);
        }
    }

    private static ObjectNode objectAt(ObjectNode parent, String field) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull() || !n.isObject()) {
            ObjectNode created = MAPPER.createObjectNode();
            parent.set(field, created);
            return created;
        }
        return (ObjectNode) n;
    }

    /** QuestionSuggestionConfig 校验：null 或首个错误的文案。 */
    public static String validateSuggestions(JsonNode cfg) {
        JsonNode qs = cfg.get("questionSuggestions");
        if (qs == null || qs.isNull() || !qs.isObject()) {
            return null;
        }
        JsonNode starters = qs.get("starters");
        int sCount = starters == null ? 0 : starters.path("count").asInt(0);
        if (sCount < 1 || sCount > 8) {
            return "starter suggestion count must be between 1 and 8";
        }
        JsonNode fu = qs.get("follow_ups");
        int fCount = fu == null ? 0 : fu.path("count").asInt(0);
        if (fCount < 1 || fCount > 5) {
            return "follow-up suggestion count must be between 1 and 5";
        }
        int mct = fu == null ? 0 : fu.path("max_context_turns").asInt(0);
        if (mct < 1 || mct > 5) {
            return "follow-up max_context_turns must be between 1 and 5";
        }
        String sMode = starters == null ? "" : starters.path("mode").asText("");
        if (!oneOf(sMode, SUGGESTION_CURATED, SUGGESTION_KNOWLEDGE, SUGGESTION_HYBRID)) {
            return "invalid starter suggestion mode " + quote(sMode);
        }
        String fMode = fu == null ? "" : fu.path("mode").asText("");
        if (!oneOf(fMode, SUGGESTION_GENERATED, SUGGESTION_KNOWLEDGE, SUGGESTION_HYBRID)) {
            return "invalid follow-up suggestion mode " + quote(fMode);
        }
        if (starters != null && starters.path("items").isArray()) {
            int i = 0;
            for (JsonNode item : starters.get("items")) {
                String trimmed = item.asText("").trim();
                if (trimmed.isEmpty()) {
                    return "starter suggestion " + (i + 1) + " cannot be empty";
                }
                if (trimmed.codePointCount(0, trimmed.length()) > 200) {
                    return "starter suggestion " + (i + 1) + " exceeds 200 characters";
                }
                i++;
            }
        }
        if (fu != null) {
            String instr = fu.path("additional_instruction").asText("").trim();
            if (instr.codePointCount(0, instr.length()) > 2000) {
                return "follow-up additional_instruction exceeds 2000 characters";
            }
            JsonNode cats = fu.get("categories");
            if (cats != null && cats.isArray()) {
                for (JsonNode c : cats) {
                    if (!oneOf(c.asText(""), "clarify", "deepen", "action")) {
                        return "invalid follow-up suggestion category " + quote(c.asText(""));
                    }
                }
            }
        }
        return null;
    }

    private static boolean oneOf(String value, String... allowed) {
        for (String a : allowed) {
            if (value.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** 双引号包裹（%q 风格）。 */
    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
