package com.ragagent.tracing.langfuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置解析单测（对照 Go internal/tracing/langfuse/config.go 与 config_test.go）：
 * parseBool 的真值表、ParseDuration 的复合串、Validate 的原文消息。
 */
class LangfuseConfigTest {

    @Test
    @DisplayName("parseBool：Go 真值表（1/true/t/yes/y/on 为真）")
    void parseBoolMatchesGo() {
        for (String truthy : new String[] {"1", "true", "t", "yes", "y", "on", "TRUE", " On "}) {
            assertTrue(LangfuseConfig.parseBool(truthy), truthy);
        }
        for (String falsy : new String[] {"0", "false", "no", "off", "", "nope"}) {
            assertFalse(LangfuseConfig.parseBool(falsy), falsy);
        }
    }

    @Test
    @DisplayName("duration：Go ParseDuration 形态（复合串/小数/非法）")
    void parseGoDurationMatchesGo() {
        assertEquals(3000, LangfuseConfig.parseGoDurationMs("3s"));
        assertEquals(500, LangfuseConfig.parseGoDurationMs("500ms"));
        assertEquals(90_000, LangfuseConfig.parseGoDurationMs("1m30s"));
        assertEquals(1500, LangfuseConfig.parseGoDurationMs("1.5s"));
        assertEquals(3_600_000, LangfuseConfig.parseGoDurationMs("1h"));
        assertEquals(1, LangfuseConfig.parseGoDurationMs("1000us"));
        assertEquals(-1, LangfuseConfig.parseGoDurationMs("abc"));
        assertEquals(-1, LangfuseConfig.parseGoDurationMs("10x"));
        assertEquals(-1, LangfuseConfig.parseGoDurationMs("-3s"));
        assertEquals(-1, LangfuseConfig.parseGoDurationMs(""));
    }

    @Test
    @DisplayName("validate：启用时 host/keys 必填，消息照 Go 原文；停用恒通过")
    void validateMessagesMatchGo() {
        LangfuseConfig noHost = new LangfuseConfig(true, "  ", "pk", "sk", 15, 3000, 2048,
                10_000, "", "", 1.0, false);
        assertEquals("langfuse: host is required when enabled",
                assertThrows(IllegalStateException.class, noHost::validate).getMessage());

        LangfuseConfig noKeys = new LangfuseConfig(true, "http://x", "", "", 15, 3000, 2048,
                10_000, "", "", 1.0, false);
        assertEquals("langfuse: public_key and secret_key are required when enabled",
                assertThrows(IllegalStateException.class, noKeys::validate).getMessage());

        LangfuseConfig disabled = new LangfuseConfig(false, "", "", "", 15, 3000, 2048,
                10_000, "", "", 1.0, false);
        disabled.validate();

        LangfuseConfig ok = new LangfuseConfig(true, "http://x", "pk", "sk", 15, 3000, 2048,
                10_000, "", "", 1.0, false);
        ok.validate();
    }

    @Test
    @DisplayName("默认值：FlushAt=15 / FlushInterval=3s / QueueSize=2048 / RequestTimeout=10s / SampleRate=1")
    void defaultsMatchGo() {
        assertEquals(15, LangfuseConfig.DEFAULT_FLUSH_AT);
        assertEquals(3000, LangfuseConfig.DEFAULT_FLUSH_INTERVAL_MS);
        assertEquals(2048, LangfuseConfig.DEFAULT_QUEUE_SIZE);
        assertEquals(10_000, LangfuseConfig.DEFAULT_REQUEST_TIMEOUT_MS);
        assertEquals("https://cloud.langfuse.com", LangfuseConfig.DEFAULT_HOST);
    }
}
