package com.ragagent.im.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 平台验签核心（对照 Go internal/im/{slack,dingtalk,telegram,mattermost,qqbot}
 * 各 adapter.go 的 VerifyCallback，波 5 W5γ3 逐行翻译——确定性面；平台出站发送
 * 属 provider-XDEP）。
 *
 * <p>字节契约：slack/dingtalk 签名向量录自独立 Go 程序（算法从 slack SDK
 * SecretsVerifier 与 dingtalk adapter 逐字抄录），fixture 在
 * {@code contracts/w5g3-im-adapter-signatures.tsv}。</p>
 */
public final class ImAdapterVerify {

    private ImAdapterVerify() {
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Slack 验签（对照 slack.NewSecretsVerifier + Ensure）：基串
     * {@code v0:<timestamp>:<body>}，HMAC-SHA256(signingSecret)，与
     * {@code X-Slack-Signature}（"v0=" + hex）比较。secret 空 → 免验（Go 原文）。
     */
    public static String slackExpectedSignature(String signingSecret, String timestamp,
            byte[] body) {
        String base = "v0:" + timestamp + ":";
        byte[] full = new byte[base.length() + body.length];
        System.arraycopy(base.getBytes(StandardCharsets.UTF_8), 0, full, 0, base.length());
        System.arraycopy(body, 0, full, base.length(), body.length);
        return "v0=" + hex(hmacSha256(signingSecret.getBytes(StandardCharsets.UTF_8), full));
    }

    /**
     * DingTalk 验签（对照 dingtalk/adapter.go）：基串 {@code <timestamp>\n<secret>}，
     * HMAC-SHA256 密钥=secret，Base64 输出。时间窗 ±1h 由调用方校验。
     */
    public static String dingtalkExpectedSignature(String clientSecret, String timestamp) {
        String stringToSign = timestamp + "\n" + clientSecret;
        return Base64.getEncoder().encodeToString(
                hmacSha256(clientSecret.getBytes(StandardCharsets.UTF_8),
                        stringToSign.getBytes(StandardCharsets.UTF_8)));
    }

    /** Telegram：constant-time 比较语义（Go subtle.ConstantTimeCompare != 1 → fail）。 */
    public static boolean telegramTokenMatches(String headerToken, String secretToken) {
        if (secretToken == null || secretToken.isEmpty()) {
            return true; // Go：secret 为空免验
        }
        return constantTimeEquals(headerToken == null ? "" : headerToken, secretToken);
    }

    /** 对照 subtle.ConstantTimeCompare 的常时比较。 */
    static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int r = ab.length ^ bb.length;
        for (int i = 0; i < ab.length && i < bb.length; i++) {
            r |= ab[i] ^ bb[i];
        }
        return r == 0 && ab.length == bb.length;
    }

    /** Mattermost：outgoing token 精确相等（payload.Token != a.outgoingToken → fail）。 */
    public static boolean mattermostTokenMatches(String payloadToken, String outgoingToken) {
        return outgoingToken != null && !outgoingToken.isEmpty()
                ? outgoingToken.equals(payloadToken)
                : true;
    }
}
