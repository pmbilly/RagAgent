package com.ragagent.mcp.domain;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 逐工具的审批/启用策略。
 *
 * **关键语义（容易翻错）**：
 * - **缺行 = enabled=true**（向后兼容）。工具清单本身来自 MCP ListTools，本表只存覆盖值。
 * - Java 的 boolean 字段零值是 false，而 DB 列默认是 true——落库时零值不能被省略，
 *   否则会意外落成默认 true。Java 侧在仓储层必须显式写入，不能依赖实体默认值。
 *
 * <p>⚠️ <b>本实体直接作为 GET /{id}/tool-approvals 的响应体</b>（裸数组，
 * 键名＝Java 字段名）；MyBatis 的列名仍按 map-underscore-to-camel-case 映射，
 * 两者互不干扰。</p>
 */
@TableName("mcp_tool_approvals")
public class McpToolApproval {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long tenantId;
    private String serviceId;
    private String toolName;
    private boolean requireApproval;
    /** 控制该工具是否暴露给 Agent；缺行视为 enabled */
    private boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { tenantId = v; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String v) { serviceId = v; }
    public String getToolName() { return toolName; }
    public void setToolName(String v) { toolName = v; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean v) { requireApproval = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
}
