package com.ragagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * const.go 纯逻辑的 Go 实录断言（/tmp/wave42rec：const.go + types/agent.go 预算族 +
 * browserskill/human.go 内嵌）。
 */
class AgentConstsTest {

    @Test
    void isTransientErrorTable() {
        String[] errs = {
            "rate limit exceeded, retry later",
            "HTTP 429 Too Many Requests",
            "500 Internal Server Error",
            "502 bad gateway",
            "503 Service Unavailable",
            "504 gateway timeout",
            "provider overloaded",
            "request timed out after 30s",
            "connection refused",
            "connection reset by peer",
            "internal server error",
            "temporarily unavailable",
            "context deadline exceeded",
            "stream stalled with no output",
            "invalid api key",
            "400 bad request: malformed arguments",
            "unexpected EOF",
            "context canceled",
            "",
        };
        boolean[] expected = {
            true, true, true, true, true, true, true, true, true, true,
            true, true, true, true, false, false, false, false, false,
        };
        for (int i = 0; i < errs.length; i++) {
            assertThat(AgentConsts.isTransientError(errs[i])).as("transient%d", i).isEqualTo(expected[i]);
        }
        // 大小写不敏感 + null
        assertThat(AgentConsts.isTransientError("RATE LIMIT hit")).isTrue();
        assertThat(AgentConsts.isTransientError((String) null)).isFalse();
        assertThat(AgentConsts.isTransientError((Throwable) null)).isFalse();
        assertThat(AgentConsts.isTransientError(new RuntimeException("connection refused"))).isTrue();
    }

    @Test
    void toolExecutionTimeoutTable() {
        assertThat(AgentConsts.toolExecutionTimeout("web_fetch")).isEqualTo(Duration.ofSeconds(60));
        assertThat(AgentConsts.toolExecutionTimeout("anything_else")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void constantsMatchGo() {
        assertThat(AgentConsts.DEFAULT_AGENT_TEMPERATURE).isEqualTo(0.7);
        assertThat(AgentConsts.DEFAULT_AGENT_MAX_ITERATIONS).isEqualTo(20);
        assertThat(AgentConsts.DEFAULT_USE_CUSTOM_SYSTEM_PROMPT).isFalse();
        assertThat(AgentConsts.MAX_LLM_RETRIES).isEqualTo(2);
        assertThat(AgentConsts.MAX_EMPTY_RESPONSE_RETRIES).isEqualTo(2);
        assertThat(AgentConsts.MAX_REPEATED_RESPONSE_ROUNDS).isEqualTo(2);
        assertThat(AgentConsts.DEFAULT_LLM_STALL_TIMEOUT).isEqualTo(Duration.ofSeconds(120));
        assertThat(AgentConsts.CONTEXT_SAFETY_TOKENS).isEqualTo(4096);
        assertThat(AgentConsts.llmStallTimeout(null)).isEqualTo(Duration.ofSeconds(120));
        assertThat(AgentConsts.llmStallTimeout(0)).isEqualTo(Duration.ofSeconds(120));
        assertThat(AgentConsts.llmStallTimeout(300)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void completionBudgetTable() {
        int[][] cases = {
            {0, 4096}, {4096, 4096}, {24576, 24576}, {2048, 2048}, {-5, 4096},
        };
        for (int[] c : cases) {
            assertThat(AgentBudgets.agentRoundMaxCompletionTokens(c[0]))
                    .as("budget_%d_", c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void reserveAndClampTable() {
        // reserve（无沙箱 budget=4096；沙箱 budget=24576）
        assertThat(AgentConsts.contextReserveTokens(4096)).isEqualTo(16384);
        assertThat(AgentConsts.contextReserveTokens(24576)).isEqualTo(28672);
        // clamp
        assertThat(AgentConsts.clampCompletionBudgetToContext(128000, 0, 4096)).isEqualTo(4096);
        assertThat(AgentConsts.clampCompletionBudgetToContext(128000, 60000, 4096)).isEqualTo(4096);
        assertThat(AgentConsts.clampCompletionBudgetToContext(128000, 200000, 4096)).isEqualTo(1);
        assertThat(AgentConsts.clampCompletionBudgetToContext(0, 0, 4096)).isEqualTo(4096);
        assertThat(AgentConsts.clampCompletionBudgetToContext(100000, 98000, 24576)).isEqualTo(1);
    }

    @Test
    void generateEventIDIsBridgedNotDuplicated() {
        // 波 4.1 已收编到 event 包：格式 <uuid前8位>-<suffix>（EventIdsTest 有实录断言）
        String id = com.ragagent.event.EventIds.generateEventID("thinking");
        assertThat(id).endsWith("-thinking");
        assertThat(id.split("-")[0]).hasSize(8).matches("[0-9a-f]{8}");
    }
}
