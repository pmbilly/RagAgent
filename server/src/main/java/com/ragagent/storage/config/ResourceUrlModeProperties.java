package com.ragagent.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资源 URL 模式的部署配置（B6 批 7）。
 *
 * <p>env 名保持原样：{@code RESOURCE_URL_MODE} → {@code resource.url-mode}
 * （Spring 松散绑定）。只承载原始串：解析失败/未配置回落 {@code handle} 的规则留在
 * {@code Mode.defaultMode()}（一个笔误应当降级到安全默认，而不是让每个请求都失败）。</p>
 */
@ConfigurationProperties(prefix = "resource")
public record ResourceUrlModeProperties(String urlMode) {
}
