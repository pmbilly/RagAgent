package com.ragagent.embed;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ragagent.embed.domain.EmbedChannelEntity;

/**
 * embed 令牌族（对照 Go internal/application/service/embed_channel.go 的
 * generateEmbedPublishToken 与 internal/application/service/embed_session.go 全文）。
 *
 * <ul>
 *   <li>publish token：{@code em_} + 32 字节 base64url（无填充）；</li>
 *   <li>session token：{@code ems_} + 32 字节 base64url（无填充），Redis 侧键
 *       {@code embed:session:<token>}（30 分钟 TTL，跨语言键空间契约）；</li>
 *   <li>会话签名：HMAC-SHA256(publish_token, "cid|sid") base64url 无填充
 *       ——{@code SignEmbedSessionHandle} 逐式复刻，A/B 录制脚本里的 python 版与之同式。</li>
 * </ul>
 */
public final class EmbedTokens {

    public static final String SESSION_TOKEN_PREFIX = "ems_";
    public static final String SESSION_REDIS_PREFIX = "embed:session:";
    public static final int SESSION_TTL_SECONDS = 30 * 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private EmbedTokens() {}

    /** 对照 generateEmbedPublishToken。 */
    public static String generatePublishToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return "em_" + base64Url(buf);
    }

    /** 对照 generateEmbedSessionToken。 */
    public static String generateSessionToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return SESSION_TOKEN_PREFIX + base64Url(buf);
    }

    /** 对照 IsEmbedSessionToken。 */
    public static boolean isSessionToken(String token) {
        return token != null && token.trim().startsWith(SESSION_TOKEN_PREFIX);
    }

    /** 对照 SignEmbedSessionHandle。 */
    public static String signHandle(EmbedChannelEntity ch, String sessionId) {
        if (ch == null || sessionId == null || sessionId.trim().isEmpty()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ch.getPublishToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((ch.getId() + "|" + sessionId).getBytes(StandardCharsets.UTF_8));
            return base64Url(out);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 对照 VerifyEmbedSessionHandle（trim + 常量时间比较）。 */
    public static boolean verifyHandle(EmbedChannelEntity ch, String sessionId, String sig) {
        if (sig == null) {
            return false;
        }
        String trimmed = sig.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String expected = signHandle(ch, sessionId);
        if (expected.isEmpty()) {
            return false;
        }
        return constantTimeEquals(expected, trimmed);
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
