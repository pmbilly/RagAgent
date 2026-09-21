package com.ragagent.im.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 平台验签核心的字节契约：期望签名由独立 Go 程序录制（算法逐字抄自 slack SDK
 * SecretsVerifier 与 dingtalk/adapter.go），fixture 在
 * {@code contracts/w5g3-im-adapter-signatures.tsv}。telegram/mattermost/qqbot
 * 的比较语义一并钉住。
 */
class ImAdapterVerifyTest {

    private static Map<String, String> fx() {
        try (var in = ImAdapterVerifyTest.class
                .getResourceAsStream("/contracts/w5g3-im-adapter-signatures.tsv")) {
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
    void slackSignature() {
        Map<String, String> fx = fx();
        String secret = fx.get("secret");
        String timestamp = "1700000000";
        byte[] body = fx.get("body").getBytes(StandardCharsets.UTF_8);
        assertEquals(fx.get("slack_sig"),
                ImAdapterVerify.slackExpectedSignature(secret, timestamp, body));
        // 附加一个非 ASCII 字节的用例：签名随输入敏感变化
        assertFalse(ImAdapterVerify.slackExpectedSignature(secret, "1700000001", body)
                .equals(fx.get("slack_sig")));
    }

    @Test
    void dingtalkSignature() {
        Map<String, String> fx = fx();
        assertEquals(fx.get("dingtalk_sig"),
                ImAdapterVerify.dingtalkExpectedSignature(fx.get("secret"), fx.get("dingtalk_ts")));
    }

    @Test
    void telegramConstantTimeCompare() {
        // secret 空 → 免验（Go 原文）
        assertTrue(ImAdapterVerify.telegramTokenMatches("", ""));
        assertTrue(ImAdapterVerify.telegramTokenMatches("anything", ""));
        assertTrue(ImAdapterVerify.telegramTokenMatches("w5g3-telegram-secret",
                "w5g3-telegram-secret"));
        assertFalse(ImAdapterVerify.telegramTokenMatches("wrong", "w5g3-telegram-secret"));
    }

    @Test
    void mattermostTokenEquality() {
        assertTrue(ImAdapterVerify.mattermostTokenMatches("tok", "tok"));
        assertFalse(ImAdapterVerify.mattermostTokenMatches("wrong", "tok"));
        // outgoing_token 未配置（空）→ 免验（mattermost/adapter.go L79：token != "" 才比）
        assertTrue(ImAdapterVerify.mattermostTokenMatches("", ""));
        assertTrue(ImAdapterVerify.mattermostTokenMatches("any", ""));
    }
}
