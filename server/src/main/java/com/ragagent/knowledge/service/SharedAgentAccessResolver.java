package com.ragagent.knowledge.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.ToolCapabilities;
import com.ragagent.auth.domain.TenantRole;
import com.ragagent.common.context.TenantContext;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.knowledge.domain.KnowledgeBase;
import com.ragagent.org.domain.AgentRow;
import com.ragagent.org.service.AgentShareService;
import com.ragagent.org.service.AgentShareSources;
import com.ragagent.org.service.OrgServiceException;
import com.ragagent.org.service.OrganizationService;
import com.ragagent.org.service.SharedAgentKBScope;

/**
 * 共享 agent 请求级解析（对照 Go handler/shared_agent_access.go
 * resolveSharedAgentForRequest + filterKnowledgeBasesForSharedAgent /
 * filterKnowledgeByAgentScope，W5α 收口）。
 *
 * <p>这是 KB 列表、文档搜索、批量恢复三个读端点的身份/共享边界；KB 范围强制
 * 在解析之后进行。错误形态逐字对照 Go：</p>
 * <ul>
 *   <li>调用方未认证 → 401 "Unauthorized"</li>
 *   <li>agent_source_tenant_id 非法 → 400 err.Error()（"invalid agent_source_tenant_id:
 *       strconv.ParseUint: ..."）</li>
 *   <li>share 不存在/无权限/agent 缺失 → 403 "no permission for this shared agent"</li>
 *   <li>其他解析错误 → 500 "Failed to verify shared agent access"</li>
 *   <li>agent 为空、tenant 为 0、显式 source 与 agent 租户不符 → 403 同上</li>
 * </ul>
 */
@Component
public class SharedAgentAccessResolver {

    private final AgentShareService agentShareService;

    public SharedAgentAccessResolver(AgentShareService agentShareService) {
        this.agentShareService = agentShareService;
    }

    /**
     * 对照 resolveSharedAgentForRequest。agentId 必须先过 LogSanitizer（调用方责任，
     * 与 Go 的 secutils.SanitizeForLog 一致）。
     */
    public AgentRow resolveForRequest(String agentId, String rawAgentSourceTenantId) {
        long callerTenant = TenantContext.currentTenantId() == null
                ? 0 : TenantContext.currentTenantId();
        String userId = TenantContext.currentUserId();
        if (callerTenant == 0 || userId == null || userId.isEmpty()) {
            throw new BizException(AppError.unauthorized("Unauthorized"));
        }
        long source;
        try {
            source = AgentShareSources.parse(rawAgentSourceTenantId);
        } catch (IllegalArgumentException e) {
            throw new BizException(AppError.badRequest(e.getMessage()));
        }
        TenantRole callerRole = OrganizationService.callerTenantRole();
        AgentRow agent;
        try {
            agent = agentShareService.getSharedAgentForTenant(callerTenant, callerRole, agentId,
                    source);
        } catch (OrgServiceException e) {
            String msg = e.getMessage();
            if ("agent share not found".equals(msg)
                    || "permission denied for this share operation".equals(msg)
                    || "agent not found".equals(msg)) {
                throw new BizException(AppError.forbidden("no permission for this shared agent"));
            }
            throw new BizException(AppError.internal("Failed to verify shared agent access"));
        } catch (RuntimeException e) {
            throw new BizException(AppError.internal("Failed to verify shared agent access"));
        }
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() == 0
                || (source != 0 && agent.getTenantId() != source)) {
            throw new BizException(AppError.forbidden("no permission for this shared agent"));
        }
        return agent;
    }

    /**
     * 对照 filterKnowledgeBasesForSharedAgent：scope 过滤共享 @KB 列表与 @文件搜索。
     * 能力检查只约束动态 "all" 选择；显式选择保持原行为。本过滤器不替代端点的
     * API-Key 范围检查。
     */
    public static List<KnowledgeBase> filterKnowledgeBasesForSharedAgent(List<KnowledgeBase> kbs,
                                                                         AgentRow agent) {
        SharedAgentKBScope scope = SharedAgentKBScope.from(agent);
        List<KnowledgeBase> filtered = new ArrayList<>();
        if (scope.isEmpty()) {
            return filtered;
        }
        JsonNode cfg = SharedAgentKBScope.parseConfig(agent.getConfig());
        String agentMode = SharedAgentKBScope.text(cfg, "agent_mode");
        List<String> allowedTools = SharedAgentKBScope.stringArray(cfg, "allowed_tools");
        ToolCapabilities.KbFilter filter =
                ToolCapabilities.deriveKbFilterForAgent(agentMode, allowedTools);
        for (KnowledgeBase kb : kbs) {
            long kbTenant = kb == null || kb.getTenantId() == null ? 0 : kb.getTenantId();
            if (kb == null || !scope.allows(kb.getId(), kbTenant)) {
                continue;
            }
            if (scope.isAll() && !filter.isEmpty()
                    && !ToolCapabilities.kbSatisfiesAgentRequirements(toKbCaps(kb), agentMode,
                            allowedTools)) {
                continue;
            }
            filtered.add(kb);
        }
        return filtered;
    }

    /** 对照 filterKnowledgeByAgentScope：批量恢复结果按 agent scope 过滤。 */
    public static <T> List<T> filterKnowledgeByAgentScope(List<T> knowledges,
                                                          SharedAgentKBScope scope,
                                                          java.util.function.Function<T, String> kbIdOf,
                                                          java.util.function.Function<T, Long> tenantOf) {
        List<T> filtered = new ArrayList<>(knowledges.size());
        for (T k : knowledges) {
            if (k == null) {
                continue;
            }
            Long t = tenantOf.apply(k);
            if (scope.allows(kbIdOf.apply(k), t == null ? 0 : t)) {
                filtered.add(k);
            }
        }
        return filtered;
    }

    private static ToolCapabilities.KbCaps toKbCaps(KnowledgeBase kb) {
        KnowledgeBase.Capabilities c = kb.capabilities();
        return new ToolCapabilities.KbCaps(c.vector(), c.keyword(), c.wiki(), c.graph(), c.faq());
    }
}
