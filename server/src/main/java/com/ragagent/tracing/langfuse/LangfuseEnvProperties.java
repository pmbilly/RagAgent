package com.ragagent.tracing.langfuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * langfuse 的环境变量族（{@code LANGFUSE_*}，B6 批 2）。
 *
 * <p><b>环境变量名保持原样</b>：{@code LANGFUSE_FLUSH_INTERVAL} → {@code langfuse.flush-interval}
 * （Spring 松散绑定），部署侧 .env 不需要改。</p>
 *
 * <p>字段一律 {@code String}，<b>不</b>绑定成数字/布尔：解析规则（缺省值、非法值回落、
 * Go 风格时长串 {@code 1m30s}、{@code parseBool} 的十种真值写法、采样率越界即忽略）
 * 全部保留在 {@link LangfuseConfig#fromEnv} 里——绑成类型会让「写错的字面量」变成启动失败，
 * 而 Go 原语义是静默回落默认值。</p>
 */
@ConfigurationProperties(prefix = "langfuse")
public record LangfuseEnvProperties(
        String enabled,
        String host,
        String publicKey,
        String secretKey,
        String flushAt,
        String flushInterval,
        String queueSize,
        String requestTimeout,
        String release,
        String environment,
        String sampleRate,
        String debug) {
}
