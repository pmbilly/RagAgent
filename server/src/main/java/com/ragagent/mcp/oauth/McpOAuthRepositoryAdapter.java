package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.mapper.McpOAuthRepository;

/**
 * {@link OAuthRepository} 的生产实现：纯转发到既有的
 * {@link McpOAuthRepository}（MyBatis-Plus + PG/H2）。
 *
 * <p>之所以是"适配器"而不是让 {@code McpOAuthRepository} 直接实现接口：本包不改动
 * 已存在的其它包文件；转发层零逻辑，语义完全等于直接调用。</p>
 */
public class McpOAuthRepositoryAdapter implements OAuthRepository {

    private final McpOAuthRepository delegate;

    public McpOAuthRepositoryAdapter(McpOAuthRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public McpOAuthClient getClient(long tenantId, String serviceId) {
        return delegate.getClient(tenantId, serviceId);
    }

    @Override
    public void saveClient(McpOAuthClient client) {
        delegate.saveClient(client);
    }

    @Override
    public void deleteClient(long tenantId, String serviceId) {
        delegate.deleteClient(tenantId, serviceId);
    }

    @Override
    public McpOAuthToken getTokenForPrincipal(long tenantId, TenantContext.Principal principal,
                                              String serviceId) {
        return delegate.getTokenForPrincipal(tenantId, principal, serviceId);
    }

    @Override
    public void saveTokenForPrincipal(McpOAuthToken token) {
        delegate.saveTokenForPrincipal(token);
    }

    @Override
    public void deleteTokenForPrincipal(long tenantId, TenantContext.Principal principal,
                                        String serviceId) {
        delegate.deleteTokenForPrincipal(tenantId, principal, serviceId);
    }

    @Override
    public boolean tryAcquireTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                               String serviceId, String leaseId,
                                               OffsetDateTime leaseUntil) {
        return delegate.tryAcquireTokenRefreshLease(tenantId, principal, serviceId, leaseId, leaseUntil);
    }

    @Override
    public void releaseTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                         String serviceId, String leaseId) {
        delegate.releaseTokenRefreshLease(tenantId, principal, serviceId, leaseId);
    }
}
