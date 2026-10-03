package com.ragagent.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * {@link LlmTransport#withLlmTimeout}（三个用例）与
 * {@link LlmTransport#parseDurationSeconds}（四个子用例）的超时语义。
 *
 * <p>withLlmTimeout 返回父 deadline 的剩余时长；测试不直接改环境变量（跨环境不可靠），
 * 改为直接测等价的纯函数 {@link LlmTransport#parseDurationSeconds}
 * （默认值本身仍由环境变量决定）。</p>
 */
class LlmTransportTest {

    @Test
    void noParentDeadlineAppliesDefault() {
        Duration got = LlmTransport.withLlmTimeout(null, Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(50), got);
    }

    @Test
    void shorterParentDeadlineRespected() {
        Instant parent = Instant.now().plus(Duration.ofMillis(20));
        Duration got = LlmTransport.withLlmTimeout(parent, Duration.ofSeconds(10));
        assertTrue(got.compareTo(Duration.ofMillis(30)) <= 0,
                "parent shorter deadline should be respected, got remaining=" + got);
    }

    @Test
    void longerParentDeadlineNotTruncated() {
        Instant parent = Instant.now().plus(Duration.ofSeconds(10));
        Duration got = LlmTransport.withLlmTimeout(parent, Duration.ofMillis(50));
        assertTrue(got.compareTo(Duration.ofSeconds(5)) >= 0,
                "parent longer deadline should NOT be truncated by default, got remaining=" + got);
    }

    /** 已过期的 deadline 折算成最小正超时（1 毫秒）。 */
    @Test
    void expiredParentDeadlineYieldsMinimalTimeout() {
        Duration got = LlmTransport.withLlmTimeout(Instant.now().minusSeconds(5), Duration.ofSeconds(300));
        assertTrue(got.isPositive() && got.compareTo(Duration.ofMillis(50)) <= 0, "got " + got);
        assertEquals(Duration.ofMillis(1), got);
    }

    @Test
    void envDurationSecondsUnsetReturnsFallback() {
        // 用一个几乎不可能被设置的名字，保证走"未设置"分支
        assertEquals(Duration.ofSeconds(7), LlmTransport.parseDurationSeconds(null, Duration.ofSeconds(7)));
        assertEquals(Duration.ofSeconds(7), LlmTransport.parseDurationSeconds("  ", Duration.ofSeconds(7)));
    }

    @Test
    void envDurationSecondsValidValueParsed() {
        assertEquals(Duration.ofSeconds(42), LlmTransport.parseDurationSeconds("42", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(42), LlmTransport.parseDurationSeconds(" 42 ", Duration.ofSeconds(1)));
    }

    @Test
    void envDurationSecondsInvalidFallsBack() {
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("not-a-number", Duration.ofSeconds(9)));
    }

    @Test
    void envDurationSecondsNonPositiveFallsBack() {
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("0", Duration.ofSeconds(9)));
        assertEquals(Duration.ofSeconds(9), LlmTransport.parseDurationSeconds("-5", Duration.ofSeconds(9)));
    }

    /**
     * 默认值是代码常量（300s/600s）。本机若设置了环境变量则以环境变量为准，
     * 故这里只在未设置时断言默认值。
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

    /** 共享客户端是同一实例（单例）。 */
    @Test
    void sharedClientIsSingleton() {
        assertTrue(LlmTransport.sharedClient() == LlmTransport.sharedClient());
    }
}
