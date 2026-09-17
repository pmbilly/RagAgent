package com.ragagent.mcp.domain;

/**
 * 逐工具策略的**部分**更新（对照 Go types.MCPToolPolicyPatch）。
 * null 字段表示保持既有行不变；新插入的行对省略字段用
 * requireApproval=false / enabled=true。
 */
public record McpToolPolicyPatch(Boolean requireApproval, Boolean enabled) {

    public boolean isEmpty() {
        return requireApproval == null && enabled == null;
    }
}
