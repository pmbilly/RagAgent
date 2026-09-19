package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * StorageConfig（对照 Go types/knowledgebase.go L372），gorm 列 cos_config，JSON 键 storage_config。
 * endpoint/use_ssl/force_path_style 带 omitempty；**Go 现状契约：secret_id/secret_key 原样外发不脱敏**。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "secret_id", "secret_key", "region", "bucket_name", "app_id",
        "path_prefix", "provider", "endpoint", "use_ssl", "force_path_style"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbStorageConfig {

    @JsonProperty("secret_id")
    private String secretId = "";
    @JsonProperty("secret_key")
    private String secretKey = "";
    @JsonProperty("region")
    private String region = "";
    @JsonProperty("bucket_name")
    private String bucketName = "";
    @JsonProperty("app_id")
    private String appId = "";
    @JsonProperty("path_prefix")
    private String pathPrefix = "";
    @JsonProperty("provider")
    private String provider = "";
    @JsonProperty("endpoint")
    private String endpoint;
    @JsonProperty("use_ssl")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean useSsl;
    @JsonProperty("force_path_style")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean forcePathStyle;

    public String getSecretId() { return secretId; }
    public void setSecretId(String v) { secretId = v == null ? "" : v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { region = v == null ? "" : v; }
    public String getBucketName() { return bucketName; }
    public void setBucketName(String v) { bucketName = v == null ? "" : v; }
    public String getAppId() { return appId; }
    public void setAppId(String v) { appId = v == null ? "" : v; }
    public String getPathPrefix() { return pathPrefix; }
    public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { endpoint = v; }
    public boolean isUseSsl() { return useSsl; }
    public void setUseSsl(boolean v) { useSsl = v; }
    public boolean isForcePathStyle() { return forcePathStyle; }
    public void setForcePathStyle(boolean v) { forcePathStyle = v; }
}
