package com.ragagent.mcp.oauth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.ragagent.common.context.TenantContext;
import com.ragagent.mcp.domain.McpAuthConfig;
import com.ragagent.mcp.domain.McpOAuthClient;
import com.ragagent.mcp.domain.McpPrincipal;
import com.ragagent.mcp.domain.McpService;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.McpContext;
import com.ragagent.mcp.protocol.McpServiceUrls;

/**
 * MCP OAuth2 授权码流程编排：发现 → 动态客户端注册 → 授权跳转 → 回调 code 交换。
 *
 * <p>token 按 (tenant, principal, service) 落库；注册的客户端按 (tenant, service)
 * 落库并<b>复用</b>（避免每次授权都走一轮 RFC 7591 注册）。</p>
 *
 * <p><b>依赖说明</b>：服务加载直接依赖
 * {@link McpServiceMapper#getByIdForTenant}（同一个查询：租户自有 + 内置 + 未软删）。
 * 刻意<b>不</b>依赖 {@code McpServiceService}——那会在 Spring 里形成
 * "McpServiceService → Optional&lt;McpOAuthSupport&gt; → OAuthManager → McpServiceService"
 * 的循环依赖。</p>
 */
public class OAuthManager {

    /** RFC 7591 动态注册时的 {@code client_name}。 */
    public static final String CLIENT_REGISTRATION_NAME = "WeKnora";

    /**
     * 浏览器落到公开回调路由后，code 交换的时限。
     * 该时限落在内部 {@link McpContext} 的 deadline 上（交换被 60s 封顶）。
     */
    public static final Duration CALLBACK_TIMEOUT = Duration.ofSeconds(60);

    /** OAuth 出站 HTTP 的统一 30s 超时。 */
    public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final OAuthRepository repo;
    private final McpServiceMapper serviceMapper;
    private final OAuthStateStore states;

    public OAuthManager(OAuthRepository repo, McpServiceMapper serviceMapper,
                        OAuthStateStore states) {
        this.repo = repo;
        this.serviceMapper = serviceMapper;
        this.states = states;
    }

    /** 测试可见：state 存储（Go 测试直接读 {@code manager.states}）。 */
    public OAuthStateStore states() {
        return states;
    }

    // ── handler 构造 ───────────────────────────────────────────────────

    /**
     * 绑定服务 + 按 principal 的
     * token 存储，并<b>在这里</b>做 SSRF 校验（两个 URL：服务 URL 与 metadata URL）。
     */
    public OAuthHandler newHandler(McpService service, long tenantId,
                                   TenantContext.Principal principal, String redirectUri) {
        if (service.getUrl() == null || service.getUrl().isEmpty()) {
            throw OAuthProtocolException.of("MCP service URL is required for OAuth");
        }
        // 保真要点：陈旧行/导入行可能绕过当前 SSRF 策略，构造点必须再查一次
        McpServiceUrls.validateServiceOutboundUrls(service);

        OAuthConfig cfg = new OAuthConfig()
                .redirectUri(redirectUri)
                .scopes(scopesOf(service))
                .tokenStore(new DbTokenStore(repo, tenantId, principal, service.getId()))
                .pkceEnabled(true)
                .authServerMetadataUrl(metadataUrlOf(service))
                .httpTimeout(HTTP_TIMEOUT);

        McpOAuthClient existing = repo.getClient(tenantId, service.getId());
        if (existing != null) {
            cfg.clientId(existing.getClientId());
            cfg.clientSecret(existing.getClientSecret());
        }
        OAuthHandler handler = new OAuthHandler(cfg);
        handler.setBaseUrl(service.getUrl());
        return handler;
    }

    // ── 发起授权 ───────────────────────────────────────────────────────

    /** 发起授权的返回值。 */
    public record StartResult(String authorizationUrl, String attemptId) {
    }

