package com.ragagent.datasource.connector.ima;

import java.time.Duration;
import java.util.List;

/**
 * IMA 客户端的重试预算（对照 Go {@code ima/client.go} 里
 * {@code callAPIAt} 开头那三个常量与 {@code backoff} 切片）。
 *
 * <h2>为什么要抽成参数</h2>
 * <p>Go 的退避是<b>硬编码</b>的（{@code 2s/4s/8s} 与 {@code retry5xxDelay=2s}），
 * Go 的测试因此只能绕着它走（yuque 那边甚至要靠 ctx 超时把 2 秒的 5xx 退避
 * "压掉"）。Java 侧把它做成可注入的构造参数，测试注入
 * {@link #immediate()}（全零）就能在毫秒级跑完重试矩阵，
 * 而不是每个用例等 N×2 秒。</p>
 *
 * <p><b>默认值一字不改</b>：{@link #defaults()} 就是 Go 的那三个常量，
 * 生产路径只用它。</p>
 *
 * @param maxRetries   可重试错误的总重试次数（Go 的 {@code maxRetries=3}）
 * @param max5xxRetries 5xx 的重试次数上限（Go 的 {@code max5xxRetries=1}）
 * @param retry5xxDelay 5xx 重试前的固定等待（Go 的 {@code retry5xxDelay=2s}）
 * @param backoff      退避序列（Go 的 {@code []time.Duration{2s,4s,8s}}）；
 *                     429 与传输层失败按 {@code min(attempt, len-1)} 取
 */
public record ImaRetryPolicy(int maxRetries, int max5xxRetries, Duration retry5xxDelay,
                             List<Duration> backoff) {

    /** Go 的硬编码默认值。生产路径唯一使用的取值。 */
    public static ImaRetryPolicy defaults() {
        return new ImaRetryPolicy(3, 1, Duration.ofSeconds(2),
                List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)));
    }

    /** 测试用：一切等待为 0，重试次数不变（语义不变、耗时归零）。 */
    public static ImaRetryPolicy immediate() {
        return new ImaRetryPolicy(3, 1, Duration.ZERO,
                List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO));
    }

    public ImaRetryPolicy {
        backoff = backoff == null || backoff.isEmpty() ? List.of(Duration.ZERO) : List.copyOf(backoff);
    }

    /**
     * 对照 Go 的 {@code backoff[minInt(attempt, len(backoff)-1)]}：429 与业务限频
     * 走的是**夹取后**的下标，所以第 4 次尝试用的是最后一档退避。
     */
    public Duration backoffAt(int attempt) {
        return backoff.get(Math.min(Math.max(attempt, 0), backoff.size() - 1));
    }
}
