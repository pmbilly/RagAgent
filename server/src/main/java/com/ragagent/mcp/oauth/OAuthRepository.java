package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpOAuthToken;

/**
 * OAuth 持久化端口（对照 Go {@code interfaces.MCPOAuthRepository}，
 * internal/types/interfaces/mcp_oauth.go）。
 *
 * <p><b>为什么在 Java 侧再抽一层接口</b>：Go 的 {@code OAuthManager}/{@code oauthRuntime}/
 * {@code dbTokenStore} 都依赖接口而非具体仓储，其测试用内存 fake
 * （{@code fakeOAuthRepo}/{@code lockedOAuthRepo}）验证租约与 principal 隔离；
 * 生产实现见 {@link McpOAuthRepositoryAdapter}（转发到既有的
 * {@link com.ragagent.mcp.mapper.McpOAuthRepository}）。</p>
 *
 * <p><b>不变式（硬契约，实现方必须保证）</b>：
 * <ul>
 *   <li>token 按 (tenant, principal_type, principal_id, service) 隔离；</li>
 *   <li>刷新租约抢占必须是<b>单条 UPDATE 的 CAS</b>，绝不能读-改-写，
 *       否则两个实例会同时成为所有者、把轮换型 refresh token 用两次。</li>
 * </ul>
 */
public interface OAuthRepository {

    // ── OAuth 客户端（每服务一个） ──────────────────────────────────────

    /** 未注册返回 {@code null}（对照 Go 的 (nil, nil)）。 */
    McpOAuthClient getClient(long tenantId, String serviceId);

    void saveClient(McpOAuthClient client);

    void deleteClient(long tenantId, String serviceId);

    // ── OAuth token（每 principal + 服务一个） ────────────────────────────

    McpOAuthToken getTokenForPrincipal(long tenantId, TenantContext.Principal principal, String serviceId);

    void saveTokenForPrincipal(McpOAuthToken token);

    void deleteTokenForPrincipal(long tenantId, TenantContext.Principal principal, String serviceId);

    /** CAS 抢占；返回 {@code true} 仅当这一条 UPDATE 恰好影响了 1 行。 */
    boolean tryAcquireTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                        String serviceId, String leaseId, OffsetDateTime leaseUntil);

    /** 非持有者释放是 no-op。 */
    void releaseTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                  String serviceId, String leaseId);
}
