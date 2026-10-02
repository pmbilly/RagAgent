package com.ragagent.auth.service;

import java.nio.charset.StandardCharsets;
import com.ragagent.auth.config.JwtProperties;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.ragagent.auth.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与解析（对照 Go internal/application/service/user.go 的 JWT 部分）。
 *
 * 契约（逐条对照 Go）：
 * - 签名：HS256；secret 取 env JWT_SECRET（trim 后非空），否则随机 32 字节 base64(Std)，进程内一次生成
 *   （对照 getJwtSecret 的 sync.Once 语义）
 * - access claims：user_id, email, tenant_id, exp(+24h 秒), iat, type="access"
 * - refresh claims：user_id, exp(+7d 秒), iat, type="refresh"（无 email/tenant_id）
 *
 * 已知差异（记录于约定 §8）：Go 对短 secret（&lt;32 字节）不报错；jjwt 对 HS256 强制 key ≥ 256bit，
 * SecretKeySpec 构造不校验，但 parse 时 WeakKeyException。dev 无 JWT_SECRET 时走随机 32 字节，无影响。
 */
@Component
public class JwtService {

        private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.key = new SecretKeySpec(
                getJwtSecret(properties.secret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 对照 getJwtSecret()：{@code JWT_SECRET}（属性绑定）→ 未配置随机 32B base64 */
    private static String getJwtSecret(String env) {
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        return Base64.getEncoder().encodeToString(randomBytes);
    }

    /** access token：24h 有效，tenant_id 编码活跃空间 */
    public String generateAccessToken(User user, long activeTenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("user_id", user.getId())
                .claim("email", user.getEmail())
                .claim("tenant_id", activeTenantId)
                .expiration(Date.from(now.plus(24, ChronoUnit.HOURS)))
                .issuedAt(Date.from(now))
                .claim("type", "access")
                .signWith(key)
                .compact();
    }

    /**
     * 对照 IssueSandboxTerminalTicket（service/sandbox_terminal_ticket.go L33-53）：
     * 沙箱终端 WS 握手票据。不是访问令牌——ValidateToken 拒绝它
     * （{@link #isSandboxTerminalTicketClaims}）；绑定 user/tenant/session/token 四元组，
     * TTL 缺省 2 分钟（DefaultSandboxTerminalTicketTTL）。W5d 批新增。
     */
    public String generateSandboxTerminalTicket(String userId, long tenantId,
            String sessionId, String tokenId, java.time.Duration ttl) {
        Instant now = Instant.now();
        java.time.Duration effective = ttl == null || ttl.isNegative() || ttl.isZero()
                ? java.time.Duration.ofMinutes(2) : ttl;
        return Jwts.builder()
                .claim("user_id", userId)
                .claim("tenant_id", tenantId)
                .claim("session_id", sessionId)
                .claim("token_id", tokenId)
                .claim("type", "sandbox_terminal")
                .expiration(Date.from(now.plus(effective)))
                .issuedAt(Date.from(now))
                .signWith(key)
                .compact();
    }

    /** refresh token：7d 有效，不含 email/tenant_id */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("user_id", user.getId())
                .expiration(Date.from(now.plus(7, ChronoUnit.DAYS)))
                .issuedAt(Date.from(now))
                .claim("type", "refresh")
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验签名（对照 jwt.Parse + HMAC 方法校验）。
     * 仅校验签名与 exp；业务语义校验（revocation/类型）由 UserService.validateToken 负责，
     * 与 Go 分层一致（middleware 调 UserService.ValidateToken）。
     *
     * @throws TokenValidationException 签名无效/过期/非 HS256
     */
    public Claims parseSigned(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (SignatureException | io.jsonwebtoken.security.WeakKeyException e) {
            throw new TokenValidationException("invalid token", e);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new TokenValidationException("invalid token", e);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            // MalformedJwtException / PrematureJwtException / 空白串等
            throw new TokenValidationException("invalid token", e);
        }
    }

    /** 对照 tenantIDFromClaims：claim 缺失/非数字/≤0 → fallback */
    public static long tenantIdFromClaims(Claims claims, long fallback) {
        Object raw = claims.get("tenant_id");
        if (raw instanceof Number n) {
            long v = n.longValue();
            return v > 0 ? v : fallback;
        }
        return fallback;
    }

    /** 对照 isRefreshTokenClaims */
    public static boolean isRefreshTokenClaims(Claims claims) {
        return "refresh".equals(claims.get("type"));
    }

    /** 对照 isSandboxTerminalTicketClaims（sandboxTerminalTicketType = "sandbox_terminal"） */
    public static boolean isSandboxTerminalTicketClaims(Claims claims) {
        return "sandbox_terminal".equals(claims.get("type"));
    }

    /**
     * 对照 {@code jwt.Parse(..., jwt.WithoutClaimsValidation())}（user.go L1318-1326，
     * Logout 的 userIDFromSignedToken 用）：签名与算法必须校验，但 **claims 不校验**——
     * 过期的 token 也允许登出（Go 注释原文："expired tokens are allowed so logout
     * still works after the access token TTL"）。jjwt 的签名校验先于 exp 检查，
     * 因此 {@link io.jsonwebtoken.ExpiredJwtException} 抛出时签名已验证通过，
     * 从异常里取回 claims 即等价。
     *
     * @throws TokenValidationException 签名无效/非 HS256（与 Go 同样拒绝）
     */
    public Claims parseSignedAllowExpired(String token) {
        try {
            return parseSigned(token);
        } catch (TokenValidationException e) {
            if (e.getCause() instanceof io.jsonwebtoken.ExpiredJwtException expired) {
                return expired.getClaims();
            }
            throw e;
        }
    }
}
