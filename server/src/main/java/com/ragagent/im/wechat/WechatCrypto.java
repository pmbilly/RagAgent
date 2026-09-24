package com.ragagent.im.wechat;

import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信 iLink 机器人的媒体加解密（对照 Go {@code internal/im/wechat/crypto.go} L12-72 与
 * {@code adapter.go} 的 {@code parseAESKey}/{@code isHex}/{@code hexDecode}，L217-280）。
 *
 * <p>算法是 <b>AES-128-ECB</b>（无 IV、无链式）。解密后的 PKCS#7 填充<b>只在校验通过时才去</b>
 * ——照 Go：末字节 padLen ∈ (0,16] 且尾部 padLen 个字节都等于 padLen 才裁掉，
 * 否则原样返回（iLink 有些媒体其实是免填充的）。</p>
 *
 * <p>{@link #parseAesKey} 覆盖 iLink 出现的三种密钥形态（照 Go 的注释）：
 * ① base64(16 字节裸密钥)——{@code CDNMedia.aes_key}；
 * ② base64(32 字符 hex 串)——{@code CDNMedia.aes_key} 的另一种；
 * ③ 裸 32 字符 hex 串——{@code ImageItem.aeskey}（<b>不</b> base64 编码）；
 * 兜底：任意偶数长度 hex 串。最终一律 16 字节。</p>
 */
public final class WechatCrypto {

    private WechatCrypto() {
    }

    /** 对照 {@code decryptAES128ECB}：ECB 解密 + 条件去填充。 */
    public static byte[] decryptAes128Ecb(byte[] ciphertext, byte[] key) throws Exception {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)) {
            throw new IllegalArgumentException("invalid aes key length: "
                    + (key == null ? "null" : key.length));
        }
        int blockSize = 16;
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length % blockSize != 0) {
            throw new IllegalArgumentException("ciphertext length "
                    + (ciphertext == null ? 0 : ciphertext.length)
                    + " is not a multiple of block size " + blockSize);
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] plain = cipher.doFinal(ciphertext);

        // 条件去 PKCS#7（照 Go：校验通过才裁）
        if (plain.length > 0) {
            int padLen = plain[plain.length - 1] & 0xFF;
            if (padLen > 0 && padLen <= blockSize && padLen <= plain.length) {
                boolean valid = true;
                for (int i = 0; i < padLen; i++) {
                    if ((plain[plain.length - 1 - i] & 0xFF) != padLen) {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    byte[] trimmed = new byte[plain.length - padLen];
                    System.arraycopy(plain, 0, trimmed, 0, trimmed.length);
                    return trimmed;
                }
            }
        }
        return plain;
    }

    /** 对照 {@code encryptAES128ECB}：PKCS#7 补齐后 ECB 加密（上传媒体用）。 */
    public static byte[] encryptAes128Ecb(byte[] plaintext, byte[] key) throws Exception {
        int blockSize = 16;
        byte[] data = plaintext == null ? new byte[0] : plaintext;
        int padLen = blockSize - (data.length % blockSize);
        byte[] padded = new byte[data.length + padLen];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = (byte) padLen;
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(padded);
    }

    /** 对照 {@code parseAESKey}：三形态 + 兜底，一律 16 字节。 */
    public static byte[] parseAesKey(String aesKeyStr) {
        if (aesKeyStr == null || aesKeyStr.isEmpty()) {
            throw new IllegalArgumentException("empty aes key");
        }
        // 形态③：裸 32 字符 hex
        if (aesKeyStr.length() == 32 && isHex(aesKeyStr)) {
            return hexDecode(aesKeyStr);
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(aesKeyStr);
        } catch (IllegalArgumentException e) {
            try {
                decoded = Base64.getDecoder().decode(padBase64(aesKeyStr));
            } catch (IllegalArgumentException e2) {
                if (isHex(aesKeyStr) && aesKeyStr.length() % 2 == 0) {
                    return hexDecode(aesKeyStr);
                }
                throw new IllegalArgumentException("cannot decode aes key (len="
                        + aesKeyStr.length() + ")", e2);
            }
        }
        if (decoded.length == 16) {
            return decoded;
        }
        String asText = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
        if (decoded.length == 32 && isHex(asText)) {
            return hexDecode(asText);
        }
        throw new IllegalArgumentException("aes key decoded to " + decoded.length
                + " bytes (expected 16 raw or 32 hex), input len=" + aesKeyStr.length());
    }

    /** Go 的 {@code base64.RawStdEncoding}（无填充）对应 Java 的解码补齐。 */
    private static String padBase64(String value) {
        int mod = value.length() % 4;
        if (mod == 0) {
            return value;
        }
        return value + "=".repeat(4 - mod);
    }

    /** 对照 {@code isHex}：非空且全是 hex 字符。 */
    public static boolean isHex(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 对照 {@code hexDecode}：奇数长度报错。 */
    public static byte[] hexDecode(String value) {
        if (value == null || value.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length hex string: "
                    + (value == null ? 0 : value.length()));
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
