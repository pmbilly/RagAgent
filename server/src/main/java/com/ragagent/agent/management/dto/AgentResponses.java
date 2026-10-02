package com.ragagent.agent.management.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.ragagent.agent.management.domain.CustomAgentEntity;

/**
 * agents CRUD 家族的响应构造（JSON 是契约）。
 *
 * <p>响应键序固定：id → name → description → avatar → is_builtin →
 * tenant_id → created_by → config → created_at → updated_at → deleted_at →
 * creator_name（空则省略）。config 走 {@link #agentConfigMap}
 * （jsonb→固定声明序重排；原 OrgResponses 实现，org 裁撤后内联至此）。</p>
 */
public final class AgentResponses {

    /** 无 DB 行时间时的占位零值时间戳（注册表内建 agent 无 DB 行）。 */
    public static final String GO_ZERO_TIME = "0001-01-01T00:00:00Z";

    private AgentResponses() {}

    public static Map<String, Object> agent(CustomAgentResult r) {
        return agent(r.row(), r.config());
    }

    public static Map<String, Object> agent(CustomAgentEntity row, Object config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("name", nz(row.getName()));
        m.put("description", nz(row.getDescription()));
        m.put("avatar", nz(row.getAvatar()));
        m.put("builtin", row.isBuiltin());
        m.put("tenantId", row.getTenantId() == null ? 0L : row.getTenantId());
        m.put("createdBy", nz(row.getCreatedBy()));
        m.put("config", agentConfigMap(asTree(config)));
        m.put("createdAt", row.getCreatedAt() == null ? GO_ZERO_TIME : row.getCreatedAt());
        m.put("updatedAt", row.getUpdatedAt() == null ? GO_ZERO_TIME : row.getUpdatedAt());
        m.put("deletedAt", null);
        if (row.getCreatorName() != null && !row.getCreatorName().isEmpty()) {
            m.put("creatorName", row.getCreatorName());
        }
        return m;
    }

