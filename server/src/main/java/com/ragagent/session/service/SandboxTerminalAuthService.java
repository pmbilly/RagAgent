package com.ragagent.session.service;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.auth.domain.TenantMember;
import com.ragagent.auth.domain.User;
import com.ragagent.auth.mapper.AuthTokenMapper;
import com.ragagent.auth.service.TenantMemberService;
import com.ragagent.auth.service.UserService;
import com.ragagent.common.context.TenantContext;
import com.ragagent.config.TenantProperties;
import com.ragagent.session.domain.Session;
import com.ragagent.session.domain.SessionNotFoundException;
import com.ragagent.session.service.SandboxTerminalTicketService.AuthTokenRow;
import com.ragagent.session.service.SandboxTerminalTicketService.TicketClaims;

/**
 * 沙箱终端的握手/复查鉴权（对照 Go internal/application/service/sandbox_terminal_auth.go
 * 全文 189 行）。
 *
 * <p>与 HTTP 中间件刻意不同源（Go 注释原文语义）：resolveTenantRole 会自愈
 * （孤儿空间自动晋升 Owner 写库），而**开着 PTY 的定时复查只观察、不授权**。
 * 同样的"active membership / 跨空间超管 / RBAC fail-open 开关"三点保持同步；
 * 复查**不查 access token 过期**（axios 静默轮换不吊销旧行——握手铸票才查过期，
 * 登出/改密吊销全部行才会拆桥）。</p>
 *
 * <p>错误两分：{@link #DENIED}（确定性失格：行没了/吊销了/用户没了/成员没了/
 * 会话不属于调用方）与查找失败（DB 抖动——按原样上抛，按分钟重试，
 * 决不能把抖动当成"全员掉线"）。映射：DENIED → 401（握手）/
 * AUTH_REVOKED 拆桥（复查）；查找失败 → 500（握手）/WARN（复查）。</p>
 */
@Service
public class SandboxTerminalAuthService {

    /** 确定性失格（对照 ErrTerminalAuthDenied）。 */
    public static final class TerminalAuthDeniedException extends RuntimeException {
        public TerminalAuthDeniedException() { super("terminal authorization no longer valid"); }
    }

    public static final TerminalAuthDeniedException DENIED = new TerminalAuthDeniedException();

    private static final Logger log = LoggerFactory.getLogger(SandboxTerminalAuthService.class);

    private final UserService userService;
    private final TenantMemberService memberService;
    private final SessionService sessionService;
    private final AuthTokenMapper authTokenMapper;
    private final TenantProperties tenantProperties;

    public SandboxTerminalAuthService(UserService userService,
            TenantMemberService memberService,
            SessionService sessionService,
            AuthTokenMapper authTokenMapper,
            TenantProperties tenantProperties) {
        this.userService = userService;
        this.memberService = memberService;
        this.sessionService = sessionService;
        this.authTokenMapper = authTokenMapper;
        this.tenantProperties = tenantProperties;
    }

    private AuthTokenRow getAccessTokenById(String tokenId) {
        var row = authTokenMapper.selectById(tokenId == null ? "" : tokenId.trim());
        if (row == null) {
            return null;
        }
        return new AuthTokenRow(row.getId(), row.getUserId(), row.getTokenType(),
                row.isIsRevoked(), row.getExpiresAt());
    }

    /**
     * 对照 GetAccessTokenByValue（user.go L1283-1294）：按 JWT 字符串查存储行
     * （First 无 ORDER BY，多行同值时取实现返回的第一行）。查不到返回 null。
     */
    public AuthTokenRow getAccessTokenByValue(String tokenValue) {
        String value = tokenValue == null ? "" : tokenValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        var row = authTokenMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<com.ragagent.auth.domain.AuthToken>lambdaQuery()
                        .eq(com.ragagent.auth.domain.AuthToken::getToken, value),
                false);
        if (row == null) {
            return null;
        }
        return new AuthTokenRow(row.getId(), row.getUserId(), row.getTokenType(),
                row.isIsRevoked(), row.getExpiresAt());
    }

    /**
     * 对照 CheckSandboxTerminalAuth（L40-101）：握手（rejectExpired=true）与
     * 开着的 PTY（false）共用同一套检查。命中 DENIED 或抛
     * {@link TerminalAuthDeniedException}；其它 RuntimeException 是查找失败，原样上抛。
     */
    public User checkSandboxTerminalAuth(TicketClaims claims, boolean rejectExpired) {
        String userId = claims.userId() == null ? "" : claims.userId().trim();
        String sessionId = claims.sessionId() == null ? "" : claims.sessionId().trim();
        String tokenId = claims.tokenId() == null ? "" : claims.tokenId().trim();
        if (userId.isEmpty() || sessionId.isEmpty() || tokenId.isEmpty() || claims.tenantId() == 0) {
            throw DENIED;
        }

        AuthTokenRow token;
        try {
            token = getAccessTokenById(tokenId);
        } catch (RuntimeException e) {
            throw e;
        }
        if (token == null || !SandboxTerminalTicketService.accessTokenNotRevoked(token, userId)) {
            throw DENIED;
        }
        if (rejectExpired
                && !SandboxTerminalTicketService.accessTokenNotExpired(token, OffsetDateTime.now())) {
            throw DENIED;
        }

        User user = userService.getUserById(userId);
        if (user == null || !user.isIsActive()) {
            throw DENIED;
        }

        membershipStillValid(user, claims.tenantId());

        // 会话所有权复查：临时上下文 = (claims 租户, web 用户)（对照
        // sandboxTerminalAuthContext）。getOwnedSession 的 owner 范围读 TenantContext。
        com.ragagent.event.TenantContextSnapshot prev = com.ragagent.event.TenantContextSnapshot.capture();
        try {
            TenantContext.set(claims.tenantId(),
                    TenantContext.webUserPrincipal(userId), null, false, userId, false);
            sessionService.getOwnedSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw DENIED;
        } finally {
            prev.replay();
        }
        return user;
    }

    /**
     * 对照 terminalMembershipStillValid（L157-178）：active 成员 → 放行；
     * 跨空间超管 → 放行；RBAC 未强制（fail-open）→ 放行；否则 DENIED。
     * 查找失败按原样上抛（复查重试）。
     */
    private void membershipStillValid(User user, long tenantId) {
        TenantMember member = memberService.getMembership(user.getId(), tenantId);
        if (member != null && TenantMemberService.STATUS_ACTIVE.equals(member.getStatus())) {
            return;
        }
        if (user.isCanAccessAllTenants()) {
            return;
        }
        if (!tenantProperties.isRbacEnforced()) {
            return;
        }
        throw DENIED;
    }
}
