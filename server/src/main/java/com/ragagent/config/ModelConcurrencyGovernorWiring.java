package com.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.LocalLimiter;
import com.ragagent.system.service.SystemSettingService;

import jakarta.annotation.PostConstruct;

/**
 * 后台并发闸门的启动装配（对照 Go {@code internal/container/container.go} 的
 * {@code registerLiteModelConcurrencyLimiter}，L596-610 + {@code resolveModelMaxConcurrency}
 * L574-580）：limit = model.max_concurrency（DB → {@code WEKNORA_MODEL_MAX_CONCURRENCY}
 * env → 缺省 32），装 {@link LocalLimiter}（进程内信号量）。
 *
 * <h2>与 Go 的已知差异（备案，known-issues 00-foundation #5）</h2>
 * <p>Go 的 Redis 模式用 {@code NewRedisLimiter}（跨进程协调，多实例共享信号量）；
 * Java 侧 Redis 限流器未翻译（与 asynq→进程内队列同族取舍），**恒走 Lite 分支**——
 * 单进程语义与 Go 的 Lite 模式逐行为一致，多实例部署下不做跨进程协调。</p>
 *
 * <p>limit ≤ 0 = 关闭治理（所有调用放行），日志文案对照 Go 原文。</p>
 */
@Configuration
public class ModelConcurrencyGovernorWiring {

    private static final Logger log = LoggerFactory.getLogger(ModelConcurrencyGovernorWiring.class);

    /** 对照 Go defaultModelMaxConcurrency = 32（container.go L568）。 */
    private static final int DEFAULT_MODEL_MAX_CONCURRENCY = 32;

    private final ConcurrencyGovernor governor;
    private final SystemSettingService systemSettingService;

    public ModelConcurrencyGovernorWiring(ConcurrencyGovernor governor,
            SystemSettingService systemSettingService) {
        this.governor = governor;
        this.systemSettingService = systemSettingService;
    }

    @PostConstruct
    void install() {
        long limit;
        try {
            limit = systemSettingService.getInt("model.max_concurrency",
                    "WEKNORA_MODEL_MAX_CONCURRENCY", DEFAULT_MODEL_MAX_CONCURRENCY);
        } catch (RuntimeException e) {
            // Go：ss == nil → 直接用缺省上限。设置面不可用时按缺省装配（尽力而为），
            // 闸门失效退化为"未装配"形态（全部放行），不得阻断应用启动。
            log.warn("[ModelLimiter] resolve model.max_concurrency failed, "
                    + "governor left unwired (all calls pass): {}", e.toString());
            return;
        }
        governor.setGovernor(new LocalLimiter(), (int) limit);
        if (limit <= 0) {
            log.info("[ModelLimiter] background concurrency governor DISABLED "
                    + "(model.max_concurrency<=0)");
            return;
        }
        log.info("[ModelLimiter] background model concurrency governed per-model, "
                + "limit={} (in-process, lite mode)", limit);
    }
}
