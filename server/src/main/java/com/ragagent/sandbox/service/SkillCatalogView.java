package com.ragagent.sandbox.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoTimeSerializer;
import com.ragagent.sandbox.domain.TenantSandboxConfigEntity;
import com.ragagent.sandbox.domain.TenantSkillCatalogEntity;
import com.ragagent.sandbox.domain.TenantSkillEntity;

/**
 * 对照 Go {@code service.SkillCatalogView / SkillCatalogInstallView}
 * （internal/application/service/tenant_skill_catalog.go L17-40）的响应投影。
 *
 * <p>Go <b>struct</b> 响应按字段声明序输出（§9 键序规则）：id,name,version?,description?,
 * bundle_sha256?,created_at,updated_at,installations；安装视图 skill_id,sandbox_config_id,
 * sandbox_config_name?,sandbox_type?,status,enabled,error?,bundle_sha256?,updated_at。
 * omitempty 全部逐字段挂 NON_EMPTY（{@code enabled} 无 omitempty——false 也输出，
 * 禁用类级 NON_DEFAULT）。</p>
 */
@JsonPropertyOrder({"id", "name", "version", "description", "bundle_sha256",
        "created_at", "updated_at", "installations"})
public record SkillCatalogView(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("version") @JsonInclude(JsonInclude.Include.NON_EMPTY) String version,
        @JsonProperty("description") @JsonInclude(JsonInclude.Include.NON_EMPTY) String description,
        @JsonProperty("bundle_sha256") @JsonInclude(JsonInclude.Include.NON_EMPTY) String bundleSha256,
        @JsonProperty("created_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime createdAt,
        @JsonProperty("updated_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime updatedAt,
        @JsonProperty("installations") List<SkillCatalogInstallView> installations) {

    /** installations 可变：ListCatalog 的"并入孤儿安装"分支要就地追加。 */
    public SkillCatalogView {
        installations = installations == null ? new ArrayList<>() : installations;
    }

    @JsonPropertyOrder({"skill_id", "sandbox_config_id", "sandbox_config_name", "sandbox_type",
            "status", "enabled", "error", "bundle_sha256", "updated_at"})
    public record SkillCatalogInstallView(
            @JsonProperty("skill_id") String skillId,
            @JsonProperty("sandbox_config_id") String sandboxConfigId,
            @JsonProperty("sandbox_config_name") @JsonInclude(JsonInclude.Include.NON_EMPTY) String sandboxConfigName,
            @JsonProperty("sandbox_type") @JsonInclude(JsonInclude.Include.NON_EMPTY) String sandboxType,
            @JsonProperty("status") String status,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("error") @JsonInclude(JsonInclude.Include.NON_EMPTY) String error,
            @JsonProperty("bundle_sha256") @JsonInclude(JsonInclude.Include.NON_EMPTY) String bundleSha256,
            @JsonProperty("updated_at") @JsonSerialize(using = GoTimeSerializer.class) OffsetDateTime updatedAt) {
    }

    /** 对照 {@code catalogView}。 */
    static SkillCatalogView catalogView(TenantSkillCatalogEntity cat,
            List<TenantSkillEntity> installs,
            java.util.Map<String, TenantSandboxConfigEntity> configByID) {
        List<SkillCatalogInstallView> views = new ArrayList<>(installs.size());
        for (TenantSkillEntity row : installs) {
            views.add(installView(row, configByID));
        }
        return new SkillCatalogView(cat.getId(), cat.getName(), cat.getVersion(),
                cat.getDescription(), cat.getBundleSha256(), cat.getCreatedAt(),
                cat.getUpdatedAt(), views);
    }

    /** 对照 {@code installView}。 */
    static SkillCatalogInstallView installView(TenantSkillEntity row,
            java.util.Map<String, TenantSandboxConfigEntity> configByID) {
        String configName = "";
        String sandboxType = "";
        TenantSandboxConfigEntity cfg = configByID.get(row.getSandboxConfigId());
        if (cfg != null) {
            configName = cfg.getName();
            sandboxType = cfg.getSandboxType();
        }
        return new SkillCatalogInstallView(row.getId(), row.getSandboxConfigId(), configName,
                sandboxType, row.getStatus(), row.isEnabled(), row.getError(),
                row.getBundleSha256(), row.getUpdatedAt());
    }
}
