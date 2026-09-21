package com.ragagent.im.runtime;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 长连接守护（对照 Go internal/im/supervisor.go 全文，波 5 W5γ1 逐行翻译）。
 *
 * <p>部分 IM SDK（DingTalk/Feishu）把重连交给内部逻辑，长跑连接可能悄悄进入
 * "僵尸"态——连接对象活着但再也收不到消息。周期性重建把最坏中断限定在一个周期内。
 * RunSupervised 阻塞到 ctx 取消；取消时先经 stop 干净地拆除活动连接。</p>
 */
public final class ImSupervisor {

    private static final Logger log = LoggerFactory.getLogger(ImSupervisor.class);

    /** 主动重建周期（supervisor.go L19）。 */
    public static final Duration DEFAULT_RECYCLE_INTERVAL = Duration.ofHours(6);
    /** 连接失败后的退避（supervisor.go L22）。 */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(5);

    /**
     * 守护循环（对照 RunSupervised）：cancel 标志被置位后退出。MaxConnAge 到点主动
     * 重建；连接失败按退避重试。阻塞调用——在线程里跑。
     */
    public static void runSupervised(String name, Duration maxConnAge, Duration retryDelay,
            java.util.function.Supplier<Runnable> connect,
            java.util.function.BooleanSupplier cancelled) {
        Duration age = maxConnAge != null && !maxConnAge.isNegative() && !maxConnAge.isZero()
                ? maxConnAge : DEFAULT_RECYCLE_INTERVAL;
        Duration delay = retryDelay != null && !retryDelay.isNegative() && !retryDelay.isZero()
                ? retryDelay : DEFAULT_RETRY_DELAY;

        while (!cancelled.getAsBoolean()) {
            Runnable stop;
            try {
                stop = connect.get();
            } catch (Exception e) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                log.warn("[IM] {} connect failed: {}, retrying in {}", name, e.getMessage(), delay);
                if (sleepInterruptibly(delay, cancelled)) {
                    return;
                }
                continue;
            }
            log.info("[IM] {} connection established (recycle in {})", name, age);

            long deadline = System.nanoTime() + age.toNanos();
            while (System.nanoTime() < deadline) {
                if (cancelled.getAsBoolean()) {
                    if (stop != null) {
                        stop.run();
                    }
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (stop != null) {
                        stop.run();
                    }
                    return;
                }
            }
            log.info("[IM] {} periodic reconnect to refresh connection", name);
            if (stop != null) {
                stop.run();
            }
        }
    }

    private static boolean sleepInterruptibly(Duration d, java.util.function.BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + d.toNanos();
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return cancelled.getAsBoolean();
    }
}
