package com.ragagent.tracing.langfuse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * langfuse 装配：启动即按环境变量安装单例；配置无效
 * （启用但缺 host/keys）直接抛异常拒启。
 *
 * <p>未设 LANGFUSE_* → 自动禁用（no-op 单例），零成本。</p>
 *
 * <p>取值走 {@link LangfuseEnvProperties} 的 {@code @ConfigurationProperties} 绑定。</p>
 */
@Configuration
public class LangfuseWiring {

    private static final Logger log = LoggerFactory.getLogger(LangfuseWiring.class);

    private final LangfuseEnvProperties envProperties;

    public LangfuseWiring(LangfuseEnvProperties envProperties) {
        this.envProperties = envProperties;
    }

    /** 失败即启动失败。 */
    @PostConstruct
    public void init() {
        LangfuseConfig cfg = LangfuseConfig.fromEnv(envProperties);
        LangfuseManager.init(cfg);
        if (!cfg.enabled()) {
            log.info("[Langfuse] disabled (no LANGFUSE_PUBLIC_KEY/SECRET_KEY)");
        }
    }

    /** 退出清理：终刷未导出 span。 */
    @PreDestroy
    public void shutdown() {
        LangfuseManager.shutdown();
    }
}
