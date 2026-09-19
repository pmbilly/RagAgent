package com.ragagent.storage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对照 Go {@code types.StorageBackendConfig}（internal/types/storagebackend.go L94-108）。
 * 全字段 omitempty；未知键容忍（jsonb 演进 + 裸种子行，§9「波 2 FAQ 补充」教训）。
 * access_key_id / secret_access_key 落库加密在仓储层序列化时处理（对照 Go Value()）。
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageConfig {

    @JsonProperty("mode") public String mode = "";
    @JsonProperty("endpoint") public String endpoint = "";
    @JsonProperty("region") public String region = "";
    @JsonProperty("access_key_id") public String accessKeyId = "";
    @JsonProperty("secret_access_key") public String secretAccessKey = "";
    @JsonProperty("bucket_name") public String bucketName = "";
    @JsonProperty("path_prefix") public String pathPrefix = "";
    @JsonProperty("app_id") public String appId = "";
    @JsonProperty("use_ssl") public boolean useSsl;
    @JsonProperty("force_path_style") public boolean forcePathStyle;
    @JsonProperty("use_temp_bucket") public boolean useTempBucket;
    @JsonProperty("temp_bucket_name") public String tempBucketName = "";
    @JsonProperty("temp_region") public String tempRegion = "";

    public StorageConfig copy() {
        StorageConfig c = new StorageConfig();
        c.mode = mode;
        c.endpoint = endpoint;
        c.region = region;
        c.accessKeyId = accessKeyId;
        c.secretAccessKey = secretAccessKey;
        c.bucketName = bucketName;
        c.pathPrefix = pathPrefix;
        c.appId = appId;
        c.useSsl = useSsl;
        c.forcePathStyle = forcePathStyle;
        c.useTempBucket = useTempBucket;
        c.tempBucketName = tempBucketName;
        c.tempRegion = tempRegion;
        return c;
    }
}
