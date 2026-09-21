package com.ragagent.im.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * feishu / wecom 的 AES 加密验签族（确定性核心，波 5 W5γ3 翻译）。
 *
 * <ul>
 *   <li>feishu decrypt（对照 internal/im/feishu/adapter.go L1305-1350）：
 *       AES-256-CBC，key = SHA-256(encrypt_key)，IV = 密文前 16 字节，PKCS#7 校验去除。</li>
 *   <li>wecom verifySignature（webhook_adapter.go L437-450）：
 *       SHA1(sort(token,timestamp,nonce,encrypt) 拼接) hex，常时比较。</li>
 *   <li>wecom decrypt（webhook_adapter.go L452-490）：AES-256-CBC，key =
 *       Base64 解码的 43 字符 EncodingAESKey（32 字节），IV = key 前 16 字节，
 *       PKCS#7；信封 = 16B random + 4B 大端消息长 + 消息 + receiveid。</li>
 * </ul>
 *
 * <p>字节契约：feishu_ciphertext/wecom_ciphertext/wecom_signature 录自独立 Go
 * 程序（contracts/w5g3b-im-crypt.tsv）。</p>
 */
public final class FeishuWecomCrypt {

    private FeishuWecomCrypt() {
    }

    private static final int BLOCK = 16;

    private static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }

    /** PKCS#7 校验去除（两侧 decrypt 的共用尾段；非法 padding 报错）。 */
    private static byte[] stripPkcs7(byte[] data) throws CryptException {
        if (data.length == 0) {
            throw new CryptException("empty plaintext");
        }
        int padLen = data[data.length - 1] & 0xFF;
        if (padLen > BLOCK || padLen == 0 || padLen > data.length) {
            throw new CryptException("invalid padding");
        }
        for (int i = 0; i < padLen; i++) {
            if ((data[data.length - 1 - i] & 0xFF) != padLen) {
                throw new CryptException("invalid padding");
            }
        }
        byte[] out = new byte[data.length - padLen];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /**
     * feishu decrypt：encrypted 为回调 encrypt 字段的 Base64。IV 是密文首 16 字节
     * （所以解密前密文必须 ≥ 2 个块）。
     */
    public static byte[] feishuDecrypt(String encryptKey, String encryptedBase64)
            throws CryptException {
        if (encryptKey == null || encryptKey.isEmpty()) {
            throw new CryptException("encrypt_key not configured");
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encryptedBase64);
        } catch (IllegalArgumentException e) {
            throw new CryptException("base64 decode: " + e.getMessage());
        }
        byte[] keyHash;
        try {
            keyHash = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (ciphertext.length < BLOCK) {
            throw new CryptException("ciphertext too short");
        }
        byte[] iv = new byte[BLOCK];
        System.arraycopy(ciphertext, 0, iv, 0, BLOCK);
        byte[] body = new byte[ciphertext.length - BLOCK];
        System.arraycopy(ciphertext, BLOCK, body, 0, body.length);
        try {
            return stripPkcs7(cbcDecrypt(keyHash, iv, body));
        } catch (CryptException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptException("decrypt failed: " + e.getMessage());
        }
    }

    /**
     * wecom verifySignature：SHA1(sort(token,timestamp,nonce,encrypt) 串接)，
     * hex 小写，常时比较（Go hmac.Equal）。
     */
    public static boolean wecomVerifySignature(String token, String timestamp, String nonce,
            String encrypt, String signature) {
        List<String> parts = new ArrayList<>(List.of(token, timestamp, nonce, encrypt));
        parts.sort(String::compareTo);
        String combined = String.join("", parts);
        String computed = sha1Hex(combined.getBytes(StandardCharsets.UTF_8));
        return constantTimeEquals(computed, signature);
    }

    /**
     * wecom decrypt + 信封拆解：返回消息体（信封 = 16B random + 4B 大端长 +
     * 消息 + receiveid；Go 侧解密后按 [16:20] 长度切消息）。
     */
    public static String wecomDecryptMessage(String aesKeyBase64, String encryptedBase64)
            throws CryptException {
        byte[] aesKey;
        try {
            // 43 字符 EncodingAESKey + "=" 后解码 32 字节（adapter L108 原文）
            aesKey = Base64.getDecoder().decode(aesKeyBase64 + "=");
        } catch (IllegalArgumentException e) {
            throw new CryptException("decode encoding_aes_key: " + e.getMessage());
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encryptedBase64);
        } catch (IllegalArgumentException e) {
            throw new CryptException("base64 decode: " + e.getMessage());
        }
        if (ciphertext.length < BLOCK) {
            throw new CryptException("ciphertext too short");
        }
        if (ciphertext.length % BLOCK != 0) {
            throw new CryptException("ciphertext length is not a multiple of AES block size");
        }
        byte[] iv = new byte[BLOCK];
        System.arraycopy(aesKey, 0, iv, 0, BLOCK);
        byte[] plain;
        try {
            plain = stripPkcs7(cbcDecrypt(aesKey, iv, ciphertext));
        } catch (CryptException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptException("decrypt failed: " + e.getMessage());
        }
        if (plain.length < 20) {
            throw new CryptException("plaintext too short for envelope");
        }
        int msgLen = ((plain[16] & 0xFF) << 24) | ((plain[17] & 0xFF) << 16)
                | ((plain[18] & 0xFF) << 8) | (plain[19] & 0xFF);
        if (msgLen < 0 || 20 + (long) msgLen > plain.length) {
            throw new CryptException("invalid envelope length");
        }
        return new String(plain, 20, msgLen, StandardCharsets.UTF_8);
    }

    private static String sha1Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int r = ab.length ^ bb.length;
        for (int i = 0; i < ab.length && i < bb.length; i++) {
            r |= ab[i] ^ bb[i];
        }
        return r == 0 && ab.length == bb.length;
    }

    /** 解密/验签失败（Go 的 error 返回；message 对照 Go 原文）。 */
    public static final class CryptException extends Exception {
        public CryptException(String message) {
            super(message);
        }
    }
}
