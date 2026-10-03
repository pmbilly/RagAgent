package com.ragagent.session.service;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.agent.management.domain.CustomAgentEntity;
import com.ragagent.agent.management.service.CustomAgentService;
import com.ragagent.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * agent 解析。
 * 空间分享裁撤后只有自有 agent 一条路径：本租户直查；查询失败用默认空配置。
 */
@Component
public class AgentResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CustomAgentService customAgentService;

    public AgentResolver(CustomAgentService customAgentService) {
        this.customAgentService = customAgentService;
    }

    /** 三元返回保留形状（effectiveTenantId 恒当前租户、sharedAgentReadOnly 恒 false）。 */
    public record ResolvedAgent(CustomAgentEntity row, long effectiveTenantId,
                                boolean sharedAgentReadOnly) {
    }

    public ResolvedAgent resolve(String agentId, long sourceTenantId) {
        if (agentId == null || agentId.isEmpty()) {
            return new ResolvedAgent(null, 0, false);
        }
        CustomAgentEntity customAgent = null;
        long effectiveTenantId = 0;
        Long currentTenant = TenantContext.currentTenantId();
        if (currentTenant != null && currentTenant != 0) {
            try {
                var result = customAgentService.getAgentByID(agentId, null);
                customAgent = result == null ? null : result.row();
                effectiveTenantId = currentTenant;
            } catch (RuntimeException e) {
                log.warn("Failed to get custom agent, agent ID: {}, error: {}, using default config",
                        agentId, e.toString());
            }
        }
        return new ResolvedAgent(customAgent, effectiveTenantId, false);
    }

    /** config 列（jsonb 文本）→ ObjectNode；非法 → RuntimeException。 */
    public static ObjectNode parseAgentConfig(CustomAgentEntity row) {
        try {
            return (ObjectNode) MAPPER.readTree(row.getConfig() == null ? "{}" : row.getConfig());
        } catch (IOException e) {
            throw new RuntimeException("failed to parse agent config: " + e.getMessage(), e);
        }
    }
}
