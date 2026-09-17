package com.ragagent.mcp.protocol;

import java.time.Duration;
import java.time.Instant;

/**
 * 调用上下文（对照 Go 侧 MCP 客户端方法签名里的 {@code ctx context.Context}）。
 *
 * <p>只建模 Go ctx 被真正用到的两件事：<b>绝对截止时刻</b>（deadline）与<b>取消</b>
 * （见 {@link McpCancellation}）。对照关系：</p>
 * <ul>
 *   <li>{@code ctx.Err() != nil} → {@link #isCancelled()}；</li>
 *   <li>{@code context.DeadlineExceeded} → {@link #throwIfExpired()} 抛
 *       {@code "context deadline exceeded"}（文案对齐 Go）；</li>
 *   <li>{@code ctx.Done()} → {@link McpCancellation#future()}。</li>
 * </ul>
 *
 * <p>⚠️ 与 Go 一致：<b>manager 传给 Connect 的是连接生命周期，而不是调用方这一轮的
 * deadline</b>（manager.go:155-156 注释："SSE needs the connection lifetime, not the
 * requesting turn's deadline"）。所以 Connect 用的通常是"只有取消、没有 deadline"的 ctx。</p>
 */
public final class McpContext {

    private static final McpContext NONE = new McpContext(null, McpCancellation.NEVER);

    private final Instant deadline;
    private final McpCancellation cancellation;

    private McpContext(Instant deadline, McpCancellation cancellation) {
        this.deadline = deadline;
        this.cancellation = cancellation == null ? McpCancellation.NEVER : cancellation;
    }

    /** 无 deadline、不可取消（对照 {@code context.Background()}）。 */
    public static McpContext none() {
        return NONE;
    }

    /** 只带 deadline（对照 {@code context.WithTimeout(ctx, d)} 的 deadline 侧）。 */
    public static McpContext deadline(Instant deadline) {
        return new McpContext(deadline, McpCancellation.NEVER);
    }

    /** 只有取消、无 deadline（manager 的连接生命周期 ctx）。 */
    public static McpContext cancellable(McpCancellation cancellation) {
        return new McpContext(null, cancellation);
    }

    public static McpContext of(Instant deadline, McpCancellation cancellation) {
        return new McpContext(deadline, cancellation);
    }

    public Instant deadline() {
        return deadline;
    }

    public McpCancellation cancellation() {
        return cancellation;
    }

    public boolean isCancelled() {
        return cancellation.isCancelled() || isExpired();
    }

    public boolean isExpired() {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /**
     * 对照 Go 的 {@code if err := ctx.Err(); err != nil { return err }}：
     * 取消优先于超时的判定顺序与 Go 一致（canceled 先被 Err() 报出）。
     */
    public void throwIfCancelled() {
        cancellation.throwIfCancelled();
        throwIfExpired();
    }

    public void throwIfExpired() {
        if (isExpired()) {
            throw new McpException(McpErrorCode.TIMEOUT, "context deadline exceeded");
        }
    }

    /**
     * 单次 HTTP 请求应施加的超时：取"服务的 timeout"与"距离 deadline 的剩余时间"的较小值
     * （对照 Go：http.Client.Timeout 与 ctx deadline 同时生效，谁先到谁生效）。
     *
     * @param serviceTimeout 服务的出站超时（{@code McpAdvancedConfig.timeout}，默认 30s）
     */
    public Duration effectiveTimeout(Duration serviceTimeout) {
        if (deadline == null) {
            return serviceTimeout;
        }
        Duration remaining = remaining();
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return remaining.compareTo(serviceTimeout) < 0 ? remaining : serviceTimeout;
    }

    /**
     * 距离 deadline 的剩余时长（可能为负）；无 deadline 时返回 {@code null}
     * （对照 Go {@code ctx.Deadline()} 的 ok 返回值）。
     */
    public Duration remaining() {
        if (deadline == null) {
            return null;
        }
        return Duration.between(Instant.now(), deadline);
    }

    /** 派生一个只换 deadline 的上下文（保留同一取消信号）。 */
    public McpContext withDeadline(Instant newDeadline) {
        return new McpContext(newDeadline, cancellation);
    }
}
