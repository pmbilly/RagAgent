package com.ragagent.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储提供方白名单的部署配置。
 *
 * <p>env 名保持原样：{@code STORAGE_ALLOW_LIST} → {@code storage.allow-list}
 * （Spring 松散绑定；同前缀下 {@code storage.type} 由 {@code storage.config.StorageProviderEnv}
 * 绑定，两者各绑各的字段）。</p>
 *
 * <p>只承载原始串：分隔符（{@code , ; | 换行 制表 空格}）、非法条目丢弃、
 * 缺省「全部受支持」等规则留在 {@link StorageAllowList}。</p>
 */
@ConfigurationProperties(prefix = "storage")
public record StorageAllowListProperties(String allowList) {
}
