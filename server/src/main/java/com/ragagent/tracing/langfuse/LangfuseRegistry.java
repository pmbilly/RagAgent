package com.ragagent.tracing.langfuse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单例持有者（对照 Go manager.go 的 {@code global}/{@code globalMu} +
 * {@code Init}/{@code GetManager}/{@code Shutdown}）。
 *
 * <p>未 Init → no-op 单例（Go 返回 nil + 调用方容忍；Java 恒非 null，形状更省心）。</p>
 */
final class LangfuseRegistry {

    private static final Logger log = LoggerFactory.getLogger(LangfuseRegistry.class);

    private static final Object LOCK = new Object();

    private static volatile LangfuseManager current = NoopLangfuseManager.INSTANCE;

    private LangfuseRegistry() {
    }

    static LangfuseManager get() {
        return current;
    }

    /** 对照 Init：校验 → 启用则建真实现（导出链路）；返回安装的实例。 */
    static LangfuseManager init(LangfuseConfig cfg) {
        cfg.validate();
        LangfuseManager manager = cfg.enabled()
                ? new DefaultLangfuseManager(cfg)
                : NoopLangfuseManager.INSTANCE;
        synchronized (LOCK) {
            current = manager;
        }
        if (cfg.enabled()) {
            log.info("[Langfuse] enabled host={} flush_at={} flush_interval={}ms sample_rate={} (OTLP/OTel SDK)",
                    cfg.host(), cfg.flushAt(), cfg.flushIntervalMs(), cfg.sampleRate());
        }
        return manager;
    }

    /** 测试专用：直接安装管理器（对照 Go Config.testExporter 的注入面）。 */
    static void installForTest(LangfuseManager manager) {
        synchronized (LOCK) {
            current = manager == null ? NoopLangfuseManager.INSTANCE : manager;
        }
    }

    /** 对照 Shutdown：终刷 + 卸载（重复调用幂等）。 */
    static void shutdown() {
        LangfuseManager previous;
        synchronized (LOCK) {
            previous = current;
            current = NoopLangfuseManager.INSTANCE;
        }
        if (previous instanceof DefaultLangfuseManager real) {
            real.shutdown();
        }
    }
}
