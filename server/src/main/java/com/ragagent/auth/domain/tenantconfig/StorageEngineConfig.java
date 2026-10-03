package com.ragagent.auth.domain.tenantconfig;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 存储引擎配置段（8 个 provider 子结构）。
 *
 * <p>default_provider 恒输出（零值 ""）；8 个 provider 子结构
 * 为 null 时省略键。provider 子结构内部字段**全部恒输出**——
 * 对象一旦存在，所有键恒输出（含 "" 与 false），golden ct-kv-storage-* 钉住。</p>
 */
@JsonPropertyOrder({
        "default_provider", "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs"
})
public class StorageEngineConfig {

    @JsonProperty("default_provider")
    private String defaultProvider = "";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("local")
    private LocalEngineConfig local;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("minio")
    private MinioEngineConfig minio;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("cos")
    private CosEngineConfig cos;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("tos")
    private TosEngineConfig tos;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("s3")
    private S3EngineConfig s3;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("oss")
    private OssEngineConfig oss;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("ks3")
    private Ks3EngineConfig ks3;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("obs")
    private ObsEngineConfig obs;

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String v) { defaultProvider = v == null ? "" : v; }
    public LocalEngineConfig getLocal() { return local; }
    public void setLocal(LocalEngineConfig v) { local = v; }
    public MinioEngineConfig getMinio() { return minio; }
    public void setMinio(MinioEngineConfig v) { minio = v; }
    public CosEngineConfig getCos() { return cos; }
    public void setCos(CosEngineConfig v) { cos = v; }
    public TosEngineConfig getTos() { return tos; }
    public void setTos(TosEngineConfig v) { tos = v; }
    public S3EngineConfig getS3() { return s3; }
    public void setS3(S3EngineConfig v) { s3 = v; }
    public OssEngineConfig getOss() { return oss; }
    public void setOss(OssEngineConfig v) { oss = v; }
    public Ks3EngineConfig getKs3() { return ks3; }
    public void setKs3(Ks3EngineConfig v) { ks3 = v; }
    public ObsEngineConfig getObs() { return obs; }
    public void setObs(ObsEngineConfig v) { obs = v; }

    /** 对照 LocalEngineConfig。 */
    @JsonPropertyOrder({"path_prefix"})
    public static class LocalEngineConfig {
        @JsonProperty("path_prefix")
        private String pathPrefix = "";

        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
    }

    /** 对照 MinIOEngineConfig。 */
    @JsonPropertyOrder({"mode", "endpoint", "access_key_id", "secret_access_key",
            "bucket_name", "use_ssl", "path_prefix"})
    public static class MinioEngineConfig {
        @JsonProperty("mode")
        private String mode = "";
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("access_key_id")
        private String accessKeyId = "";
        @JsonProperty("secret_access_key")
        private String secretAccessKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("use_ssl")
        private boolean useSsl;
        @JsonProperty("path_prefix")
        private String pathPrefix = "";

        public String getMode() { return mode; }
        public void setMode(String v) { mode = v == null ? "" : v; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String v) { accessKeyId = v == null ? "" : v; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String v) { secretAccessKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public boolean isUseSsl() { return useSsl; }
        public void setUseSsl(boolean v) { useSsl = v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
    }

    /** 对照 COSEngineConfig。 */
    @JsonPropertyOrder({"secret_id", "secret_key", "region", "bucket_name",
            "app_id", "path_prefix", "temp_bucket_name", "temp_region"})
    public static class CosEngineConfig {
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
        @JsonProperty("temp_bucket_name")
        private String tempBucketName = "";
        @JsonProperty("temp_region")
        private String tempRegion = "";

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
        public String getTempBucketName() { return tempBucketName; }
        public void setTempBucketName(String v) { tempBucketName = v == null ? "" : v; }
        public String getTempRegion() { return tempRegion; }
        public void setTempRegion(String v) { tempRegion = v == null ? "" : v; }
    }

    /** 对照 TOSEngineConfig。 */
    @JsonPropertyOrder({"endpoint", "region", "access_key", "secret_key",
            "bucket_name", "path_prefix", "temp_bucket_name", "temp_region"})
    public static class TosEngineConfig {
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("region")
        private String region = "";
        @JsonProperty("access_key")
        private String accessKey = "";
        @JsonProperty("secret_key")
        private String secretKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("path_prefix")
        private String pathPrefix = "";
        @JsonProperty("temp_bucket_name")
        private String tempBucketName = "";
        @JsonProperty("temp_region")
        private String tempRegion = "";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v == null ? "" : v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { accessKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
        public String getTempBucketName() { return tempBucketName; }
        public void setTempBucketName(String v) { tempBucketName = v == null ? "" : v; }
        public String getTempRegion() { return tempRegion; }
        public void setTempRegion(String v) { tempRegion = v == null ? "" : v; }
    }

    /** 对照 S3EngineConfig。 */
    @JsonPropertyOrder({"endpoint", "region", "access_key", "secret_key",
            "bucket_name", "path_prefix", "use_ssl", "force_path_style"})
    public static class S3EngineConfig {
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("region")
        private String region = "";
        @JsonProperty("access_key")
        private String accessKey = "";
        @JsonProperty("secret_key")
        private String secretKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("path_prefix")
        private String pathPrefix = "";
        @JsonProperty("use_ssl")
        private boolean useSsl;
        @JsonProperty("force_path_style")
        private boolean forcePathStyle;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v == null ? "" : v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { accessKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
        public boolean isUseSsl() { return useSsl; }
        public void setUseSsl(boolean v) { useSsl = v; }
        public boolean isForcePathStyle() { return forcePathStyle; }
        public void setForcePathStyle(boolean v) { forcePathStyle = v; }
    }

    /** 对照 OSSEngineConfig。 */
    @JsonPropertyOrder({"endpoint", "region", "access_key", "secret_key",
            "bucket_name", "path_prefix", "use_temp_bucket", "temp_bucket_name", "temp_region"})
    public static class OssEngineConfig {
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("region")
        private String region = "";
        @JsonProperty("access_key")
        private String accessKey = "";
        @JsonProperty("secret_key")
        private String secretKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("path_prefix")
        private String pathPrefix = "";
        @JsonProperty("use_temp_bucket")
        private boolean useTempBucket;
        @JsonProperty("temp_bucket_name")
        private String tempBucketName = "";
        @JsonProperty("temp_region")
        private String tempRegion = "";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v == null ? "" : v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { accessKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
        public boolean isUseTempBucket() { return useTempBucket; }
        public void setUseTempBucket(boolean v) { useTempBucket = v; }
        public String getTempBucketName() { return tempBucketName; }
        public void setTempBucketName(String v) { tempBucketName = v == null ? "" : v; }
        public String getTempRegion() { return tempRegion; }
        public void setTempRegion(String v) { tempRegion = v == null ? "" : v; }
    }

    /** 对照 KS3EngineConfig。 */
    @JsonPropertyOrder({"endpoint", "region", "access_key", "secret_key",
            "bucket_name", "path_prefix"})
    public static class Ks3EngineConfig {
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("region")
        private String region = "";
        @JsonProperty("access_key")
        private String accessKey = "";
        @JsonProperty("secret_key")
        private String secretKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("path_prefix")
        private String pathPrefix = "";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v == null ? "" : v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { accessKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
    }

    /** 对照 OBSEngineConfig。 */
    @JsonPropertyOrder({"endpoint", "region", "access_key", "secret_key",
            "bucket_name", "path_prefix", "use_ssl"})
    public static class ObsEngineConfig {
        @JsonProperty("endpoint")
        private String endpoint = "";
        @JsonProperty("region")
        private String region = "";
        @JsonProperty("access_key")
        private String accessKey = "";
        @JsonProperty("secret_key")
        private String secretKey = "";
        @JsonProperty("bucket_name")
        private String bucketName = "";
        @JsonProperty("path_prefix")
        private String pathPrefix = "";
        @JsonProperty("use_ssl")
        private boolean useSsl;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { endpoint = v == null ? "" : v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { region = v == null ? "" : v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { accessKey = v == null ? "" : v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { secretKey = v == null ? "" : v; }
        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { bucketName = v == null ? "" : v; }
        public String getPathPrefix() { return pathPrefix; }
        public void setPathPrefix(String v) { pathPrefix = v == null ? "" : v; }
        public boolean isUseSsl() { return useSsl; }
        public void setUseSsl(boolean v) { useSsl = v; }
    }
}
