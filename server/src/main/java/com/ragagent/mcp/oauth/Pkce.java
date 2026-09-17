package com.ragagent.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE（RFC 7636）与 state 的生成（对照 mcp-go
 * {@code client/transport/oauth_utils.go} 全文）。
 *
 * <p>逐条对照：
 * <ul>
 *   <li>{@code GenerateRandomString(n)}：{@code n} 字节 CSPRNG →
 *       base64url（无填充）→ 截断到前 {@code n} 个字符；</li>
 *   <li>{@code GenerateCodeVerifier()}：{@code GenerateRandomString(64)}；</li>
 *   <li>{@code GenerateCodeChallenge(v)}：{@code base64url(SHA-256(v))}（无填充）；</li>
 *   <li>{@code GenerateState()}：{@code GenerateRandomString(32)}。</li>
 * </ul>
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {
    }

    /** 对照 {@code GenerateRandomString}。 */
    public static String generateRandomString(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    /** 对照 {@code GenerateCodeVerifier}：RFC 7636 要求 43–128 字符，这里取 64。 */
    public static String generateCodeVerifier() {
        return generateRandomString(64);
    }

    /** 对照 {@code GenerateCodeChallenge}：S256。 */
    public static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然带 SHA-256；到这一步说明运行环境损坏
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 对照 {@code GenerateState}。 */
    public static String generateState() {
        return generateRandomString(32);
    }
}
