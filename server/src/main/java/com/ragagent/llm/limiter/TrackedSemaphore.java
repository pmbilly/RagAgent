package com.ragagent.llm.limiter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对照 Go limiter.trackedSemaphore（limiter.go）：每个 key 的观测载体
 * （当前 limit、等待者计数、用于展示的模型名）。
 *
 * Go 的 {@code atomic.Value // string} → Java {@link AtomicReference}。
 * Java 独有字段 {@code capacity} 对应 Go 缓冲 channel 的 cap（容量在首次使用时固定，
 * 之后传入的 limit 只更新展示值），RuntimeStats 的 Active 由它算出。
 */
final class TrackedSemaphore {

    final AtomicLong limit = new AtomicLong();
    final AtomicLong waiting = new AtomicLong();
    final AtomicReference<String> name = new AtomicReference<>("");
    final AtomicInteger capacity = new AtomicInteger();
}
