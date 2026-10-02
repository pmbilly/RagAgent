package com.ragagent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 启动最早点安装 {@link AppEnvLookup}（B6 批 10）。
 *
 * <p>为什么用 {@code EnvironmentPostProcessor} 而不是 {@code @Configuration}：本批收编的读点里
 * 有<b>启动期即可触发</b>的（{@code StartupTaskRecovery.distributed()} 决定 Lite/分布式分支、
 * {@code BuiltinModelsReconciler} 是启动 runner、API-Key 引导、Gate 的 pubsub 频道），
 * 而 {@code @Configuration} 的实例化顺序在 Spring 里不保证——晚装一步就会**静默**读不到部署配置。
 * 本钩子在<b>所有 bean 实例化之前</b>执行，从机制上消除该风险（B6 批 9 已在检索域踩过一次同类风险）。</p>
 *
 * <p>用最低优先级：让 Boot 自带的配置数据装配（application.yml / 命令行 / {@code SPRING_APPLICATION_JSON}）
 * 先进 Environment；装的又是查找函数而非值，读时实时取值，不存在陈旧快照。</p>
 */
public class AppEnvLookupEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        AppEnvLookup.install(EnvPropertyLookup.of(environment));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
