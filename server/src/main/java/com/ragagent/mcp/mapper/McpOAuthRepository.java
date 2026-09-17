package com.ragagent.mcp.mapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * OAuth 客户端 / token 仓储（对照 Go internal/application/repository/mcp_oauth.go 的
 * mcpOAuthRepository）。
 *
 * <p>Go 用 {@code clause.OnConflict} 表达"存在则改、不存在则插"；Java 用
 * UPDATE-未命中再-INSERT，并用唯一键冲突兜住竞态（并发下两个请求同时 INSERT，
 * 输家改走 UPDATE）。语义与 ON CONFLICT DO UPDATE 一致，且 H2 / PG 通用。</p>
 *
 * <p><b>principal 隔离与租约 CAS 是硬契约</b>：
 * token 按 (tenant, principal_type, principal_id, service) 隔离；
 * 跨实例刷新靠 refresh 租约，抢占必须是单条 UPDATE 的 CAS，不能读-改-写。</p>
 */
@Component
public class McpOAuthRepository {

    private final McpOAuthClientMapper clientMapper;
    private final McpOAuthTokenMapper tokenMapper;

    public McpOAuthRepository(McpOAuthClientMapper clientMapper, McpOAuthTokenMapper tokenMapper) {
        this.clientMapper = clientMapper;
        this.tokenMapper = tokenMapper;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── OAuth 客户端（每服务一个） ──────────────────────────────────────

    /** 对照 GetClient：未注册返回 null（Go 返回 (nil, nil)） */
    public McpOAuthClient getClient(long tenantId, String serviceId) {
        return clientMapper.find(tenantId, serviceId);
    }

    /** 对照 SaveClient：upsert，恒刷新 updated_at */
    public void saveClient(McpOAuthClient client) {
        client.setUpdatedAt(now());
        if (client.getId() == null || client.getId().isEmpty()) {
            client.setId(UUID.randomUUID().toString());
        }
        if (client.getCreatedAt() == null) {
            client.setCreatedAt(client.getUpdatedAt());
        }
        if (clientMapper.updateExisting(client) == 0) {
            try {
                clientMapper.insert(client);
            } catch (DataIntegrityViolationException e) {
                // 并发注册：另一个请求先插进去了，改走更新
                clientMapper.updateExisting(client);
            }
        }
    }

    public void deleteClient(long tenantId, String serviceId) {
        clientMapper.deleteForService(tenantId, serviceId);
    }

    // ── OAuth token（每 principal + 服务一个） ────────────────────────────

    /** 对照 GetToken：历史 (tenant, user, service) 入口，等价于 web_user principal */
    public McpOAuthToken getToken(long tenantId, String userId, String serviceId) {
        return getTokenForPrincipal(tenantId,
                new TenantContext.Principal(McpPrincipal.WEB_USER, userId), serviceId);
    }

    /** 对照 GetTokenForPrincipal：principal 无效返回 null */
    public McpOAuthToken getTokenForPrincipal(long tenantId, TenantContext.Principal principal,
                                              String serviceId) {
        TenantContext.Principal p = McpPrincipal.normalize(principal);
        if (!McpPrincipal.valid(p)) {
            return null;
        }
        return tokenMapper.findForPrincipal(tenantId, p.type(), p.id(), serviceId);
    }

    /** 对照 SaveToken：principal 缺省时回落为 (web_user, UserID) */
    public void saveToken(McpOAuthToken token) {
        if (isBlank(token.getPrincipalType()) || isBlank(token.getPrincipalId())) {
            token.setPrincipalType(McpPrincipal.WEB_USER);
            token.setPrincipalId(token.getUserId());
        }
        saveTokenForPrincipal(token);
    }

    /**
     * 对照 SaveTokenForPrincipal：principal 必填；user_id 为空时填 principal.StorageID()。
     *
     * @throws IllegalArgumentException principal 缺失（Go 返回 error）
     */
    public void saveTokenForPrincipal(McpOAuthToken token) {
        if (isBlank(token.getPrincipalType()) || isBlank(token.getPrincipalId())) {
            throw new IllegalArgumentException("mcp oauth token requires principal_type and principal_id");
        }
        if (isBlank(token.getUserId())) {
            token.setUserId(McpPrincipal.storageId(new TenantContext.Principal(
                    token.getPrincipalType(), token.getPrincipalId())));
        }
        token.setUpdatedAt(now());
        if (token.getId() == null || token.getId().isEmpty()) {
            token.setId(UUID.randomUUID().toString());
        }
        if (token.getCreatedAt() == null) {
            token.setCreatedAt(token.getUpdatedAt());
        }
        if (tokenMapper.updateExisting(token) == 0) {
            try {
                tokenMapper.insert(token);
            } catch (DataIntegrityViolationException e) {
                tokenMapper.updateExisting(token);
            }
        }
    }

    public void deleteToken(long tenantId, String userId, String serviceId) {
        deleteTokenForPrincipal(tenantId, new TenantContext.Principal(McpPrincipal.WEB_USER, userId),
                serviceId);
    }

    /** 对照 DeleteTokenForPrincipal：principal 无效 → 静默 no-op */
    public void deleteTokenForPrincipal(long tenantId, TenantContext.Principal principal, String serviceId) {
        TenantContext.Principal p = McpPrincipal.normalize(principal);
        if (!McpPrincipal.valid(p)) {
            return;
        }
        tokenMapper.deleteForPrincipal(tenantId, p.type(), p.id(), serviceId);
    }

    /**
     * 对照 TryAcquireTokenRefreshLease：单条 UPDATE 的 CAS，
     * {@code RowsAffected == 1} 才算抢到（0 = 行不存在 / 未过期 / principal 无效）。
     */
    public boolean tryAcquireTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                               String serviceId, String leaseId,
                                               OffsetDateTime leaseUntil) {
        TenantContext.Principal p = McpPrincipal.normalize(principal);
        if (!McpPrincipal.valid(p) || isBlank(leaseId)) {
            return false;
        }
        return tokenMapper.tryAcquireLease(tenantId, p.type(), p.id(), serviceId, leaseId, leaseUntil,
                now()) == 1;
    }

    /** 对照 ReleaseTokenRefreshLease：非持有者释放是 no-op */
    public void releaseTokenRefreshLease(long tenantId, TenantContext.Principal principal,
                                         String serviceId, String leaseId) {
        TenantContext.Principal p = McpPrincipal.normalize(principal);
        if (!McpPrincipal.valid(p) || isBlank(leaseId)) {
            return;
        }
        tokenMapper.releaseLease(tenantId, p.type(), p.id(), serviceId, leaseId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
