package com.ragagent.datasource.connector.yuque;

import java.time.Duration;
import java.util.List;

/**
 * 语雀客户端的重试预算（对照 Go {@code yuque/client.go} 的 {@code doRequest}
 * 开头那三个常量与 {@code backoff} 切片）。
 *
 * <h2>为什么要抽成参数</h2>
 * <p>Go 的退避是硬编码的（{@code 2s/4s/8s}、{@code retry5xxDelay=2s}），
 * Go 的测试只能绕着走（{@code TestClient_5xxRetriesOnce} 甚至要开一个
 * 5 秒超时的 ctx 才不至于等 2 秒）。Java 侧做成可注入的构造参数，测试注入
 * {@link #immediate()} 就能在毫秒级跑完重试矩阵。</p>
 *
 * <p><b>默认值一字不改</b>：{@link #defaults()} 就是 Go 的三个常量，生产只用它。</p>
 *
 * @param maxRetries    可重试错误的总重试次数（Go {@code maxRetries=3}）
 * @param max5xxRetries 5xx 的重试次数上限（Go {@code max5xxRetries=1}）
 * @param retry5xxDelay 5xx 重试前的固定等待（Go {@code retry5xxDelay=2s}）
 * @param backoff       退避序列（Go {@code []time.Duration{2s,4s,8s}}）
 */
public record YuqueRetryPolicy(int maxRetries, int max5xxRetries, Duration retry5xxDelay,
                               List<Duration> backoff) {

    /** Go 的硬编码默认值。生产路径唯一使用的取值。 */
    public static YuqueRetryPolicy defaults() {
        return new YuqueRetryPolicy(3, 1, Duration.ofSeconds(2),
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)));
    }

    /** 测试用：一切等待为 0，重试次数不变（语义不变、耗时归零）。 */
    public static YuqueRetryPolicy immediate() {
        return new YuqueRetryPolicy(3, 1, Duration.ZERO,
                List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
    }

    public YuqueRetryPolicy {
        backoff = backoff == null || backoff.isEmpty() ? List.of(Duration.ZERO) : List.copyOf(backoff);
    }

    /**
     * 对照 Go 的 {@code backoff[min(attempt, len(backoff)-1)]}：429 走的是
     * <b>夹取后</b>的下标，所以第 4 次尝试用的是最后一档退避。
     */
    public Duration backoffAt(int attempt) {
        return backoff.get(Math.min(Math.max(attempt, 0), backoff.size() - 1));
    }
}
