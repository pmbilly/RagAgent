package com.ragagent.tracing.langfuse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * langfuse 装配（对照 Go container.go 的 {@code langfuse.Init(LoadConfigFromEnv())} +
 * 退出时的 {@code mgr.Shutdown(ctx)}）：启动即按环境变量安装单例；配置无效
 * （启用但缺 host/keys）直接抛异常拒启（Go 的 provider 错误向上传播）。
 *
 * <p>未设 LANGFUSE_* → 自动禁用（no-op 单例），零成本；与 Go 一致。</p>
 */
@Configuration
public class LangfuseWiring {

    private static final Logger log = LoggerFactory.getLogger(LangfuseWiring.class);

    /** 对照 langfuse.Init：失败即启动失败（must 语义）。 */
    @PostConstruct
    public void init() {
        LangfuseConfig cfg = LangfuseConfig.loadFromEnv();
        LangfuseManager.init(cfg);
        if (!cfg.enabled()) {
            log.info("[Langfuse] disabled (no LANGFUSE_PUBLIC_KEY/SECRET_KEY)");
        }
    }

    /** 对照退出清理（manager.Shutdown）：终刷未导出 span。 */
    @PreDestroy
    public void shutdown() {
        LangfuseManager.shutdown();
    }
}
