package com.ragagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用级 env 查找面的<b>装配守卫</b>（B16「静默失效」扫描产出）。
 *
 * <p>背景：{@code AppEnvLookup} 由 {@code AppEnvLookupEnvironmentPostProcessor} 安装，而该 EPP
 * 只能由 {@code META-INF/spring.factories} 装载——写成
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports} 会被
 * <b>静默忽略</b>（不报错、不执行、读点回落到「未配置」）。单条注解/文件形态检查抓不住这种回归，
 * 因为它恰恰是"代码看着对、运行时不生效"。</p>
 *
 * <p>本测试因此做<b>运行时</b>断言：在真实 Spring 上下文里向 {@code AppEnvLookup} 取两个键——
 * 一个键名原样、一个走属性风格回落。若 EPP 未被装载，两次取值都是 {@code null}，此测试立刻变红。
 * 覆盖面：约 25 个散落读点（Ollama / OIDC / Gate / 邀请 TTL / 内置模型 / 启动恢复 / 临时文档 TTL
 * / vectorstore 副本数 …）。</p>
 */
@SpringBootTest(properties = {
        "APP_ENV_LOOKUP_PROBE=probe-exact",
        "app.env.lookup.probe.dotted=probe-dotted"
})
class AppEnvLookupWiringTest {

    @Test
    @DisplayName("EPP 真的装载了：键名原样命中 + 属性风格回落命中 + 未配置为 null")
    void environmentPostProcessorIsActuallyInstalled() {
        assertThat(AppEnvLookup.get("APP_ENV_LOOKUP_PROBE"))
                .as("键名原样（环境变量风格）必须命中——EPP 未装载时这里恒为 null")
                .isEqualTo("probe-exact");

        assertThat(AppEnvLookup.get("APP_ENV_LOOKUP_PROBE_DOTTED"))
                .as("属性风格回落（APP_ENV_LOOKUP_PROBE_DOTTED → app.env.lookup.probe.dotted）必须命中")
                .isEqualTo("probe-dotted");

        assertThat(AppEnvLookup.get("APP_ENV_LOOKUP_PROBE_MISSING"))
                .as("未配置即 null（各读点的缺省语义依赖它）")
                .isNull();
    }
}
