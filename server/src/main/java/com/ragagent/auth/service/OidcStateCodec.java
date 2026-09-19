package com.ragagent.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * OIDC state 编解码（对照 Go internal/utils/oidc_state.go，逐字节）。
 *
 * state = base64url_nopad(json).base64url_nopad(hmac-sha256)，
 * json 字段序 nonce, redirect_uri(omitempty), iat；密钥 = env JWT_SECRET
 * （trim 后非空），否则随机 32B base64(Std)（对照 sync.Once：构造时一次定案）。
 *
 * verify：必须恰好 2 段、HMAC 相等、redirect_uri trim 后非空、iat != 0、
 * now-iat ≤ 10min 且 iat-now ≤ 1min。所有失败抛 {@link StateException}，
 * 由 handler 层坍缩成 invalid_state 302（错误消息不外泄）。
 *
     * sign 的 JSON 序列化复刻 Go encoding/json 默认 HTML 转义（& < > 与控制字符都转 hex 形式），
     * 以保证与 Go 侧 SignOIDCState 跨语言互验。
 */
@Component
public class OidcStateCodec {

    /** 对照 oidcStateMaxAge = 10 * time.Minute */
    private static final long MAX_AGE_SECONDS = 600;
    /** 对照 time.Until(issuedAt) > time.Minute（未来容忍 1 分钟） */
    private static final long FUTURE_TOLERANCE_SECONDS = 60;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] signingKey;

    public OidcStateCodec() {
        String env = System.getenv("JWT_SECRET");
        String secret;
        if (env != null && !env.trim().isEmpty()) {
            secret = env.trim();
        } else {
            byte[] randomBytes = new byte[32];
            RANDOM.nextBytes(randomBytes);
            secret = Base64.getEncoder().encodeToString(randomBytes);
        }
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 对照 OIDCStatePayload */
    public record Payload(String nonce, String redirectUri, long issuedAt) {
    }

    /** verify 失败（消息对照 Go error 原文，仅供日志，不外泄） */
    public static class StateException extends RuntimeException {
        public StateException(String message) {
            super(message);
        }
    }

    /**
     * 对照 SignOIDCState。iat 传 0 = time.Now().Unix()。
     * public 供契约测试签 state（测试需与录制脚本 python 锻造等价的入口）。
     */
    public String sign(String nonce, String redirectUri, long issuedAt) {
        if (nonce == null || UserService.goTrimSpace(nonce).isEmpty()) {
            throw new IllegalArgumentException("oidc state nonce is required");
        }
        if (redirectUri == null || UserService.goTrimSpace(redirectUri).isEmpty()) {
            throw new IllegalArgumentException("oidc state redirect_uri is required");
        }
        long iat = issuedAt == 0 ? Instant.now().getEpochSecond() : issuedAt;
        // 字段序 nonce, redirect_uri, iat（Go struct 声明序；redirect_uri 必填故 omitempty 恒输出）
        String raw = "{\"nonce\":" + goJsonString(nonce)
                + ",\"redirect_uri\":" + goJsonString(redirectUri)
                + ",\"iat\":" + iat + "}";
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        return base64Url(rawBytes) + "." + base64Url(hmac(rawBytes));
    }

    /** 对照 VerifyOIDCState */
    public Payload verify(String rawState) {
        String raw = rawState == null ? "" : UserService.goTrimSpace(rawState);
        String[] parts = raw.split("\\.", -1); // 对照 strings.Split（全切）
        if (parts.length != 2) {
            throw new StateException("invalid oidc state format");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new StateException("decode oidc state payload: " + e.getMessage());
        }
        byte[] sigBytes;
        try {
            sigBytes = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new StateException("decode oidc state signature: " + e.getMessage());
        }
        if (!MessageDigest.isEqual(hmac(payloadBytes), sigBytes)) {
            throw new StateException("oidc state signature mismatch");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadBytes);
        } catch (Exception e) {
            throw new StateException("unmarshal oidc state: " + e.getMessage());
        }
        String nonce = node.path("nonce").isTextual() ? node.path("nonce").asText() : "";
        String redirectUri = node.path("redirect_uri").isTextual() ? node.path("redirect_uri").asText() : "";
        long iat = node.path("iat").isIntegralNumber() ? node.path("iat").asLong() : 0;
        if (UserService.goTrimSpace(redirectUri).isEmpty()) {
            throw new StateException("state.redirect_uri is required");
        }
        if (iat == 0) {
            throw new StateException("state.iat is required");
        }
        long now = Instant.now().getEpochSecond();
        if (now - iat > MAX_AGE_SECONDS || iat - now > FUTURE_TOLERANCE_SECONDS) {
            throw new StateException("oidc state expired or invalid timestamp");
        }
        return new Payload(nonce, redirectUri, iat);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("hmac-sha256 unavailable", e);
        }
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Go encoding/json 的字符串字面量序列化（默认 HTML 转义开启）：
     * 双引号/反斜杠与 0x20 以下控制字符转义（LF CR TAB 有短形式，其余为四位小写 hex 形式），
     * 另 & < > 与 U+2028/U+2029 也转义为各自的 hex 形式。
     */
    static String goJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '<' -> sb.append("\\u003c");
                case '>' -> sb.append("\\u003e");
                case '&' -> sb.append("\\u0026");
                case '\u2028' -> sb.append("\\u2028");
                case '\u2029' -> sb.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
