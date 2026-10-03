package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpOAuthToken;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.protocol.McpAuthorizationRequiredException;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpOAuthRuntime;

/**
 * 运行期 token 生命周期。
 *
 * <p>核心是"跨实例单所有者"语义，规则逐条如下：</p>
 *
 * <h3>保鲜判定（{@code ensureFresh}）</h3>
 * <ol>
 *   <li>先查库：没有行 / access token 为空 → 要重新授权；</li>
 *   <li>{@code force=false} 且 token 距过期还超过 {@value #REFRESH_SKEW_SECONDS}s → 直接用；</li>
 *   <li><b>没有 refresh token 的行不能被 skew 提前判死</b>：它的寿命就是真实过期时刻，
 *       {@code expiresAt > now} 就仍可用（刷新 skew 不得缩短其寿命）；</li>
 *   <li>确实要刷新却没有 refresh token → <b>删掉这行</b>再报要重新授权
 *       （留着只会让后续每次调用都以同样方式失败）。</li>
 * </ol>
 *
 * <h3>刷新租约（{@code refreshWithLease} / {@code refreshAsLeaseOwner}）</h3>
 * <ol>
 *   <li>每轮用新的 leaseId 做 <b>单条 UPDATE 的 CAS</b> 抢占，抢到才当所有者；</li>
 *   <li>没抢到就等 {@value #REFRESH_POLL_MILLIS}ms 后<b>重读</b>：若别人已经把 token
 *       换成新的且还没接近过期，直接返回成功——<b>绝不</b>把刚轮换过的 refresh token
 *       再用一遍；</li>
 *   <li>成为所有者后<b>再读一次</b>：抢占前可能刚有别的所有者完成刷新，同样要避开；</li>
 *   <li>释放走"脱离取消信号 + 5s 超时"的收尾，释放失败只记日志
 *       （否则一次日志故障会把成功的刷新变成失败）。</li>
 * </ol>
 *
 * <h3>失败分类</h3>
 * <ul>
 *   <li><b>永久失败</b>（invalid_grant / invalid_token / bad_refresh_token / expired_token，
 *       或 "status 400"）：删 token，报要重新授权；其中 invalid_client /
 *       unauthorized_client（或 "status 401"）连<b>客户端注册</b>一起删——凭据本身失效了，
 *       只删 token 下次还是同样的失败；</li>
 *   <li><b>临时失败</b>：<b>保留</b> token，抛 {@link OAuthRefreshTemporaryException}
 *       （这是运维故障，不该弹授权窗）。</li>
 * </ul>
 */
public class OAuthRuntime implements McpOAuthRuntime {

    /** 距过期不足 30s 才提前刷新。 */
    public static final Duration REFRESH_SKEW = Duration.ofSeconds(30);
    /** 租约时长下限。 */
    public static final Duration REFRESH_LEASE = Duration.ofSeconds(45);
    /** 抢不到租约时的轮询间隔。 */
    public static final long REFRESH_POLL_MILLIS = 100L;
    /** 释放租约时的收尾超时（5s）。 */
    static final long RELEASE_GRACE_MILLIS = 5000L;

    private static final long REFRESH_SKEW_SECONDS = 30L;

    private final OAuthRepository repo;
    private final long tenantId;
    private final TenantContext.Principal principal;
    private final String serviceId;
    private final OAuthHandler handler;
    private final Duration leaseDuration;

    /** 构造 runtime：绑定 (tenant, principal, service) 与 handler。 */
    public OAuthRuntime(OAuthRepository repo, long tenantId, TenantContext.Principal principal,
                        String serviceId, String baseUrl, OAuthConfig cfg) {
        this.repo = repo;
        this.tenantId = tenantId;
        this.principal = McpPrincipal.normalize(principal);
        this.serviceId = serviceId;
        this.handler = new OAuthHandler(cfg);
        this.handler.setBaseUrl(baseUrl);
        // 租约必须比上游 HTTP 超时更长，否则"还在飞的刷新"会被下一个所有者重复执行
        Duration effective = REFRESH_LEASE;
        if (cfg.httpTimeout() != null && cfg.httpTimeout().toSeconds() > 0) {
            Duration candidate = cfg.httpTimeout().plusSeconds(15);
            if (candidate.compareTo(effective) > 0) {
                effective = candidate;
            }
        }
        this.leaseDuration = effective;
    }

    /** 测试可见：当前租约时长。 */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /** 测试/日志可见：本 runtime 绑定的 principal（Go 测试直接读 {@code runtime.principal}）。 */
    public TenantContext.Principal principal() {
        return principal;
    }

    /** 测试可见：底层 handler（Go 测试用 {@code runtime.handler} 造 401）。 */
    public OAuthHandler handler() {
        return handler;
    }

    // ── McpOAuthRuntime ────────────────────────────────────────────────

