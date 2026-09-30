package com.ragagent.knowledge.domain;


/** 知识库的对象存储配置（jsonb 列 {@code storage_config}）：凭据、桶、路径前缀与 S3 兼容开关。 */
public class KnowledgeBaseStorageConfig {

    private String secretId = "";
    private String secretKey = "";
    private String region = "";
    private String bucketName = "";
    private String appId = "";
    private String pathPrefix = "";
    private String provider = "";
    private String endpoint;
    private boolean useSsl;
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
