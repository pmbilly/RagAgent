package com.ragagent.agentm.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.CustomAgentService.Result;
import com.ragagent.org.dto.OrgResponses;

/**
 * agents CRUD 家族的响应构造（JSON 是契约）。
 *
 * <p>CustomAgent struct 序：id → name → description → avatar → is_builtin →
 * tenant_id → created_by → config → created_at → updated_at → deleted_at →
 * creator_name(omitempty)。config 走 {@link OrgResponses#agentConfigMap}
 * （jsonb→struct 序重排，波 3 协作面先例）。</p>
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
        m.put("config", OrgResponses.agentConfigMap(asTree(config)));
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
}
