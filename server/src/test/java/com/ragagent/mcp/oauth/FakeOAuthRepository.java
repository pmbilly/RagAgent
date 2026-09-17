package com.ragagent.mcp.oauth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;

/**
 * 内存版 OAuth 仓储（对照 Go 测试的 {@code fakeOAuthRepo} / {@code lockedOAuthRepo}）。
 *
 * <p>与 Go 测试一致的两条关键语义：
 * <ol>
 *   <li><b>读写都返回克隆</b>——否则运行期拿到的是同一个对象引用，租约/材料的比对会被
 *       调用方的原地改写污染（Go 用 {@code cloneOAuthToken}）；</li>
 *   <li><b>租约 CAS 在锁内完成</b>——对照 {@code lockedOAuthRepo}，
 *       保证并发用例测的是"只有一个所有者"而不是测试自身的竞态。</li>
 * </ol>
 */
public final class FakeOAuthRepository implements OAuthRepository {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, McpOAuthClient> clients = new HashMap<>();
    private final Map<String, McpOAuthToken> tokens = new HashMap<>();

    static String tokenKey(long tenantId, TenantContext.Principal principal, String serviceId) {
        TenantContext.Principal p = McpPrincipal.normalize(principal);
        return tenantId + "|" + McpPrincipal.storageId(p) + "|" + serviceId;
    }

    static String clientKey(long tenantId, String serviceId) {
        return tenantId + "|" + serviceId;
    }

    /** 测试可见：直接塞一行（对照 Go 测试里的 {@code repo.tokens[key] = row}）。 */
    public void seed(TenantContext.Principal principal, McpOAuthToken row) {
        lock.lock();
        try {
            tokens.put(tokenKey(row.getTenantId(), principal, row.getServiceId()), row);
        } finally {
            lock.unlock();
        }
    }

    /** 测试可见：直接读一行（不克隆，供断言）。 */
    McpOAuthToken peek(TenantContext.Principal principal, long tenantId, String serviceId) {
        lock.lock();
        try {
            return tokens.get(tokenKey(tenantId, principal, serviceId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public McpOAuthClient getClient(long tenantId, String serviceId) {
        lock.lock();
        try {
            return clients.get(clientKey(tenantId, serviceId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void saveClient(McpOAuthClient client) {
        lock.lock();
        try {
            clients.put(clientKey(client.getTenantId(), client.getServiceId()), client);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteClient(long tenantId, String serviceId) {
        lock.lock();
        try {
            clients.remove(clientKey(tenantId, serviceId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public McpOAuthToken getTokenForPrincipal(long tenantId, TenantContext.Principal principal,
                                              String serviceId) {
        lock.lock();
        try {
            return cloneToken(tokens.get(tokenKey(tenantId, principal, serviceId)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void saveTokenForPrincipal(McpOAuthToken token) {
        lock.lock();
        try {
            McpOAuthToken copy = cloneToken(token);
            TenantContext.Principal p = new TenantContext.Principal(
                    token.getPrincipalType(), token.getPrincipalId());
            copy.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            tokens.put(tokenKey(token.getTenantId(), p, token.getServiceId()), copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteTokenForPrincipal(long tenantId, TenantContext.Principal principal,
                                        String serviceId) {
        lock.lock();
        try {
            tokens.remove(tokenKey(tenantId, principal, serviceId));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean tryAcquireTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                               String serviceId, String leaseId,
                                               OffsetDateTime leaseUntil) {
        lock.lock();
        try {
            McpOAuthToken row = tokens.get(tokenKey(tenantId, principal, serviceId));
            if (row == null
                    || (row.getRefreshLeaseUntil() != null
                        && row.getRefreshLeaseUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))) {
                return false;
            }
            row.setRefreshLeaseId(leaseId);
            row.setRefreshLeaseUntil(leaseUntil);
            row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releaseTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                         String serviceId, String leaseId) {
        lock.lock();
        try {
            McpOAuthToken row = tokens.get(tokenKey(tenantId, principal, serviceId));
            if (row != null && leaseId.equals(row.getRefreshLeaseId())) {
                row.setRefreshLeaseId("");
                row.setRefreshLeaseUntil(null);
                row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            }
        } finally {
            lock.unlock();
        }
    }

    /** 对照 Go {@code cloneOAuthToken}（含租约时刻的深拷贝）。 */
    static McpOAuthToken cloneToken(McpOAuthToken token) {
        if (token == null) {
            return null;
        }
        McpOAuthToken c = new McpOAuthToken();
        c.setId(token.getId());
        c.setTenantId(token.getTenantId());
        c.setUserId(token.getUserId());
        c.setPrincipalType(token.getPrincipalType());
        c.setPrincipalId(token.getPrincipalId());
        c.setServiceId(token.getServiceId());
        c.setAccessToken(token.getAccessToken());
        c.setRefreshToken(token.getRefreshToken());
        c.setTokenType(token.getTokenType());
        c.setExpiresAt(token.getExpiresAt());
        c.setRefreshLeaseId(token.getRefreshLeaseId());
        c.setRefreshLeaseUntil(token.getRefreshLeaseUntil());
        c.setCreatedAt(token.getCreatedAt());
        c.setUpdatedAt(token.getUpdatedAt());
        return c;
    }
}
