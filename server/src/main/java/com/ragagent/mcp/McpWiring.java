package com.ragagent.mcp;

import com.ragagent.agent.approval.Adapter;
import com.ragagent.agent.approval.Cancellation;
import com.ragagent.agent.approval.Checker;
import com.ragagent.agent.approval.Gate;
import com.ragagent.agent.approval.GateOptions;
import com.ragagent.agent.approval.RedisPubSub;
import com.ragagent.mcp.oauth.McpOAuthSupportImpl;
import com.ragagent.mcp.protocol.McpClientManager;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import com.ragagent.mcp.service.McpToolApprovalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 模块的 bean 接线（对照 Go internal/container/container.go 的装配段）。
 *
 * 这里补上两个此前只有接口、没有实例的依赖：
 * - {@link McpClientManager}：MCP 连接池（对照 Go 的 {@code *mcppkg.Manager}）
 * - {@link Gate}：工具审批门（对照 Go 的 {@code approval.NewGate(...)}）
 *
 * 两者的消费方此前用 {@code Optional<...>} 注入，缺省时走 Go 的 nil 分支
 * （返回 500 / 业务失败）。这里提供实例后，那些分支不再触发。
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
     * 工具审批门（对照 Go {@code approval.NewGate(cfg, &approval.Adapter{Svc: s}, rdb)}）。
     *
     * <p>Checker 由 {@link McpToolApprovalService} 适配而来：Go 的 Gate 直接断言
     * {@code Adapter{Svc}} 是否实现 {@code enabledChecker} / 批量查询接口；Java 侧
     * 用 {@link Adapter} 包一层，保持"目录批量查询走单次查询"的优化路径。</p>
     *
     * <p>Redis 缺失时传 null = Go 的 Lite 单实例行为（审批结果不跨实例广播）。</p>
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
        // timeoutSeconds<=0 → GateOptions 用默认（10 分钟，对照 Go 的 10*time.Minute）
        return new Gate(GateOptions.fromConfig(timeoutSeconds > 0 ? timeoutSeconds : null),
                checker, redis.getIfAvailable());
    }
}
