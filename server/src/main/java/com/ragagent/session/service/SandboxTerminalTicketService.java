package com.ragagent.session.service;

import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ragagent.auth.service.JwtService;

import io.jsonwebtoken.Claims;

/**
 * 沙箱终端握手票据（对照 Go internal/application/service/sandbox_terminal_ticket.go 全文）。
 *
 * <p>浏览器 WebSocket 握手带不了 Authorization 头：浏览器先经**普通认证的 POST**
 * （terminal-ticket）换一张 2 分钟短票据（绑定会话 + 铸票 access-token 的 id），
 * 再在 WS 握手的 ticket query 参数里出示。票据本身**不是**访问令牌——
 * ValidateToken 拒绝它（user.go L1246），AuthFilter 白名单也不放行普通会话路由。</p>
 *
 * <p>签发走 {@link JwtService#generateSandboxTerminalTicket}（同一把 HS256 key）；
 * 解析走 {@link JwtService#parseSigned}（签名 + exp 校验，与 jwt.Parse 默认语义一致）。
 * 票据在升级后即被丢弃：开着 PTY 用 token_id 轮询复查，登出/吊销可拆桥。</p>
 */
@Service
public class SandboxTerminalTicketService {

    /** 对照 DefaultSandboxTerminalTicketTTL = 2 * time.Minute。 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

    private static final String TICKET_TYPE = "sandbox_terminal";

    private static final Logger log = LoggerFactory.getLogger(SandboxTerminalTicketService.class);

    private final JwtService jwtService;

    public SandboxTerminalTicketService(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** 票据claims（对照 SandboxTerminalTicketClaims）。 */
    public record TicketClaims(String userId, long tenantId, String sessionId, String tokenId) {}

    /** 解析失败（签名/过期/类型/字段缺失）。handler 统一映射 401。 */
    public static final class TicketInvalidException extends RuntimeException {
        public TicketInvalidException(String message) { super(message); }
    }

    /**
     * 对照 IssueSandboxTerminalTicket：必填校验失败抛
     * {@link TicketInvalidException}（Go 返回 error，handler 落 500
     * "failed to issue ticket"——校验在 controller 已先行，实际不可达）。
     */
    public String issue(String userId, long tenantId, String sessionId, String tokenId,
            Duration ttl) {
        String uid = trim(userId);
        String sid = trim(sessionId);
        String tid = trim(tokenId);
        if (uid.isEmpty() || sid.isEmpty() || tid.isEmpty() || tenantId == 0) {
            throw new TicketInvalidException(
                    "sandbox terminal ticket requires user, tenant, session, and access token");
        }
        return jwtService.generateSandboxTerminalTicket(uid, tenantId, sid, tid, ttl);
    }

    /**
     * 对照 ParseSandboxTerminalTicket（L57-92）：任何失败都归
     * {@link TicketInvalidException}（Go 的 error 原文在 handler 里不可见）。
     */
    public TicketClaims parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new TicketInvalidException("missing terminal ticket");
        }
        Claims claims;
        try {
            claims = jwtService.parseSigned(trimmed);
        } catch (RuntimeException e) {
            log.debug("[sandbox-terminal] ticket parse rejected: {}", e.toString());
            throw new TicketInvalidException("invalid or expired terminal ticket");
        }
        if (!TICKET_TYPE.equals(claims.get("type"))) {
            throw new TicketInvalidException("not a terminal ticket");
        }
        String userId = stringClaim(claims, "user_id");
        String sessionId = stringClaim(claims, "session_id");
        String tokenId = stringClaim(claims, "token_id");
        long tenantId = JwtService.tenantIdFromClaims(claims, 0);
        if (userId.isEmpty() || sessionId.isEmpty() || tokenId.isEmpty() || tenantId == 0) {
            throw new TicketInvalidException("invalid terminal ticket claims");
        }
        return new TicketClaims(userId, tenantId, sessionId, tokenId);
    }

    private static String stringClaim(Claims claims, String name) {
        Object raw = claims.get(name);
        return raw instanceof String s ? s.trim() : "";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 对照 AssertAccessTokenNotRevoked 的行字段比较（owner/type/revoked）。 */
    public static boolean accessTokenNotRevoked(AuthTokenRow token, String userId) {
        if (token == null) {
            return false;
        }
        if (!Objects.equals(trim(token.userId()), trim(userId))) {
            return false;
        }
        if (!"access_token".equals(token.tokenType())) {
            return false;
        }
        return !token.isRevoked();
    }

    /** auth_tokens 行的窄投影（对照 types.AuthToken 的消费面）。 */
    public record AuthTokenRow(String id, String userId, String tokenType,
            boolean isRevoked, java.time.OffsetDateTime expiresAt) {}

    /** 对照 assertAccessTokenNotExpired：ExpiresAt 零值视为不过期。 */
    public static boolean accessTokenNotExpired(AuthTokenRow token,
            java.time.OffsetDateTime now) {
        if (token == null) {
            return false;
        }
        if (token.expiresAt() == null) {
            return true;
        }
        // Go 零值 time.Time 的判据是 IsZero；Java 侧 DB 读不到零值（NOT NULL 列恒有时间），
        // null 分支已兜底；其余按 !After(now) 拒绝。
        return token.expiresAt().toInstant().isAfter(now.toInstant());
    }

    /** 对照 AssertAccessTokenStillActive（铸票检查：未吊销且未过期）。 */
    public static boolean accessTokenStillActive(AuthTokenRow token, String userId,
            java.time.OffsetDateTime now) {
        return accessTokenNotRevoked(token, userId) && accessTokenNotExpired(token, now);
    }
}
