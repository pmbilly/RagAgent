package com.ragagent.mcp.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.ragagent.common.error.BizException;
import com.ragagent.mcp.domain.McpToolApproval;
import com.ragagent.mcp.domain.McpToolPolicyPatch;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 逐工具策略仓储（对照 Go internal/application/repository/
 * mcp_tool_approval_repository.go 的 MCPToolApprovalRepository）。
 *
 * <p><b>UpsertPolicy 的语义（最容易翻错的一处）</b>：</p>
 * <ol>
 *   <li><b>部分列补丁</b>：只写 patch 里出现的列——不同字段的并发补丁不会互相覆盖
 *       （Go 靠 ON CONFLICT 的更新集只含补丁列实现，Java 靠 UPDATE 的 {@code <if>}）。</li>
 *   <li><b>显式写 false</b>：首次插入必须真的把 {@code enabled=false} 写进 DB。
 *       Java 的 boolean 零值是 false、实体默认值不可依赖，所以插入语句**逐列传值**。</li>
 *   <li>未提供的字段在**新行**上用 require_approval=false / enabled=true。</li>
 *   <li>空 patch（两个字段都是 null）必须拒绝。</li>
 * </ol>
 *
 * <p>UPDATE-未命中再 INSERT，唯一键冲突时回落 UPDATE：等价 Go 的
 * {@code clause.OnConflict + Assignments}，且 H2 / PG 通用。</p>
 */
@Component
public class McpToolApprovalRepository {

    /** 并发插入冲突后的重试次数（唯一键冲突说明别人刚插入，改走 UPDATE 即可） */
    private static final int MAX_ATTEMPTS = 3;

    private final McpToolApprovalMapper mapper;

    public McpToolApprovalRepository(McpToolApprovalMapper mapper) {
        this.mapper = mapper;
    }

    /** 对照 ListByService：按 tool_name 升序；可为空列表 */
    public List<McpToolApproval> listByService(long tenantId, String serviceId) {
        return mapper.listByService(tenantId, serviceId);
    }

    /** 对照 IsRequired：缺行 → false */
    public boolean isRequired(long tenantId, String serviceId, String toolName) {
        Boolean v = mapper.selectRequireApproval(tenantId, serviceId, toolName);
        return v != null && v;
    }

    /** 对照 IsEnabled：<b>缺行 → true</b>（保留逐工具设置之前的历史行为） */
    public boolean isEnabled(long tenantId, String serviceId, String toolName) {
        Boolean v = mapper.selectEnabled(tenantId, serviceId, toolName);
        return v == null || v;
    }

    /** 对照 UpsertPolicy */
    public void upsertPolicy(long tenantId, String serviceId, String toolName, McpToolPolicyPatch patch) {
        if (serviceId == null || serviceId.isEmpty() || toolName == null || toolName.isEmpty()) {
            throw BizException.badRequest("service_id and tool_name are required");
        }
        if (patch == null || patch.isEmpty()) {
            throw BizException.badRequest("require_approval or enabled is required");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 新行省略字段的缺省：require_approval=false、enabled=true（Go 同）
        boolean insertRequireApproval = patch.requireApproval() != null && patch.requireApproval();
        boolean insertEnabled = patch.enabled() == null || patch.enabled();

        if (mapper.updatePolicy(tenantId, serviceId, toolName,
                patch.requireApproval(), patch.enabled(), now) > 0) {
            return;
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                mapper.insertPolicy(UUID.randomUUID().toString(), tenantId, serviceId, toolName,
                        insertRequireApproval, insertEnabled, now, now);
                return;
            } catch (DataIntegrityViolationException e) {
                // 行已被并发请求插入：补丁语义仍要落到已存在的行上
                if (mapper.updatePolicy(tenantId, serviceId, toolName,
                        patch.requireApproval(), patch.enabled(), now) > 0) {
                    return;
                }
            }
        }
        throw BizException.internal("upsert mcp tool policy failed: concurrent patch did not settle");
    }
}
