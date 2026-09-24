package com.ragagent.im.yunzhijia;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 云之家回调签名——对照 Go {@code internal/im/yunzhijia/sign.go} L16-32。
 *
 * <p>签名串 = 以逗号连接 {@code robotId,robotName,operatorOpenid,operatorName,time,msgId,content}
 * （{@code time} 用十进制整数），签名 = {@code Base64(HMAC-SHA1(secret, 签名串))}。</p>
 */
public final class YunzhijiaSign {

    private YunzhijiaSign() {
    }

    public static String computeSignature(String secret, YunzhijiaTypes.CallbackMessage msg) {
        String signatureString = String.join(",",
                nullSafe(msg.robotId),
                nullSafe(msg.robotName),
                nullSafe(msg.operatorOpenid),
                nullSafe(msg.operatorName),
                String.valueOf(msg.time),
                nullSafe(msg.msgId),
                nullSafe(msg.content));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret == null ? "" : secret)
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(signatureString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("compute yunzhijia signature: " + e.getMessage(), e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
