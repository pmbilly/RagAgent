package com.ragagent.agent.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ragagent.llm.domain.ChatResponse;
import com.ragagent.llm.domain.TokenUsage;

/**
 * 溢出识别的 Go 实录断言（/tmp/wave42rec：overflow.go 内嵌；
 * 语料 = overflow_test.go 的真实供应商错误 + 大小写/否定分支 + 命中窗口三形态）。
 */
class CompactionOverflowTest {

    @Test
    void detectsRealProviderOverflowErrors() {
        String[] overflow = {
            "prompt is too long: 213462 tokens > 200000 maximum",
            "413 {\"error\":{\"type\":\"request_too_large\",\"message\":\"Request exceeds the maximum size\"}}",
            "Your input exceeds the context window of this model",
            "Requested token count exceeds the model's maximum context length of 131072 tokens",
            "Input length (265330) exceeds model's maximum context length (262144).",
            "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)",
            "This model's maximum prompt length is 131072 but the request contains 537812 tokens",
            "Please reduce the length of the messages or completion",
            "This endpoint's maximum context length is 131072 tokens",
            "The input (300 tokens) is longer than the model's context length (200 tokens).",
            "the request exceeds the available context size, try increasing it",
            "prompt token count of 5000 exceeds the limit of 4096",
            "invalid params, context window exceeds limit",
            "Your request exceeded model token limit: 128000",
            "Prompt has 5000 tokens, but the configured context size is 4096 tokens",
            "Range of input length should be [1, 30000]",
            "context_length_exceeded",
            // 实录 ovf17：大小写变体但词是 window 不是 length → 不匹配（Go 既有行为）
            "CONTEXT_WINDOW_EXCEEDED",
            "model_context_window_exceeded",
            "prompt too long; exceeded max context length",
            "too many tokens in the request",
            "token limit exceeded for this model",
        };
        boolean[] expected = {
            true, true, true, true, true, true, true, true, true, true, true, true,
            true, true, true, true, true, false, true, true, true, true,
        };
        for (int i = 0; i < overflow.length; i++) {
            assertThat(CompactionOverflow.isOverflowError(overflow[i]))
                    .as("ovf%d %s", i, overflow[i]).isEqualTo(expected[i]);
        }
    }

    @Test
    void doesNotMisreadThrottlingAsOverflow() {
        String[] notOverflow = {
            "ThrottlingException: Too many tokens, please wait before trying again.",
            "429 rate limit exceeded",
            "Too many requests",
            "connection reset by peer",
            "context deadline exceeded",
            "service unavailable, please retry",
        };
        for (int i = 0; i < notOverflow.length; i++) {
            assertThat(CompactionOverflow.isOverflowError(notOverflow[i]))
                    .as("novf%d %s", i, notOverflow[i]).isFalse();
        }
        assertThat(CompactionOverflow.isOverflowError(null)).isFalse();
    }

    private static ChatResponse resp(String finishReason, int promptTokens, int completionTokens) {
        ChatResponse r = new ChatResponse();
        r.setFinishReason(finishReason);
        r.getUsage().setPromptTokens(promptTokens);
        r.getUsage().setCompletionTokens(completionTokens);
        return r;
    }

    @Test
    void responseHitContextLimitThreeShapes() {
        final int window = 100000;
        final int budget = 8000;

        // 一般可恢复情形：length 停止但低于我们自己的上限
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", 90000, 2000), window, budget)).isTrue();
        // 用满了预算：模型只是还有话说，压缩后重试同样会截断
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", 50000, budget), window, budget)).isFalse();
        // 无声溢出：供应商接受大于窗口的 prompt（z.ai）
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("stop", window + 1, 10), window, budget)).isTrue();
        // 服务器截断输入恰好填满窗口（小米 MiMo）
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", window, 0), window, budget)).isTrue();
        // 普通完成的响应不是溢出
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("stop", 1000, 100), window, budget)).isFalse();
        // 没有 usage 就没有可比对象
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", 0, 0), window, budget)).isFalse();
        assertThat(CompactionOverflow.responseHitContextLimit(null, window, budget)).isFalse();
        // 99% 边界（>= 才算）
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", window * 99 / 100 - 1, 0), window, budget)).isFalse();
        assertThat(CompactionOverflow.responseHitContextLimit(
                resp("length", window * 99 / 100, 0), window, budget)).isTrue();
    }
}
