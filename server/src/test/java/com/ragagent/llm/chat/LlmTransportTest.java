package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 对照 Go internal/models/chat/timeout_test.go
 * （TestWithLLMTimeout_* 三个用例 + TestEnvDurationSeconds 的四个子用例）。
 *
 * <p>与 Go 测试的对应差异：Go 的断言是"ctx 上的 deadline 剩余时长"，Java 侧
 * {@link LlmTransport#withLlmTimeout} 直接返回剩余时长；Go 用 {@code t.Setenv} 造环境变量，
 * Java 改环境变量不可移植，故改为直接测等价的纯函数
 * {@link LlmTransport#parseDurationSeconds}（默认值本身仍由环境变量决定）。</p>
 */
class LlmTransportTest {

    /** 对照 Go TestWithLLMTimeout_NoParentDeadline_AppliesDefault */
    @Test
    void noParentDeadlineAppliesDefault() {
        Duration got = LlmTransport.withLlmTimeout(null, Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(50), got);
    }

    /** 对照 Go TestWithLLMTimeout_ShorterParentDeadline_Respected */
    @Test
    void shorterParentDeadlineRespected() {
        Instant parent = Instant.now().plus(Duration.ofMillis(20));
        Duration got = LlmTransport.withLlmTimeout(parent, Duration.ofSeconds(10));
        assertTrue(got.compareTo(Duration.ofMillis(30)) <= 0,
                "parent shorter deadline should be respected, got remaining=" + got);
    }

    /** 对照 Go TestWithLLMTimeout_LongerParentDeadline_NotTruncated */
    @Test
    void longerParentDeadlineNotTruncated() {
        Instant parent = Instant.now().plus(Duration.ofSeconds(10));
        Duration got = LlmTransport.withLlmTimeout(parent, Duration.ofMillis(50));
        assertTrue(got.compareTo(Duration.ofSeconds(5)) >= 0,
                "parent longer deadline should NOT be truncated by default, got remaining=" + got);
    }

    /** 已过期的 deadline 折算成最小正超时（Go 里 ctx 已过期 → 立即失败）。 */
    @Test
    void expiredParentDeadlineYieldsMinimalTimeout() {
        Duration got = LlmTransport.withLlmTimeout(Instant.now().minusSeconds(5), Duration.ofSeconds(300));
        assertTrue(got.isPositive() && got.compareTo(Duration.ofMillis(50)) <= 0, "got " + got);
        assertEquals(Duration.ofMillis(1), got);
    }

    /** 对照 Go TestEnvDurationSeconds/unset returns fallback */
    @Test
    void envDurationSecondsUnsetReturnsFallback() {
        // 用一个几乎不可能被设置的名字，保证走"未设置"分支
        assertEquals(Duration.ofSeconds(7), LlmTransport.parseDurationSeconds(null, Duration.ofSeconds(7)));
        assertEquals(Duration.ofSeconds(7), LlmTransport.parseDurationSeconds("  ", Duration.ofSeconds(7)));
    }

    /** 对照 Go TestEnvDurationSeconds/valid value parsed */
    @Test
    void envDurationSecondsValidValueParsed() {
        assertEquals(Duration.ofSeconds(42), LlmTransport.parseDurationSeconds("42", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(42), LlmTransport.parseDurationSeconds(" 42 ", Duration.ofSeconds(1)));
    }

    /** 对照 Go TestEnvDurationSeconds/invalid falls back */
    @Test
    void envDurationSecondsInvalidFallsBack() {
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("not-a-number", Duration.ofSeconds(9)));
    }

    /** 对照 Go TestEnvDurationSeconds/non-positive falls back */
    @Test
    void envDurationSecondsNonPositiveFallsBack() {
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("0", Duration.ofSeconds(9)));
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("-5", Duration.ofSeconds(9)));
    }

    /**
     * 默认值以 Go <b>代码</b>为准（transport.go:20-21 是 300s/600s，注释里写的
     * 600s/1800s 与代码不符）。本机若设置了环境变量则以环境变量为准，故这里只在
     * 未设置时断言默认值。
     */
    @Test
    void defaultTimeoutsMatchGoCode() {
        if (System.getenv("WEKNORA_LLM_CHAT_TIMEOUT_SECONDS") == null) {
            assertEquals(Duration.ofSeconds(300), LlmTransport.DEFAULT_CHAT_TIMEOUT);
        }
        if (System.getenv("WEKNORA_LLM_STREAM_TIMEOUT_SECONDS") == null) {
            assertEquals(Duration.ofSeconds(600), LlmTransport.DEFAULT_STREAM_TIMEOUT);
        }
    }

    /** 共享客户端是同一实例（对照 Go 的包级 rawHTTPClient）。 */
    @Test
    void sharedClientIsSingleton() {
        assertTrue(LlmTransport.sharedClient() == LlmTransport.sharedClient());
    }
}
