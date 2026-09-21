package com.ragagent.org.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragagent.org.domain.AgentRow;

/**
 * 共享智能体 KB 范围快照（对照 Go internal/types/shared_agent_access.go
 * SharedAgentKBScope / NewSharedAgentKBScope / SharedAgentIncludesKB）。
 *
 * <p>它描述「一个已授权的共享 agent 暴露了哪些 KB」——本身不构成调用方可用该
 * agent 的证明。零值、未知 selection mode、空选择一律拒绝。</p>
 *
 * <p>Java 侧没有 CustomAgent 完整领域对象在场时的载体是 {@link AgentRow}：
 * scope 只消费 config 的 {@code kb_selection_mode}/{@code knowledge_bases}
 * 与行上的 {@code tenant_id}。</p>
 */
public final class SharedAgentKBScope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long tenantId;
    private final boolean all;
    private final List<String> ids;
    private final Set<String> selected;

    private SharedAgentKBScope(long tenantId, boolean all, List<String> ids, Set<String> selected) {
        this.tenantId = tenantId;
        this.all = all;
        this.ids = ids;
        this.selected = selected;
    }

    /** 零值（拒绝一切），对照 Go 的 SharedAgentKBScope{}。 */
    public static SharedAgentKBScope empty() {
        return new SharedAgentKBScope(0, false, List.of(), Set.of());
    }

    /** 对照 NewSharedAgentKBScope：agent==null 或 tenantID==0 → 零值。 */
    public static SharedAgentKBScope from(AgentRow agent) {
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() == 0) {
            return empty();
        }
        return from(agent.getTenantId(), parseConfig(agent.getConfig()));
    }

    /** 对照 NewSharedAgentKBScope 的 mode 分派："all" → 全量；"selected" → 去重保序。 */
    public static SharedAgentKBScope from(long tenantId, JsonNode config) {
        if (tenantId == 0) {
            return empty();
        }
        String mode = config == null ? "" : text(config, "kb_selection_mode");
        switch (mode) {
            case "all":
                return new SharedAgentKBScope(tenantId, true, List.of(), Set.of());
            case "selected":
                List<String> ids = new ArrayList<>();
                Set<String> selected = new LinkedHashSet<>();
                JsonNode kbs = config.get("knowledge_bases");
                if (kbs != null && kbs.isArray()) {
                    for (JsonNode id : kbs) {
                        String v = id.isTextual() ? id.asText() : "";
                        if (v.isEmpty() || selected.contains(v)) {
                            continue;
                        }
                        selected.add(v);
                        ids.add(v);
                    }
                }
                return new SharedAgentKBScope(tenantId, false, List.copyOf(ids),
                        Set.copyOf(selected));
            default:
                return new SharedAgentKBScope(tenantId, false, List.of(), Set.of());
        }
    }

    public boolean isAll() {
        return all;
    }

    /** 对照 IsEmpty：未授权任何 KB（all 恒非空；selected 空即空）。 */
    public boolean isEmpty() {
        return !all && ids.isEmpty();
    }

    /** 对照 IDs：返回拷贝；null 从不表示"不受限"。 */
    public List<String> ids() {
        return new ArrayList<>(ids);
    }

    /** 对照 Allows：同时校验 KB 选择与权威 owner 租户。 */
    public boolean allows(String kbId, long kbTenantId) {
        if (kbId == null || kbId.isEmpty() || tenantId == 0 || tenantId != kbTenantId) {
            return false;
        }
        return all || selected.contains(kbId);
    }

    /** 对照 SharedAgentIncludesKB："all" 选择也绑定在 agent 的租户上。 */
    public static boolean includesKb(AgentRow agent, String kbId, long kbTenantId) {
        return kbId != null && from(agent).allows(kbId, kbTenantId);
    }

    public static JsonNode parseConfig(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static String text(JsonNode c, String field) {
        JsonNode n = c == null ? null : c.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    /** config 的字符串数组字段（allowed_tools 等；缺失/非数组 → 空表）。 */
    public static List<String> stringArray(JsonNode c, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = c == null ? null : c.get(field);
        if (arr != null && arr.isArray()) {
            for (JsonNode v : arr) {
                if (v.isTextual()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }
}
