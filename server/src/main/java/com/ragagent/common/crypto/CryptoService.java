package com.ragagent.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 对照 Go internal/utils/crypto.go：AES-256-GCM 加密 + enc:v1: 前缀。
 *
 * - GetAESKey：env SYSTEM_AES_KEY，必须恰好 32 字节，否则视为未配置（null）
 * - encrypt：plaintext 为空 / 已带前缀 / key 为 null → 原样返回；
 *   输出 enc:v1: + base64.RawURLEncoding(nonce || ciphertext)
 * - decryptStored*：无前缀 = 历史明文原样返回；带前缀解密失败 →
 *   strict 抛异常 / lenient 返回 ("", false)（行级加载不拖垮整个列表，对照 Scan 语义）
 */
@Component
public class CryptoService {

    public static final String ENC_PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 密钥原始值（{@code SYSTEM_AES_KEY}）——**启动期快照**（B6 批 6）。
     *
     * <p>本类在 MyBatis 类型处理器等处是手工 {@code new} 出来的（不经 Spring 注入），
     * 故值由 {@code config.RuntimeSnapshotWiring} 启动期写入一次；<b>只允许装配层调用 install</b>。</p>
     */
    private static volatile String configuredAesKey = "";

    /** 启动期安装密钥原始值（{@code null} → 空串，即不可用）。 */
    public static void installAesKey(String raw) {
        configuredAesKey = raw == null ? "" : raw;
    }

    /** 对照 GetAESKey：非 32 字节返回 null */
    public byte[] getAESKey() {
        String key = configuredAesKey;
        if (key != null && key.getBytes(StandardCharsets.UTF_8).length == 32) {
            return key.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /** 对照 EncryptAESGCM（value 语义：不改传入对象，内部拷贝） */
    public String encryptAESGCM(String plaintext, byte[] key) {
        if (plaintext == null || plaintext.isEmpty() || key == null) {
            return plaintext;
        }
        if (plaintext.startsWith(ENC_PREFIX)) {
            return plaintext;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return ENC_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** 对照 DecryptAESGCM：无前缀按明文原样返回 */
    public String decryptAESGCM(String encrypted, byte[] key) {
        if (encrypted == null || encrypted.isEmpty() || key == null) {
            return encrypted;
        }
        if (!encrypted.startsWith(ENC_PREFIX)) {
            return encrypted;
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(encrypted.substring(ENC_PREFIX.length()));
            if (data.length < 12) {
                throw new IllegalArgumentException("invalid encrypted data: too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(GCM_TAG_BITS, data, 0, 12));
            byte[] plaintext = cipher.doFinal(data, 12, data.length - 12);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    /**
     * 对照 DecryptStoredSecret：严格模式。
     * 带前缀但 key 缺失 → IllegalStateException（ErrEncryptedDataMissingKey 语义）；
     * 解密失败（密钥轮换/密文损坏）→ 异常原样上抛，调用方决定 loud fail。
     */
    public String decryptStoredSecret(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        if (!encrypted.startsWith(ENC_PREFIX)) {
            return encrypted;
        }
        byte[] key = getAESKey();
        if (key == null) {
            throw new IllegalStateException(
                    "encrypted data found but SYSTEM_AES_KEY is not set or has wrong length");
        }
        return decryptAESGCM(encrypted, key);
    }

    /** 对照 DecryptStoredSecretLenient：行级加载路径，失败返回 ("", false) */
    public LenientResult decryptStoredSecretLenient(String encrypted) {
        try {
            return new LenientResult(decryptStoredSecret(encrypted), true);
        } catch (RuntimeException e) {
            return new LenientResult("", false);
        }
    }

    public record LenientResult(String plaintext, boolean ok) {}
}
