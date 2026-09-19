package com.ragagent.sandbox.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.VolumeMountConfig}（internal/types/tenant.go L789-813）。
 *
 * <p>共享卷挂载进该租户的每个沙箱。当前用于租户安装的 skills，但 schema 刻意与
 * skill 无关。注意 {@code enabled} **无 omitempty**（恒输出），其余全 omitempty。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class VolumeMountConfig {

    /** 无 omitempty：恒输出（对照 Go tag `json:"enabled"`） */
    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("mount_path")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String mountPath = "";

    /** 目前 "e2b" 或 "cube" */
    @JsonProperty("provider")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String provider = "";

    @JsonProperty("volume_id")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String volumeId = "";

    @JsonProperty("volume_name")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String volumeName = "";

    /** sha256(provider + APIKey + APIURL)，用于探测后端/密钥切换 */
    @JsonProperty("volume_owner_fingerprint")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private String volumeOwnerFingerprint = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getMountPath() { return mountPath; }
    public void setMountPath(String v) { mountPath = v == null ? "" : v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String v) { volumeId = v == null ? "" : v; }
    public String getVolumeName() { return volumeName; }
    public void setVolumeName(String v) { volumeName = v == null ? "" : v; }
    public String getVolumeOwnerFingerprint() { return volumeOwnerFingerprint; }
    public void setVolumeOwnerFingerprint(String v) { volumeOwnerFingerprint = v == null ? "" : v; }

    public VolumeMountConfig copy() {
        VolumeMountConfig c = new VolumeMountConfig();
        c.enabled = enabled;
        c.mountPath = mountPath;
        c.provider = provider;
        c.volumeId = volumeId;
        c.volumeName = volumeName;
        c.volumeOwnerFingerprint = volumeOwnerFingerprint;
        return c;
    }
}