    /**
     * 保鲜判定入口。
     *
     * @param forceRefresh 上一次调用因授权失败 → 强制刷新一次再重试
     * @param trigger      触发强制刷新的原始异常；实现从这里取 handler。
     *                     注意：{@link OAuthHandler} 的刷新路径不依赖 handler 身份
     *                     （client_id 已在构造期写进配置），故取不到时退回本 runtime 的 handler。
     */
    @Override
    public void ensureFresh(McpContext ctx, boolean forceRefresh, Throwable trigger) {
        ensureFreshWith(ctx, forceRefresh, handlerFrom(trigger));
    }

    /** 授权失败判定：本包的两类异常 + 任意 401 信号。 */
    @Override
    public boolean isAuthorizationFailure(Throwable e) {
        if (e == null) {
            return false;
        }
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof OAuthReauthorizationRequiredException
                    || cur instanceof McpAuthorizationRequiredException) {
                return true;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return false;
    }

    /** 从异常链里取回引发 401 的 handler。 */
    private static OAuthHandler handlerFrom(Throwable trigger) {
        Throwable cur = trigger;
        while (cur != null) {
            if (cur instanceof OAuthAuthorizationRequiredException required) {
                return required.handler();
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }

    // ── 保鲜 ───────────────────────────────────────────────────────────

    /**
     * 保鲜实现。
     *
     * <p>刻意不叫 {@code ensureFresh}：避免与接口的
     * {@code ensureFresh(ctx, boolean, Throwable)} 在 {@code null} 实参下产生重载歧义
     * （接口方法与实现是两个不同名字）。</p>
     */
    public void ensureFreshWith(McpContext ctx, boolean force, OAuthHandler override) {
        McpOAuthToken row = repo.getTokenForPrincipal(tenantId, principal, serviceId);
        if (row == null || isBlank(row.getAccessToken())) {
            throw new OAuthReauthorizationRequiredException("no token is stored");
        }
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (!force) {
            OffsetDateTime expiresAt = row.getExpiresAt();
            if (expiresAt == null || expiresAt.isAfter(now.plus(REFRESH_SKEW))) {
                return;
            }
            // 没有 refresh token 的行靠真实过期时刻活着：skew 不得缩短它的寿命
            if (isBlank(row.getRefreshToken()) && expiresAt.isAfter(now)) {
                return;
            }
        }
        if (isBlank(row.getRefreshToken())) {
            repo.deleteTokenForPrincipal(tenantId, principal, serviceId);
            throw new OAuthReauthorizationRequiredException(
                    "the access token expired and no refresh token is available");
        }
        refreshWithLease(ctx, row, override);
    }

    // ── 租约 ───────────────────────────────────────────────────────────

    /** 带租约的刷新循环。 */
    private void refreshWithLease(McpContext ctx, McpOAuthToken observed, OAuthHandler override) {
        while (true) {
            String leaseId = UUID.randomUUID().toString();
            Duration duration = leaseDuration == null || leaseDuration.isZero()
                    ? REFRESH_LEASE : leaseDuration;
            OffsetDateTime leaseUntil = OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(duration);

            boolean acquired;
            try {
                acquired = repo.tryAcquireTokenRefreshLease(
                        tenantId, principal, serviceId, leaseId, leaseUntil);
            } catch (RuntimeException e) {
                throw OAuthProtocolException.of(
                        "claim MCP OAuth token refresh: " + e.getMessage(), e);
            }
            if (acquired) {
                refreshAsLeaseOwner(ctx, observed, leaseId, override);
                return;
            }

            // 等待轮询间隔期间响应取消
            if (ctx.isCancelled()) {
                throw OAuthProtocolException.of("context canceled while waiting for MCP OAuth refresh");
            }
            sleepPoll();

            McpOAuthToken current;
            try {
                current = repo.getTokenForPrincipal(tenantId, principal, serviceId);
            } catch (RuntimeException e) {
                throw OAuthProtocolException.of(
                        "reload MCP OAuth token after concurrent refresh: " + e.getMessage(), e);
            }
            if (current == null || isBlank(current.getAccessToken())) {
                throw new OAuthReauthorizationRequiredException("the refresh token is no longer valid");
            }
            if (tokenMaterialChanged(current, observed)) {
                OffsetDateTime expiresAt = current.getExpiresAt();
                if (expiresAt == null
                        || expiresAt.isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(REFRESH_SKEW))) {
                    return;
                }
                observed = current;
            }
        }
    }

    private static void sleepPoll() {
        try {
            Thread.sleep(REFRESH_POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw OAuthProtocolException.of("interrupted while waiting for MCP OAuth refresh", e);
        }
    }

    /** 以租约所有者身份执行刷新。 */
    private void refreshAsLeaseOwner(McpContext ctx, McpOAuthToken observed, String leaseId,
                                     OAuthHandler override) {
        try {
            McpOAuthToken current;
            try {
                current = repo.getTokenForPrincipal(tenantId, principal, serviceId);
            } catch (RuntimeException e) {
                throw OAuthProtocolException.of(
                        "reload MCP OAuth token before refresh: " + e.getMessage(), e);
            }
            if (current == null || isBlank(current.getAccessToken())) {
                throw new OAuthReauthorizationRequiredException("no token is stored");
            }
            // 抢占之前可能刚有别的所有者完成刷新：绝不要把已经轮换掉的 refresh token 再用一次
            if (tokenMaterialChanged(current, observed)) {
                OffsetDateTime expiresAt = current.getExpiresAt();
                if (expiresAt == null
                        || expiresAt.isAfter(OffsetDateTime.now(java.time.ZoneOffset.UTC).plus(REFRESH_SKEW))) {
                    return;
                }
            }
            if (isBlank(current.getRefreshToken())) {
                invalidateToken(ctx, false, "no refresh token is available");
                return;
            }

            OAuthHandler effective = override != null ? override : handler;
            OAuthToken refreshed = null;
            RuntimeException refreshError = null;
            try {
                refreshed = effective.refreshToken(ctx, current.getRefreshToken());
            } catch (RuntimeException e) {
                refreshError = e;
            }
            if (refreshError == null && refreshed != null && !isBlank(refreshed.accessToken())) {
                return;
            }
            if (refreshError == null) {
                refreshError = OAuthProtocolException.of(
                        "authorization server returned an empty access token");
            }
            PermanentFailure verdict = permanentRefreshFailure(refreshError);
            if (verdict.permanent()) {
                invalidateToken(ctx, verdict.resetClient(),
                        "the refresh token or OAuth client is no longer valid");
                return;
            }
            throw new OAuthRefreshTemporaryException(refreshError);
        } finally {
            // 收尾释放租约，失败只告警
            releaseLeaseQuietly(leaseId);
        }
    }

    private void releaseLeaseQuietly(String leaseId) {
        try {
            repo.releaseTokenRefreshLease(tenantId, principal, serviceId, leaseId);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(OAuthRuntime.class)
                    .warn("failed to release MCP OAuth refresh lease: {}", e.getMessage());
        }
    }

    /**
     * token 的"材料"（access / refresh / 过期时刻）
     * 任一变化即视为被别人换过。<b>过期时刻按瞬时比较</b>，
     * 不是按偏移量比较。
     */
    static boolean tokenMaterialChanged(McpOAuthToken current, McpOAuthToken observed) {
        if (current == null || observed == null) {
            return current != observed;
        }
        return !Objects.equals(current.getAccessToken(), observed.getAccessToken())
                || !Objects.equals(current.getRefreshToken(), observed.getRefreshToken())
                || !Objects.equals(instantOf(current.getExpiresAt()), instantOf(observed.getExpiresAt()));
    }

    private static Instant instantOf(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    /** 删除失效 token（可选连带客户端注册），并抛"需重新授权"。 */
    private void invalidateToken(McpContext ctx, boolean resetClient, String reason) {
        try {
            repo.deleteTokenForPrincipal(tenantId, principal, serviceId);
        } catch (RuntimeException e) {
            throw OAuthProtocolException.of("delete invalid MCP OAuth token: " + e.getMessage(), e);
        }
        if (resetClient) {
            try {
                repo.deleteClient(tenantId, serviceId);
            } catch (RuntimeException e) {
                throw OAuthProtocolException.of(
                        "delete invalid MCP OAuth client registration: " + e.getMessage(), e);
            }
        }
        throw new OAuthReauthorizationRequiredException(reason);
    }

    /** 永久失败判定结果。 */
    record PermanentFailure(boolean permanent, boolean resetClient) {
    }

    /**
     * 永久/临时失败判定。
     *
     * <p>先看结构化 error_code；拿不到才回落到<span>异常消息文本</span>里的
     * {@code "status 400"} / {@code "status 401"} 匹配——后者依赖
     * {@link OAuthProtocolException#ofRawStatus} 生成的文案，改文案会改变判定。</p>
     */
    static PermanentFailure permanentRefreshFailure(Throwable err) {
        OAuthError oauthError = findOAuthError(err);
        if (oauthError != null) {
            switch (oauthError.errorCode().toLowerCase(Locale.ROOT)) {
                case "invalid_grant", "invalid_token", "bad_refresh_token", "expired_token" ->
                {
                    return new PermanentFailure(true, false);
                }
                case "invalid_client", "unauthorized_client" -> {
                    return new PermanentFailure(true, true);
                }
                default -> {
                    // 其它 error_code 落到下面的文本兜底
                }
            }
        }
        String lower = err == null || err.getMessage() == null
                ? "" : err.getMessage().toLowerCase(Locale.ROOT);
        if (lower.contains("status 400")) {
            return new PermanentFailure(true, false);
        }
        if (lower.contains("status 401")) {
            return new PermanentFailure(true, true);
        }
        return new PermanentFailure(false, false);
    }

    private static OAuthError findOAuthError(Throwable err) {
        Throwable cur = err;
        while (cur != null) {
            if (cur instanceof OAuthProtocolException protocol && protocol.oauthError() != null) {
                return protocol.oauthError();
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }
}
