package com.ragagent.wiki.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * LLM 瞬时错误判定的对等测试（对照 Go
 * internal/application/service/wiki_ingest_retry_test.go，137 行，<b>用例逐条翻译</b>）。
 *
 * <p>Go 用 {@code context.Background()} 表示"父作用域未取消"；Java 侧对应
 * {@code parentContextCancelled = false}。</p>
 */
class WikiIngestRetryTest {

    private static final boolean CTX_ALIVE = false;
    private static final boolean CTX_CANCELLED = true;

    // ── TestIsTransientLLMError_HTTPStatuses（Go L14-40） ──

    @ParameterizedTest(name = "{0} → 瞬时={1}")
    @DisplayName("TestIsTransientLLMError_HTTPStatuses：状态码分支")
    @CsvSource({
            "'API request failed with status 504: Remote error, timeout with 60', true",
            "'API request failed with status 503: service unavailable', true",
            "'API request failed with status 502: bad gateway', true",
            "'API request failed with status 500: internal server error', true",
            "'API request failed with status 429: rate limited', true",
            "'API request failed with status 408: request timeout', true",
            "'API request failed with status 520: Web server returned unknown', true",
            "'API request failed with status 524: timeout (Cloudflare)', true",
            "'API request failed with status 501: not implemented', true",
            "'API request failed with status 521: Web server is down', true",
            "'API request failed with status 522: Connection timed out', true",
            "'API request failed with status 523: Origin is unreachable', true",
            // 4xx 永久错误：重试只会同样失败
            "'API request failed with status 400: bad request', false",
            "'API request failed with status 401: unauthorized', false",
            "'API request failed with status 403: forbidden (quota exhausted)', false",
            "'API request failed with status 404: model not found', false"
    })
    void transientHttpStatuses(String message, boolean want) {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_ALIVE, message)).isEqualTo(want);
    }

    // ── TestIsTransientLLMError_TransportSubstrings（Go L42-72） ──

    @ParameterizedTest(name = "{0} → 瞬时={1}")
    @DisplayName("TestIsTransientLLMError_TransportSubstrings：传输层子串分支")
    @CsvSource({
            "'send request: dial tcp: lookup api.example.com: no such host', true",
            "'send request: read tcp 10.0.0.1: i/o timeout', true",
            "'send request: Post \"...\": context deadline exceeded', true",
            "'send request: connection reset by peer', true",
            "'send request: connection refused', true",
            "'send request: broken pipe', true",
            "'send request: tls handshake timeout', true",
            "'send request: unexpected EOF', true",
            // 非瞬时：错误是可操作的（缺 model id、工具调用畸形等），重试只是浪费预算
            "'model not configured for tool use', false",
            "'invalid JSON in tool arguments', false",
            "'parse template: syntax error', false"
    })
    void transientTransportSubstrings(String message, boolean want) {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_ALIVE, message)).isEqualTo(want);
    }

    // ── TestIsTransientLLMError_AbortsWhenParentCtxDone（Go L74-90） ──

    @Test
    @DisplayName("TestIsTransientLLMError_AbortsWhenParentCtxDone：父作用域取消时短路为非瞬时")
    void abortsWhenParentContextDone() {
        // 这条错误本来会被判为瞬时（见上面的状态码用例），
        // 但已取消的 ctx 必须覆盖它，让任务能及时拆栈。
        String message = "API request failed with status 504: Remote error";
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_CANCELLED, message)).isFalse();
    }

    // ── TestIsTransientLLMError_NilError（Go L92-99） ──

    @Test
    @DisplayName("TestIsTransientLLMError_NilError：nil 永不瞬时")
    void nilErrorIsNotTransient() {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_ALIVE, (Throwable) null)).isFalse();
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_ALIVE, (String) null)).isFalse();
    }

    @Test
    @DisplayName("Throwable 重载读取 getMessage（对照 Go 的 err.Error()）")
    void throwableOverloadReadsMessage() {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(
                CTX_ALIVE, new RuntimeException("API request failed with status 503: down"))).isTrue();
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(
                CTX_ALIVE, new RuntimeException("bad request"))).isFalse();
    }

    // ── TestIsTransientLLMError_RateLimit403（Go L101-137） ──

    @ParameterizedTest(name = "{0}")
    @DisplayName("TestIsTransientLLMError_RateLimit403：403 只在响应体是限流语义时才瞬时")
    @CsvSource({
            // 网关把 QPM/QPS 限流报成 403
            "qpm gateway, 'API request failed with status 403: {\"code\":\"0x04030020\",\"message\":\"调用频率（qpm）超限\"}', true",
            "qps gateway, 'API request failed with status 403: {\"message\":\"qps exceeded\"}', true",
            "rate limit, 'API request failed with status 403: rate limit reached', true",
            "rate_limit, 'API request failed with status 403: rate_limit exceeded', true",
            "too many requests, 'API request failed with status 403: too many requests', true",
            "throttled, 'API request failed with status 403: request throttled', true",
            "chinese frequency, 'API request failed with status 403: 请求过于频繁，请稍后重试', true",
            "busy, 'API request failed with status 403: 服务繁忙', true",
            "try again later, 'API request failed with status 403: please try again later', true",
            "slow down, 'API request failed with status 403: slow down your requests', true",
            // 鉴权形态的 403 保持永久
            "unauthorized, 'API request failed with status 403: invalid api key', false",
            "forbidden, 'API request failed with status 403: forbidden', false",
            "permission denied, 'API request failed with status 403: permission denied', false",
            "quota exhausted, 'API request failed with status 403: forbidden (quota exhausted)', false",
            "unknown body, 'API request failed with status 403: {\"error\":\"something else\"}', false"
    })
    void rateLimit403(String name, String message, boolean want) {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(CTX_ALIVE, message))
                .as(name)
                .isEqualTo(want);
    }

    @Test
    @DisplayName("中文限流措辞（频率超限）也被识别")
    void chineseRateLimitWording() {
        assertThat(WikiLlmRetryPolicy.isTransientLlmError(
                CTX_ALIVE, "API request failed with status 403: 频率超限"))
                .isTrue();
    }

    // ── 退避常量（对照 Go wikiLLMBackoffBase / wikiLLMMaxAttempts） ──

    @ParameterizedTest
    @DisplayName("退避序列 = base << (attempt-1)：2s、4s、8s")
    @ValueSource(ints = {1, 2, 3})
    void backoffSequence(int attempt) {
        long expected = switch (attempt) {
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        assertThat(WikiIngestConstants.llmBackoff(attempt).toSeconds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("退避基数与尝试上限与 Go 一致（3 次尝试、2 秒基数）")
    void retryBudget() {
        assertThat(WikiIngestConstants.LLM_MAX_ATTEMPTS).isEqualTo(3);
        assertThat(WikiIngestConstants.LLM_BACKOFF_BASE.toSeconds()).isEqualTo(2);
        assertThat(WikiIngestConstants.LLM_MAX_TOKENS).isEqualTo(32768);
    }

    @Test
    @DisplayName("限流指示词清单与 Go 逐条一致（顺序也一致，便于 diff 对照）")
    void rateLimitIndicatorList() {
        assertThat(WikiLlmRetryPolicy.RATE_LIMIT_ERROR_INDICATORS).isEqualTo(List.of(
                "qpm", "qps", "rate limit", "rate_limit", "too many requests", "throttl",
                "调用频率", "频率超限", "请求过于频繁", "繁忙",
                "try again later", "retry later", "slow down"));
    }
}