    /** 列表信封（键按字母序：data < disabled_own_agent_ids < success）。 */
    public static Map<String, Object> listEnvelope(List<?> agents,
            List<String> disabledOwnIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agents", agents);
        m.put("disabledOwnAgentIds", disabledOwnIds == null ? List.of() : disabledOwnIds);
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
        m.put("agentMode", text(c, "agentMode"));
        ifStr(c, "agentType", m);
        m.put("systemPrompt", text(c, "systemPrompt"));
        ifStr(c, "systemPromptId", m);
        m.put("contextTemplate", text(c, "contextTemplate"));
        ifStr(c, "contextTemplateId", m);
        m.put("modelId", text(c, "modelId"));
        m.put("rerankModelId", text(c, "rerankModelId"));
        m.put("temperature", goNumber(c, "temperature"));
        m.put("maxCompletionTokens", intOf(c, "maxCompletionTokens"));
        m.put("thinking", boolPtr(c, "thinking"));
        m.put("citationEnabled", boolPtr(c, "citationEnabled"));
        m.put("maxIterations", intOf(c, "maxIterations"));
        ifIntNonZero(c, "llmCallTimeout", m);
        m.put("allowedTools", strSlice(c, "allowedTools"));
        m.put("mcpSelectionMode", text(c, "mcpSelectionMode"));
        m.put("mcpServices", strSlice(c, "mcpServices"));
        ifIntNonZero(c, "mcpAuthWaitTimeout", m);
        m.put("skillsSelectionMode", text(c, "skillsSelectionMode"));
        m.put("selectedSkills", strSlice(c, "selectedSkills"));
        m.put("kbSelectionMode", text(c, "kbSelectionMode"));
        m.put("knowledgeBases", strSlice(c, "knowledgeBases"));
        m.put("retrieveKbOnlyWhenMentioned", boolOf(c, "retrieveKbOnlyWhenMentioned"));
        m.put("retainRetrievalHistory", boolOf(c, "retainRetrievalHistory"));
        m.put("imageUploadEnabled", boolOf(c, "imageUploadEnabled"));
        m.put("vlmModelId", text(c, "vlmModelId"));
        m.put("audioUploadEnabled", boolOf(c, "audioUploadEnabled"));
        m.put("asrModelId", text(c, "asrModelId"));
        m.put("imageStorageProvider", text(c, "imageStorageProvider"));
        m.put("supportedFileTypes", strSlice(c, "supportedFileTypes"));
        ifArrayNonEmpty(c, "chatParserEngineRules", m);
        m.put("attachmentImageUnderstanding", boolOf(c, "attachmentImageUnderstanding"));
        ifIntNonZero(c, "attachmentOcrMaxPages", m);
        ifIntNonZero(c, "attachmentParseWaitTimeoutSec", m);
        m.put("dataAnalysisEnabled", boolOf(c, "dataAnalysisEnabled"));
        m.put("faqPriorityEnabled", boolOf(c, "faqPriorityEnabled"));
        m.put("faqDirectAnswerThreshold", goNumber(c, "faqDirectAnswerThreshold"));
        m.put("faqScoreBoost", goNumber(c, "faqScoreBoost"));
        m.put("webSearchEnabled", boolOf(c, "webSearchEnabled"));
        m.put("webSearchMaxResults", intOf(c, "webSearchMaxResults"));
        ifStr(c, "webSearchProviderId", m);
        m.put("webFetchEnabled", boolOf(c, "webFetchEnabled"));
        ifIntNonZero(c, "webFetchTopN", m);
        m.put("multiTurnEnabled", boolOf(c, "multiTurnEnabled"));
        m.put("historyTurns", intOf(c, "historyTurns"));
        ifBoolPtrNonNil(c, "memoryEnabled", m);
        m.put("embeddingTopK", intOf(c, "embeddingTopK"));
        m.put("keywordThreshold", goNumber(c, "keywordThreshold"));
        m.put("vectorThreshold", goNumber(c, "vectorThreshold"));
        m.put("rerankTopK", intOf(c, "rerankTopK"));
        m.put("rerankThreshold", goNumber(c, "rerankThreshold"));
        m.put("enableQueryExpansion", boolOf(c, "enableQueryExpansion"));
        m.put("enableRewrite", boolOf(c, "enableRewrite"));
        m.put("rewritePromptSystem", text(c, "rewritePromptSystem"));
        m.put("rewritePromptUser", text(c, "rewritePromptUser"));
        ifStr(c, "queryUnderstandModelId", m);
        m.put("fallbackStrategy", text(c, "fallbackStrategy"));
        m.put("fallbackResponse", text(c, "fallbackResponse"));
        m.put("fallbackPrompt", text(c, "fallbackPrompt"));
        ifStrMapNonEmpty(c, "intentPrompts", m);
        ifQuestionSuggestions(c.get("questionSuggestions"), m);
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
        JsonNode f = q.get("followUps");
        fu.put("enabled", f != null && f.path("enabled").asBoolean(false));
        fu.put("mode", f == null ? "" : text(f, "mode"));
        fu.put("count", f == null ? 0 : f.path("count").asInt(0));
        if (f != null && !text(f, "modelId").isEmpty()) {
            fu.put("modelId", text(f, "modelId"));
        }
        if (f != null && !text(f, "additionalInstruction").isEmpty()) {
            fu.put("additionalInstruction", text(f, "additionalInstruction"));
        }
        if (f != null && f.get("categories") != null && f.get("categories").isArray() && f.get("categories").size() > 0) {
            fu.put("categories", strList(f.get("categories")));
        }
        fu.put("maxContextTurns", f == null ? 0 : f.path("maxContextTurns").asInt(0));
        fu.put("suppressOnFallback", f != null && f.path("suppressOnFallback").asBoolean(false));
        fu.put("suppressWhenAnswerAsksQuestion", f != null && f.path("suppressWhenAnswerAsksQuestion").asBoolean(false));
        fu.put("knowledgeFallback", f != null && f.path("knowledgeFallback").asBoolean(false));
        fu.put("allowRegenerate", f != null && f.path("allowRegenerate").asBoolean(false));
        outer.put("starters", starters);
        outer.put("followUps", fu);
        m.put("questionSuggestions", outer);
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
