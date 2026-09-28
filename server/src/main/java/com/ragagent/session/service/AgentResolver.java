package com.ragagent.session.service;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agentm.domain.CustomAgentEntity;
import com.ragagent.agentm.service.CustomAgentService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.org.domain.AgentRow;
import com.ragagent.org.service.AgentShareService;
import com.ragagent.org.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 共享/自有 agent 解析（对照 Go handler/session 的 {@code resolveAgent}，
 * qa.go L548-600）：共享 agent 优先（解析失败静默吞掉），{@code sourceTenantId == 0}
 * 时才回落自有 agent；{@code sourceTenantId != 0} 且未命中时由调用方落
 * 404 "Shared agent not found"。Go 里它是 Handler 的方法、被 QA 与附件上传两个
 * 入口共用；Java 侧收成独立组件，两个入口共享同一语义。
 *
 * <p>三元组 = (agent 行, effectiveTenantId, sharedAgentReadOnly)。effectiveTenantId
 * 是共享 agent 的**实际归属租户**（模型/KB/解析依赖的解析范围），非请求里的
 * source 参数。</p>
 */
@Component
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CustomAgentService customAgentService;
    private final AgentShareService agentShareService;

    public AgentResolver(CustomAgentService customAgentService,
            AgentShareService agentShareService) {
        this.customAgentService = customAgentService;
        this.agentShareService = agentShareService;
    }

    /** 三元返回（对照 Go {@code (customAgent, effectiveTenantID, sharedAgentReadOnly)}）。 */
    public record ResolvedAgent(CustomAgentEntity row, long effectiveTenantId,
                                boolean sharedAgentReadOnly) {
    }

    public ResolvedAgent resolve(String agentId, long sourceTenantId) {
        if (agentId == null || agentId.isEmpty()) {
            return new ResolvedAgent(null, 0, false);
        }
        CustomAgentEntity customAgent = null;
        long effectiveTenantId = 0;
        boolean sharedAgentReadOnly = false;
        Long currentTenant = TenantContext.currentTenantId();
        String currentUser = TenantContext.currentUserId();
        if (currentTenant != null && currentTenant != 0 && currentUser != null
                && !currentUser.isEmpty()) {
            try {
                AgentRow shared = agentShareService.getSharedAgentForTenant(currentTenant,
                        OrganizationService.callerTenantRole(), agentId, sourceTenantId);
                if (shared != null) {
                    effectiveTenantId = shared.getTenantId() == null ? 0 : shared.getTenantId();
                    customAgent = toCustomAgentEntity(shared);
                    sharedAgentReadOnly = true;
                    log.info("Using shared agent: ID={}, Name={}, effectiveTenantID={} (retrieval scope)",
                            customAgent.getId(), customAgent.getName(), effectiveTenantId);
                }
            } catch (RuntimeException e) {
                // Go：share 解析失败静默——source==0 回落 own，source!=0 外层 404
                log.info("Shared agent resolution miss: agent ID: {}, error: {}", agentId,
                        e.toString());
            }
        }
        // sourceTenantId == 0 时才回落 own agent（Go L584 的守卫语义：
        // 被拒的共享选择子不许静默跑同 id 的本地内建 agent）
        if (customAgent == null && sourceTenantId == 0) {
            try {
                var result = customAgentService.getAgentByID(agentId, null);
                customAgent = result == null ? null : result.row();
            } catch (RuntimeException e) {
                log.warn("Failed to get custom agent, agent ID: {}, error: {}, using default config",
                        agentId, e.toString());
            }
        }
        return new ResolvedAgent(customAgent, effectiveTenantId, sharedAgentReadOnly);
    }

    /** AgentRow（org 投影）→ CustomAgentEntity（agentm 消费面）：字段一一同名映射。 */
    public static CustomAgentEntity toCustomAgentEntity(AgentRow row) {
        CustomAgentEntity e = new CustomAgentEntity();
        e.setId(row.getId());
        e.setName(row.getName());
        e.setDescription(row.getDescription());
        e.setAvatar(row.getAvatar());
        e.setBuiltin(row.isBuiltin());
        e.setTenantId(row.getTenantId());
        e.setCreatedBy(row.getCreatedBy());
        e.setConfig(row.getConfig());
        e.setCreatedAt(row.getCreatedAt());
        e.setUpdatedAt(row.getUpdatedAt());
        return e;
    }

    /** config 列（jsonb 文本）→ ObjectNode；非法 → RuntimeException（照 Go 的 json 失败路径）。 */
    public static ObjectNode parseAgentConfig(CustomAgentEntity row) {
        try {
            return (ObjectNode) MAPPER.readTree(row.getConfig() == null ? "{}" : row.getConfig());
        } catch (IOException e) {
            throw new RuntimeException("failed to parse agent config: " + e.getMessage(), e);
        }
    }
}
