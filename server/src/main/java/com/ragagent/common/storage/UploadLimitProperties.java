package com.ragagent.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传大小限额的部署配置。
 *
 * <p>env 名保持原样：{@code MAX_FILE_SIZE_MB} → {@code max.file-size-mb}（Spring 松散绑定）。
 * 只承载原始串：缺省 50、非法值回落缺省的规则留在 {@link UploadLimits}。</p>
 */
@ConfigurationProperties(prefix = "max")
public record UploadLimitProperties(String fileSizeMb) {
}
