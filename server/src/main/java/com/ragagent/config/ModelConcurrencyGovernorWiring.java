package com.ragagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.limiter.LocalLimiter;
import com.ragagent.system.service.SystemSettingService;

import jakarta.annotation.PostConstruct;

/**
 * 后台并发闸门的启动装配：limit = model.max_concurrency（DB → {@code WEKNORA_MODEL_MAX_CONCURRENCY}
 * env → 缺省 32），装 {@link LocalLimiter}（进程内信号量）。
 *
 * <h2>已知限制（详见 docs/known-issues/00-foundation.md #5）</h2>
 * <p>本仓只装进程内信号量，**恒走 Lite 分支**——多实例部署下不做跨进程协调
 * （Redis 分布式限流器未实现）。</p>
 *
 * <p>limit ≤ 0 = 关闭治理（所有调用放行）。</p>
 */
@Configuration
public class ModelConcurrencyGovernorWiring {

    private static final Logger log = LoggerFactory.getLogger(ModelConcurrencyGovernorWiring.class);

    /** 缺省上限。 */
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
            // 设置面不可用时按缺省装配（尽力而为），
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