    /**
     * 发起一次授权。
     *
     * <p>{@code redirectUri} 是登记到授权服务器的<b>后端回调</b>地址；
     * {@code frontendRedirect} 是回调结束后把浏览器弹回的前端地址。</p>
     */
    public StartResult startAuthorization(McpService service, long tenantId,
                                          TenantContext.Principal principal,
                                          String redirectUri, String frontendRedirect) {
        if (!isOAuth(service)) {
            throw OAuthProtocolException.of(
                    "MCP service " + service.getId() + " does not use OAuth");
        }
        TenantContext.Principal normalized = McpPrincipal.normalize(principal);
        if (!McpPrincipal.valid(normalized)) {
            throw OAuthProtocolException.of(
                    "principal context is required to authorize OAuth MCP service " + service.getId());
        }

        OAuthHandler handler = newHandler(service, tenantId, normalized, redirectUri);

        // 每个服务只注册一次客户端，之后所有用户复用
        McpOAuthClient existing = repo.getClient(tenantId, service.getId());
        if (existing == null) {
            try {
                handler.registerClient(McpContext.none(), CLIENT_REGISTRATION_NAME);
            } catch (RuntimeException e) {
                throw OAuthProtocolException.of(
                        "dynamic client registration failed: " + e.getMessage(), e);
            }
            String clientId = handler.getClientId();
            if (clientId == null || clientId.isEmpty()) {
                throw OAuthProtocolException.of(
                        "dynamic client registration returned an empty client_id");
            }
            McpOAuthClient client = new McpOAuthClient();
            client.setTenantId(tenantId);
            client.setServiceId(service.getId());
            client.setClientId(clientId);
            client.setRedirectUri(redirectUri);
            try {
                repo.saveClient(client);
            } catch (RuntimeException e) {
                // 落库失败只告警，本次授权照常继续
                org.slf4j.LoggerFactory.getLogger(OAuthManager.class)
                        .warn("failed to persist MCP oauth client: {}", e.getMessage());
            }
        }

        String verifier = Pkce.generateCodeVerifier();
        String challenge = Pkce.generateCodeChallenge(verifier);
        String state = Pkce.generateState();

        String authUrl;
        try {
            authUrl = handler.getAuthorizationUrl(McpContext.none(), state, challenge);
        } catch (RuntimeException e) {
            throw OAuthProtocolException.of("failed to build authorization URL: " + e.getMessage(), e);
        }

        try {
            states.put(state, new OAuthState(
                    tenantId,
                    McpPrincipal.storageId(normalized),
                    OAuthState.Principal.of(normalized),
                    service.getId(),
                    verifier,
                    handler.getClientId(),
                    redirectUri,
                    frontendRedirect));
        } catch (RuntimeException e) {
            throw OAuthProtocolException.of(
                    "failed to persist authorization state: " + e.getMessage(), e);
        }
        return new StartResult(authUrl, state);
    }

    /**
     * 只持有 serviceID 的调用方
     * （如 IM 渠道）的便捷入口。
     */
    public String startAuthorizationForService(long tenantId, TenantContext.Principal principal,
                                               String serviceId, String redirectUri,
                                               String frontendRedirect) {
        McpService service;
        try {
            service = serviceMapper.getByIdForTenant(tenantId, serviceId);
        } catch (RuntimeException e) {
            throw OAuthProtocolException.of("failed to load MCP service: " + e.getMessage(), e);
        }
        if (service == null) {
            throw OAuthProtocolException.of("MCP service not found");
        }
        return startAuthorization(service, tenantId, principal, redirectUri, frontendRedirect)
                .authorizationUrl();
    }

    // ── 回调 ───────────────────────────────────────────────────────────

    /** 回调完成的返回值。 */
    public record CompleteResult(String frontendRedirect, String serviceId) {
    }

    /**
     * 完成回调：消费 state → 加载服务 → code 交换 → 记录完成。
     *
     * <p><b>要点 1（本文件最容易被"顺手修掉"的地方）</b>：下面的
     * {@code handler.setExpectedState(state)} 是<b>刻意的</b>——
     * 回调是<b>另一个 HTTP 请求</b>，handler 是重建的，其内部 {@code expectedState}
     * 是空的；若不再设一次，{@code ProcessAuthorizationResponse} 的 CSRF 校验会以
     * "no expected state found / invalid state" 告终。
     * 之所以安全：state 已在<b>服务端</b>由 {@link OAuthStateStore#take} 做过
     * 一次性校验（未知 / 已用过 / 已过期都会在上一行就抛错），能走到这里说明这次回调
     * 对应的正是服务端签发的那次授权。
     * <b>不要</b>因为"看起来绕过了 CSRF"而删掉它。</p>
     *
     * <p><b>要点 2</b>：{@code completeAttempt} 必须在 code 交换成功<b>之后</b>才调
     * （见 {@link OAuthStateStore}）。</p>
     */
    public CompleteResult completeAuthorization(String state, String code) {
        // 60s 超时兜底（见 CALLBACK_TIMEOUT 注释）
        McpContext ctx = McpContext.deadline(Instant.now().plus(CALLBACK_TIMEOUT));

        OAuthState st;
        try {
            st = states.take(state);
        } catch (RuntimeException e) {
            // state 都消费不了时，前端地址与 serviceID 都还不知道
            throw new OAuthCallbackException("", "", e.getMessage(), e);
        }
        String frontendRedirect = st.frontendRedirect();
        String serviceId = st.serviceId();

        TenantContext.Principal principal = McpPrincipal.normalize(st.principalOrNull());
        if (!McpPrincipal.valid(principal) && !st.userId().isEmpty()) {
            principal = McpPrincipal.normalize(
                    new TenantContext.Principal(McpPrincipal.WEB_USER, st.userId()));
        }
        if (!McpPrincipal.valid(principal)) {
            throw new OAuthCallbackException(frontendRedirect, serviceId,
                    "principal context is missing from OAuth state", null);
        }

        McpService service;
        try {
            service = serviceMapper.getByIdForTenant(st.tenantId(), st.serviceId());
        } catch (RuntimeException e) {
            throw new OAuthCallbackException(frontendRedirect, serviceId,
                    "failed to load MCP service: " + e.getMessage(), e);
        }
        if (service == null) {
            throw new OAuthCallbackException(frontendRedirect, serviceId,
                    "MCP service not found", null);
        }

        OAuthHandler handler;
        try {
            handler = newHandler(service, st.tenantId(), principal, st.redirectUri());
        } catch (RuntimeException e) {
            throw new OAuthCallbackException(frontendRedirect, serviceId, e.getMessage(), e);
        }

        // ⚠️ 刻意的 CSRF 绕过点，见方法注释「保真要点 1」——不要删。
        handler.setExpectedState(state);

        try {
            handler.processAuthorizationResponse(ctx, code, state, st.codeVerifier());
        } catch (RuntimeException e) {
            throw new OAuthCallbackException(frontendRedirect, serviceId,
                    "token exchange failed: " + e.getMessage(), e);
        }
        try {
            states.completeAttempt(state);
        } catch (RuntimeException e) {
            throw new OAuthCallbackException(frontendRedirect, serviceId,
                    "failed to record authorization completion: " + e.getMessage(), e);
        }
        // ProcessAuthorizationResponse 已通过 TokenStore 落库 token
        org.slf4j.LoggerFactory.getLogger(OAuthManager.class)
                .info("MCP OAuth authorized: service={} principal={}",
                        st.serviceId(), McpPrincipal.storageId(principal));
        return new CompleteResult(frontendRedirect, serviceId);
    }

