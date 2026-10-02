package com.ragagent.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储的部署配置（B6 批 7）。
 *
 * <p>env 名保持原样：{@code LOCAL_STORAGE_BASE_DIR} → {@code local.storage-base-dir}
 * （Spring 松散绑定）。只承载原始串：缺省 {@code /data/files} 的回落规则留在各读点。</p>
 *
 * <p>注：{@code knowledge.storage.LocalStorageService} 另有一处 {@code @Value} 形式的同键读取
 * （带 {@code weknora.storage.local-base-dir} 别名），属既有约定，本批不动。</p>
 */
@ConfigurationProperties(prefix = "local")
public record LocalStorageEnvProperties(String storageBaseDir) {
}
