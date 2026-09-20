package com.ragagent.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 压缩设置的 Go 实录断言（/tmp/wave42rec：settings.go 内嵌；
 * 场景 = compaction_test.go 的 TestKeepRecentIsScaledDownOnSmallWindows /
 * TestThresholdAndShouldCompact + 预算公式边界）。
 */
class CompactionSettingsTest {

    @Test
    void keepRecentIsScaledDownOnSmallWindows() {
        // 20k 窗口装不下 20k 默认值还要留出余量
        CompactionSettings small = new CompactionSettings(false, 20000, 8000, 0, 0).normalize();
        assertThat(small.keepRecentTokens()).isEqualTo(3000); // 可用窗口的四分之一

        // 大窗口保持默认
        CompactionSettings big = new CompactionSettings(false, 200000, 16384, 0, 0).normalize();
        assertThat(big.keepRecentTokens()).isEqualTo(CompactionSettings.DEFAULT_KEEP_RECENT_TOKENS);
        assertThat(big.reserveTokens()).isEqualTo(16384);
    }

    @Test
    void thresholdAndShouldCompact() {
        CompactionSettings s = new CompactionSettings(true, 128000, 28672, 0, 0).normalize();
        assertThat(s.threshold()).isEqualTo(128000 - 28672);
        assertThat(s.shouldCompact(s.threshold())).isFalse();
        assertThat(s.shouldCompact(s.threshold() + 1)).isTrue();

        // 没有窗口就没有可执行的预算
        assertThat(new CompactionSettings(true, 0, 0, 0, 0).normalize().threshold()).isZero();
        assertThat(new CompactionSettings(true, 0, 0, 0, 0).normalize().shouldCompact(1_000_000)).isFalse();

        // 关闭就永不触发，无论多大
        assertThat(new CompactionSettings(false, 1000, 100, 0, 0).normalize().shouldCompact(1_000_000)).isFalse();
        // 录音 st_disabled_normalize：reserve 保留 100，keepRecent 收到 225
        assertThat(new CompactionSettings(false, 1000, 100, 0, 0).normalize().keepRecentTokens()).isEqualTo(225);
    }

    @Test
    void summaryBudgets() {
        CompactionSettings ts = new CompactionSettings(true, 40000, 8000, 2000, 0).normalize();
        assertThat(ts.keepRecentTokens()).isEqualTo(2000);
        assertThat(ts.threshold()).isEqualTo(32000);
        assertThat(ts.summaryBudget()).isEqualTo(2000);
        assertThat(ts.turnPrefixBudget()).isEqualTo(1000);

        // 全零：16384*4/5=13107；13107/2=6553
        CompactionSettings zero = new CompactionSettings(false, 0, 0, 0, 0).normalize();
        assertThat(zero.summaryBudget()).isEqualTo(13107);
        assertThat(zero.turnPrefixBudget()).isEqualTo(6553);

        // MaxSummaryTokens 钳制（reserve 10000 → 8000；max 5000 < 8000 生效）
        CompactionSettings capped = new CompactionSettings(false, 0, 10000, 0, 5000).normalize();
        assertThat(capped.summaryBudget()).isEqualTo(5000);
        assertThat(capped.turnPrefixBudget()).isEqualTo(2500);
    }

    @Test
    void defaultConstants() {
        assertThat(CompactionSettings.DEFAULT_RESERVE_TOKENS).isEqualTo(16384);
        assertThat(CompactionSettings.DEFAULT_KEEP_RECENT_TOKENS).isEqualTo(20000);
    }
}
