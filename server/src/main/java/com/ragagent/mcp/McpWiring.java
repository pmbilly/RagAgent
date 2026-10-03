package com.ragagent.mcp;

import com.ragagent.mcp.service.Adapter;
import com.ragagent.common.approval.Cancellation;
import com.ragagent.common.approval.Checker;
import com.ragagent.common.approval.Gate;
import com.ragagent.common.approval.GateOptions;
import com.ragagent.common.approval.RedisPubSub;
import com.ragagent.mcp.oauth.McpOAuthSupportImpl;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import com.ragagent.mcp.service.McpToolApprovalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 模块的 bean 接线。
 *
 * 这里补上两个此前只有接口、没有实例的依赖：
 * - {@link McpClientManager}：MCP 连接池
 * - {@link Gate}：工具审批门
 *
 * 两者的消费方此前用 {@code Optional<...>} 注入，缺省时走"返回 500 / 业务失败"的兜底
 * 分支。这里提供实例后，那些分支不再触发。
 */
@Configuration
public class McpWiring {

    /**
     * MCP 连接池。OAuth 支持由 {@link McpOAuthSupportImpl} 提供（协议层预留的注入点）。
     */
    @Bean(destroyMethod = "shutdown")
    public McpClientManager mcpClientManager(ObjectProvider<McpOAuthSupport> oauthSupport) {
        return new McpClientManager(oauthSupport.getIfAvailable());
    }

    /**
     * 工具审批门。
     *
     * <p>Checker 由 {@link McpToolApprovalService} 适配而来：用 {@link Adapter}
     * 包一层，保持"目录批量查询走单次查询"的优化路径。</p>
     *
     * <p>Redis 缺失时传 null：单实例模式，审批结果不跨实例广播。</p>
     */
    @Bean(destroyMethod = "close")
    public Gate toolApprovalGate(McpToolApprovalService toolApprovalService,
                                 ObjectProvider<RedisPubSub> redis,
                                 @Value("${weknora.agent.tool-approval-timeout-seconds:0}") int timeoutSeconds) {
        Checker checker = new Adapter(new Checker() {
            @Override
            public boolean isRequired(Cancellation ctx, long tenantId, String serviceId, String toolName) {
                return toolApprovalService.isRequired(tenantId, serviceId, toolName);
            }

            @Override
            public boolean isEnabled(Cancellation ctx, long tenantId, String serviceId, String toolName) {
                return toolApprovalService.isEnabled(tenantId, serviceId, toolName);
            }
        });
        // timeoutSeconds<=0 → GateOptions 用默认值（10 分钟）
        return new Gate(GateOptions.fromConfig(timeoutSeconds > 0 ? timeoutSeconds : null),
                checker, redis.getIfAvailable());
    }
}
