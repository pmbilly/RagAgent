package com.ragagent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库清理（housekeeping）的部署开关。
 *
 * <p>env 名保持原样：{@code WEKNORA_HOUSEKEEPING_ENABLED} → {@code weknora.housekeeping.enabled}、
 * {@code WEKNORA_DOCUMENT_PROCESS_TIMEOUT} → {@code weknora.document-process-timeout}。</p>
 *
 * <p>同前缀 {@code weknora} 下另有 {@code common.deployment.DeploymentProperties}（版次/部署模式）
 * ——两者各绑各的字段，互不影响（Spring 默认忽略未知字段）。</p>
 *
 * <p>时长串与开关字面量的解析留在 {@code HousekeepingService} 的纯函数里（错误/缺省语义照旧），
 * 本类只承载原始串。</p>
 */
@ConfigurationProperties(prefix = "weknora")
public record HousekeepingProperties(Housekeeping housekeeping, String documentProcessTimeout) {

    /** {@code weknora.housekeeping.*}。 */
    public record Housekeeping(String enabled) {
    }

    /** 清理总开关的原始串（{@code WEKNORA_HOUSEKEEPING_ENABLED}）。 */
    public String enabledRaw() {
        return housekeeping == null ? null : housekeeping.enabled();
    }
}
