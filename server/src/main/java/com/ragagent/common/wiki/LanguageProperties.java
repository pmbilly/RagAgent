package com.ragagent.common.wiki;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署默认语言的配置（B6 批 6）。
 *
 * <p>env 名保持原样：{@code WEKNORA_LANGUAGE} → {@code weknora.language}（Spring 松散绑定；
 * 同前缀下 {@code weknora.edition} / {@code weknora.housekeeping.*} 等由各自配置类绑定）。</p>
 *
 * <p>缺省 {@code zh-CN} 的回落规则留在 {@link WikiLanguageSupport}。</p>
 */
@ConfigurationProperties(prefix = "weknora")
public record LanguageProperties(String language) {
}
