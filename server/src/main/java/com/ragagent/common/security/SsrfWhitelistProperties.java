package com.ragagent.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSRF 白名单的部署配置。
 *
 * <p>env 名保持原样：{@code SSRF_WHITELIST} → {@code ssrf.whitelist}、
 * {@code SSRF_WHITELIST_EXTRA} → {@code ssrf.whitelist-extra}（Spring 松散绑定）。</p>
 *
 * <p>白名单在 {@link SsrfGuard} 里是<b>进程级静态</b>（所有上下文共享一份，行为确定）；
 * 本类只提供原始串，解析与合并规则留在 {@code SsrfGuard.parseWhitelistRaw/mergeRaws}。</p>
 */
@ConfigurationProperties(prefix = "ssrf")
public record SsrfWhitelistProperties(String whitelist, String whitelistExtra) {
}
