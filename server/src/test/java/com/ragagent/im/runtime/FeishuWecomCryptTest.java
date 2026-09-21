package com.ragagent.im.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * feishu/wecom AES 加密验签族的字节契约。密文/签名录自独立 Go 程序
 * （/tmp/w5g3b，算法逐字抄自 feishu/adapter.go decrypt L1305-1350 与
 * wecom/webhook_adapter.go verifySignature L437-450 + decrypt L452-490），
 * fixture 在 {@code contracts/w5g3b-im-crypt.tsv}。
 */
class FeishuWecomCryptTest {

    private static Map<String, String> fx() {
        try (var in = FeishuWecomCryptTest.class
                .getResourceAsStream("/contracts/w5g3b-im-crypt.tsv")) {
            Map<String, String> map = new HashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .split("\n", -1)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void feishuDecryptRoundTrip() throws Exception {
        Map<String, String> fx = fx();
        byte[] plain = FeishuWecomCrypt.feishuDecrypt(
                fx.get("feishu_encrypt_key"), fx.get("feishu_ciphertext"));
        assertEquals(fx.get("feishu_plaintext"), new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void feishuDecryptFailures() throws Exception {
        Map<String, String> fx = fx();
        // encrypt_key 未配置
        FeishuWecomCrypt.CryptException e = assertThrows(FeishuWecomCrypt.CryptException.class,
                () -> FeishuWecomCrypt.feishuDecrypt("", fx.get("feishu_ciphertext")));
        assertEquals("encrypt_key not configured", e.getMessage());
        // 错误的 key → padding 校验失败（或块长不足）
        assertThrows(FeishuWecomCrypt.CryptException.class,
                () -> FeishuWecomCrypt.feishuDecrypt("wrong-key", fx.get("feishu_ciphertext")));
        // 密文过短
        assertThrows(FeishuWecomCrypt.CryptException.class,
                () -> FeishuWecomCrypt.feishuDecrypt(fx.get("feishu_encrypt_key"), "d293"));
    }

    @Test
    void wecomSignature() throws Exception {
        Map<String, String> fx = fx();
        assertTrue(FeishuWecomCrypt.wecomVerifySignature(fx.get("wecom_token"),
                fx.get("wecom_timestamp"), fx.get("wecom_nonce"),
                fx.get("wecom_ciphertext"), fx.get("wecom_signature")));
        assertFalse(FeishuWecomCrypt.wecomVerifySignature(fx.get("wecom_token"),
                fx.get("wecom_timestamp"), "wrong-nonce",
                fx.get("wecom_ciphertext"), fx.get("wecom_signature")));
    }

    @Test
    void wecomDecryptEnvelope() throws Exception {
        Map<String, String> fx = fx();
        String msg = FeishuWecomCrypt.wecomDecryptMessage(
                fx.get("wecom_aeskey_b64"), fx.get("wecom_ciphertext"));
        assertEquals(fx.get("wecom_plaintext"), msg);
    }

    @Test
    void wecomDecryptFailures() throws Exception {
        Map<String, String> fx = fx();
        // key 长度不是 32 字节
        assertThrows(FeishuWecomCrypt.CryptException.class,
                () -> FeishuWecomCrypt.wecomDecryptMessage(
                        java.util.Base64.getEncoder().encodeToString(new byte[16]),
                        fx.get("wecom_ciphertext")));
        // 密文不是块长的整数倍
        assertThrows(FeishuWecomCrypt.CryptException.class,
                () -> FeishuWecomCrypt.wecomDecryptMessage(fx.get("wecom_aeskey_b64"), "d293"));
    }
}
