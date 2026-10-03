package com.ragagent.storage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 对象存储后端配置（{@code storage_backends.config} jsonb 的载荷类型）。
 * 键名即 Java 字段名（camelCase）；未知键容忍（jsonb 演进 + 历史行）。
 * accessKeyId / secretAccessKey 的密文在仓储层序列化写回时处理（加密与键名正交）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageConfig {

    public String mode = "";
    public String endpoint = "";
    public String region = "";
    public String accessKeyId = "";
    public String secretAccessKey = "";
    public String bucketName = "";
    public String pathPrefix = "";
    public String appId = "";
    public boolean useSsl;
    public boolean forcePathStyle;
    public boolean useTempBucket;
    public String tempBucketName = "";
    public String tempRegion = "";

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
