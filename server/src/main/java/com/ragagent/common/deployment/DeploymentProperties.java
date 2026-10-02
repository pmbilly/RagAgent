package com.ragagent.common.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署形态配置（B6 批 4）。
 *
 * <ul>
 *   <li>{@code WEKNORA_EDITION} → {@code weknora.edition}：版次，capabilities 端点原样下发
 *       （缺省 {@code standard}；{@code lite} 会关掉 auto-setup 等运维面）。</li>
 *   <li>{@code WEKNORA_DEPLOYMENT_MODE} → {@code weknora.deployment.mode}：是否生产部署。
 *       <b>取代 Go 时代的 {@code GIN_MODE}</b>（Gin 是 Go 的 Web 框架，该变量在本仓只剩
 *       「是否生产」一个用途）——{@code production} 判为生产，兼容历史字面量 {@code release}，
 *       其余（含未设置）按非生产处理。</li>
 * </ul>
 *
 * <p>前缀是 {@code weknora}（与 env 名同形）；同前缀下别的属性（{@code weknora.tenant.*}
 * 等由各自配置类绑定）与本记录无字段对应，按 Spring 默认忽略未知字段处理。</p>
 */
@ConfigurationProperties(prefix = "weknora")
public record DeploymentProperties(String edition, Deployment deployment) {

    /** {@code weknora.deployment.*}。 */
    public record Deployment(String mode) {
    }

    /** 版次：未设/全空白 → {@code standard}（对照 Go 的 edition 兜底）。 */
    public String editionOrDefault() {
        return edition == null || edition.isBlank() ? "standard" : edition.trim();
    }

    /** 生产部署判定（{@code production} 或历史字面量 {@code release}）。 */
    public boolean isProduction() {
        if (deployment == null || deployment.mode() == null) {
            return false;
        }
        String mode = deployment.mode().trim();
        return "production".equalsIgnoreCase(mode) || "release".equalsIgnoreCase(mode);
    }
}
