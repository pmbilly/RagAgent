package com.ragagent.mcp.service;

import java.util.List;

import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.domain.McpToolPolicyPatch;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.mapper.McpToolApprovalRepository;
import org.springframework.stereotype.Service;

/**
 * 逐工具审批/启用策略的业务层（对照 Go internal/application/service/
 * mcp_tool_approval_service.go）。
 *
 * <p>三条不能被"优化"掉的语义：</p>
 * <ol>
 *   <li>策略操作前先确认服务可见（否则越权者可给任意 service_id 写策略行）；</li>
 *   <li>空补丁必须拒绝（两个字段都为 null 时没有可写内容）；</li>
 *   <li>{@code SetEnabled} 必须保留 approval、{@code SetRequireApproval} 必须保留 disabled
 *       ——补丁在仓储层是**部分列**更新，别在这里展开成全列写。</li>
 * </ol>
 */
@Service
public class McpToolApprovalService {

    private final McpToolApprovalRepository repo;
    private final McpServiceMapper mcpServiceMapper;

    public McpToolApprovalService(McpToolApprovalRepository repo, McpServiceMapper mcpServiceMapper) {
        this.repo = repo;
        this.mcpServiceMapper = mcpServiceMapper;
    }

    /** 对照 ListByService：先校验服务可见，再列策略（可为空列表） */
    public List<McpToolApproval> listByService(long tenantId, String serviceId) {
        requireService(tenantId, serviceId);
        return repo.listByService(tenantId, serviceId);
    }

    /** 对照 SetPolicy：工具名必填、补丁非空、服务存在 */
    public void setPolicy(long tenantId, String serviceId, String toolName,
                          Boolean requireApproval, Boolean enabled) {
        if (toolName == null || toolName.isEmpty()) {
            throw BizException.badRequest("tool_name is required");
        }
        if (requireApproval == null && enabled == null) {
            throw BizException.badRequest("require_approval or enabled is required");
        }
        requireService(tenantId, serviceId);
        repo.upsertPolicy(tenantId, serviceId, toolName,
                new McpToolPolicyPatch(requireApproval, enabled));
    }

    /** 对照 SetRequireApproval：只动 require_approval，enabled 保持不变 */
    public void setRequireApproval(long tenantId, String serviceId, String toolName, boolean require) {
        setPolicy(tenantId, serviceId, toolName, require, null);
    }

    /** 对照 SetEnabled：只动 enabled，require_approval 保持不变 */
    public void setEnabled(long tenantId, String serviceId, String toolName, boolean enabled) {
        setPolicy(tenantId, serviceId, toolName, null, enabled);
    }

    /** 对照 IsRequired：缺行 → false */
    public boolean isRequired(long tenantId, String serviceId, String toolName) {
        return repo.isRequired(tenantId, serviceId, toolName);
    }

    /** 对照 IsEnabled：<b>缺行 → true</b> */
    public boolean isEnabled(long tenantId, String serviceId, String toolName) {
        return repo.isEnabled(tenantId, serviceId, toolName);
    }

    private void requireService(long tenantId, String serviceId) {
        McpService svc = mcpServiceMapper.getByIdForTenant(tenantId, serviceId);
        if (svc == null) {
            throw BizException.notFound("mcp service not found");
        }
    }
}