    // ── 状态查询 ───────────────────────────────────────────────────────

    /**
     * 判定一次授权尝试是否完成。
     *
     * <p>语义：<b>这一次</b>授权尝试是否在<b>当前</b> principal + service 上完成。
     * 一行预先存在的 token 绝不能当作新弹窗的成功（否则弹窗一开就"绿"）。
     * 不匹配或过期都抛错。</p>
     */
    public boolean isAuthorizationAttemptComplete(long tenantId, TenantContext.Principal principal,
                                                  String serviceId, String attemptId) {
        OAuthAttempt attempt = states.attempt(attemptId);
        TenantContext.Principal normalized = McpPrincipal.normalize(principal);
        TenantContext.Principal attemptPrincipal = McpPrincipal.normalize(attempt.principalOrNull());
        if (attempt.tenantId() != tenantId
                || !attempt.serviceId().equals(serviceId)
                || !McpPrincipal.storageId(attemptPrincipal).equals(McpPrincipal.storageId(normalized))) {
            throw OAuthProtocolException.of(
                    "oauth authorization attempt does not match the current principal or service");
        }
        return attempt.completed();
    }

    /** 查询 token 生命周期状态；无 token 行时按未授权处理。 */
    public OAuthAuthorizationStatus authorizationStatus(long tenantId,
                                                        TenantContext.Principal principal,
                                                        String serviceId) {
        var token = repo.getTokenForPrincipal(tenantId, principal, serviceId);
        return OAuthAuthorizationStatus.of(
                token == null ? null : toOAuthToken(token), OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * access token 现在就能用才算已授权。
     * <b>加密列非空但已过期的行不算。</b>
     */
    public boolean isAuthorized(long tenantId, TenantContext.Principal principal, String serviceId) {
        return authorizationStatus(tenantId, principal, serviceId).authorized();
    }

    /** 删除该 principal 在该服务上的 token。 */
    public void revoke(long tenantId, TenantContext.Principal principal, String serviceId) {
        repo.deleteTokenForPrincipal(tenantId, principal, serviceId);
    }

    // ── 工具 ───────────────────────────────────────────────────────────

    private static OAuthToken toOAuthToken(com.ragagent.mcp.domain.McpOAuthToken row) {
        return new OAuthToken(row.getAccessToken(), row.getRefreshToken(), row.getTokenType(),
                row.getExpiresAt());
    }

    private static boolean isOAuth(McpService service) {
        McpAuthConfig ac = service.getAuthConfig();
        return ac != null && ac.isOAuth();
    }

    private static List<String> scopesOf(McpService service) {
        McpAuthConfig ac = service.getAuthConfig();
        return ac == null || ac.getScopes() == null ? List.of() : ac.getScopes();
    }

    private static String metadataUrlOf(McpService service) {
        McpAuthConfig ac = service.getAuthConfig();
        if (ac == null || ac.getAuthServerMetadataUrl() == null) {
            return "";
        }
        return ac.getAuthServerMetadataUrl();
    }
}
