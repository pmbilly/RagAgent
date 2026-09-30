package com.ragagent.wiki.service;

import java.util.List;
import java.util.Locale;

/**
 * LLM 瞬时错误判定。
 *
 * <p>分类刻意保守：「判断不出来就当永久错误」能让重试保持廉价，也避免掩盖真正的 bug。</p>
 *
 * <h2>判为瞬时的三类</h2>
 * <ol>
 *   <li><b>HTTP 状态</b>：408（上游通常还没处理）、429（限流，退避后可能成功）、
 *       5xx（任意服务端故障，含网关最常见的 504 "Remote error, timeout with 60"），
 *       以及 Cloudflare 系的 520–524。</li>
 *   <li><b>403 但响应体是限流语义</b>：见下。</li>
 *   <li><b>文本子串</b>：{@code timeout} / {@code connection reset} /
 *       {@code context deadline exceeded} 等传输层故障——不少供应商不返回结构化状态码。</li>
 * </ol>
 *
 * <h2>为什么 403 要单独判</h2>
 * <p>有些网关把 QPM/QPS 超限报成 HTTP 403 而不是标准 429（例如 MaaS 网关返回
 * code {@code 0x04030020}、message「调用频率（qpm）超限」）。供应商错误里会内嵌
 * 响应体（{@code "API request failed with status 403: {...}"}），所以<span>不能</span>
 * 只看状态码——<b>纯 403 通常是鉴权失败，绝不能重试</b>，本判定因此要求响应体里
 * 命中 {@link #RATE_LIMIT_ERROR_INDICATORS} 中的限流词才升级为瞬时。</p>
 *
 * <p><b>取消短路</b>：调用方显式传入「父作用域是否已取消」——任务正在取消时
 * 再试一次只会同样失败，故直接判为非瞬时。</p>
 */
public final class WikiLlmRetryPolicy {

    private WikiLlmRetryPolicy() {}

    /**
     * 把 403 响应体标记为
     * 「限流」而非「鉴权失败」的子串（判定是"命中即真"，顺序不影响结果）。
     */
    public static final List<String> RATE_LIMIT_ERROR_INDICATORS = List.of(
            "qpm",                 // 网关 qpm 配额（0x04030020）
            "qps",                 // 网关 qps 配额
            "rate limit",          // OpenAI 风格的 "rate limit reached"
            "rate_limit",
            "too many requests",   // RFC 6585 措辞
            "throttl",             // "throttled"
            "调用频率",               // 中文网关常见措辞
            "频率超限",
            "请求过于频繁",
            "繁忙",                  // "服务繁忙，请稍后重试"
            "try again later",
            "retry later",
            "slow down");

    /**
     * 瞬时 HTTP 状态码清单。
     * 匹配形态是供应商气泡上来的 {@code "API request failed with status NNN: ..."}。
     */
    private static final List<String> TRANSIENT_STATUS_MARKERS = List.of(
            "status 408", "status 429",
            "status 500", "status 501", "status 502", "status 503", "status 504",
            "status 520", "status 521", "status 522", "status 523", "status 524");

    /** 传输层故障子串清单，全部按小写匹配。 */
    private static final List<String> TRANSIENT_TRANSPORT_MARKERS = List.of(
            "timeout",
            "timed out",
            "connection reset",
            "connection refused",
            "broken pipe",
            "no such host",              // DNS 抖动
            "i/o timeout",
            "unexpected eof",
            "tls handshake",
            "context deadline exceeded"); // 嵌套的逐次调用超时

    /**
     * 判定一次 LLM 失败是否瞬时（可重试）。
     *
     * @param parentContextCancelled 调用方所在的任务上下文是否已取消
     * @param err                    失败原因；null 一律返回 false
     */
    public static boolean isTransientLlmError(boolean parentContextCancelled, Throwable err) {
        if (err == null) {
            return false;
        }
        String message = err.getMessage() == null ? "" : err.getMessage();
        return isTransientLlmError(parentContextCancelled, message);
    }

    /**
     * 同上，但直接收<b>错误文本</b>。
     *
     * <p>两条前置短路（顺序固定）：</p>
     * <ol>
     *   <li>err 为 nil → 不可能瞬时（否则忘了处理成功路径的调用方会空转）；</li>
     *   <li>父上下文已取消 → 不重试（任务正在拆栈，下一个尝试只会同样失败）。</li>
     * </ol>
     */
    public static boolean isTransientLlmError(boolean parentContextCancelled, String message) {
        if (message == null) {
            return false;
        }
        // 父 ctx 已过期就绝不重试
        if (parentContextCancelled) {
            return false;
        }

        // 供应商把 HTTP 状态格式化成 "API request failed with status NNN: ..." —— 先匹配它
        for (String marker : TRANSIENT_STATUS_MARKERS) {
            if (message.contains(marker)) {
                return true;
            }
        }

        String lower = message.toLowerCase(Locale.ROOT);
        // 部分网关把 QPM/QPS 限流报成 HTTP 403 而非 429。纯 403 通常是鉴权失败，
        // 绝不能重试，因此这里仍以响应体里的限流指示词为前提。
        if (message.contains("status 403")) {
            for (String indicator : RATE_LIMIT_ERROR_INDICATORS) {
                if (lower.contains(indicator)) {
                    return true;
                }
            }
        }

        for (String marker : TRANSIENT_TRANSPORT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
