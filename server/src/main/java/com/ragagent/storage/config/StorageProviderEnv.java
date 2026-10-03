package com.ragagent.storage.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储后端默认装配读取的 provider 环境变量族（进程级 env 快照）。
 *
 * <p><b>环境变量名保持原样</b>：env → 属性名走 Spring 松散绑定
 * （{@code MINIO_ACCESS_KEY_ID} → {@code minio.access-key-id}），部署侧 .env 不需要改，
 * 本类只把「裸 System.getenv + 手写 JSON」换成类型化绑定。</p>
 *
 * <p>字段一律 {@code String}（不用 {@code Boolean}/数字）：保留宽容语义——未设置、
 * 大小写不符、写错的布尔字面量都不该让绑定失败（{@code S3_USE_SSL} 只在恰为 "false" 时
 * 为假、{@code MINIO_USE_SSL} 只在恰为 "true" 时为真，其余值一律按「未设置」处理）。</p>
 *
 * <p><b>输出词汇＝落库面（camel）</b>：键名与 {@code dto/StorageConfig} 同族
 * （{@code accessKeyId}/{@code bucketName}/{@code pathPrefix}…），因为本投影的唯一消费者是
 * 「落一行 {@code storage_backends}」，而行的读写两侧认的就是这套 camel
 * （读侧 {@code StorageBackendService.configOf/serializeConfig} 忽略未知键——
 * 此前输出 snake 会被<b>静默丢弃</b>）。引擎面（snake，各 provider 段命名
 * 还不统一）由 {@code StorageFileResolver.renameConfigKeys} 单点派生，本类不再管。</p>
 *
 * <p>省略规则：空串整键省略，假值整键省略；键序＝下列
 * {@code writeConfig} 调用序。</p>
 */
public final class StorageProviderEnv {

    private StorageProviderEnv() {
    }

    /**
     * 一家 provider 的环境变量族：自称 provider 名（与 {@code STORAGE_TYPE} 取值同一命名空间），
     * 并把自己的非空项按落库键写进后端 config。
     */
    public interface ProviderEnvFamily {

        String provider();

        void writeConfig(ObjectNode config);
    }

    /** 写非空字符串（空串/未设置整键省略）。 */
    private static void putNonEmpty(ObjectNode config, String key, String value) {
        if (value != null && !value.isEmpty()) {
            config.put(key, value);
        }
    }

    /** 写真值（false 整键省略）。 */
    private static void putTrue(ObjectNode config, String key, boolean value) {
        if (value) {
            config.put(key, true);
        }
    }

    /** 去空白版取值（既有装配器对 STORAGE_TYPE / LOCAL_STORAGE_PATH_PREFIX 是 trim 过的）。 */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** {@code STORAGE_TYPE}：默认后端 provider；未设置（或全空白）→ {@code local}。 */
    @ConfigurationProperties(prefix = "storage")
    public record StorageType(String type) {

        public String provider() {
            String v = trim(type);
            return v.isEmpty() ? "local" : v;
        }
    }

    /** {@code LOCAL_*}：本地磁盘后端。 */
    @ConfigurationProperties(prefix = "local")
    public record Local(String storagePathPrefix) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "local";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "pathPrefix", trim(storagePathPrefix));
        }
    }

    /** {@code MINIO_*}。 */
    @ConfigurationProperties(prefix = "minio")
    public record Minio(
            String endpoint,
            String accessKeyId,
            String secretAccessKey,
            String bucketName,
            String pathPrefix,
            String useSsl) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "minio";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "mode", "remote");
            putNonEmpty(config, "endpoint", endpoint);
            putNonEmpty(config, "accessKeyId", accessKeyId);
            putNonEmpty(config, "secretAccessKey", secretAccessKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putTrue(config, "useSsl", "true".equalsIgnoreCase(useSsl));
        }
    }

    /** {@code COS_*}（腾讯云）：凭据键名是 SECRET_ID/SECRET_KEY，落库后叫 access_key_id/secret_access_key。 */
    @ConfigurationProperties(prefix = "cos")
    public record Cos(
            String region,
            String secretId,
            String secretKey,
            String bucketName,
            String pathPrefix,
            String appId,
            String tempBucketName,
            String tempRegion) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "cos";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "region", region);
            putNonEmpty(config, "accessKeyId", secretId);
            putNonEmpty(config, "secretAccessKey", secretKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putNonEmpty(config, "appId", appId);
            putNonEmpty(config, "tempBucketName", tempBucketName);
            putNonEmpty(config, "tempRegion", tempRegion);
        }
    }

    /** {@code TOS_*}（火山引擎）。 */
    @ConfigurationProperties(prefix = "tos")
    public record Tos(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucketName,
            String pathPrefix,
            String tempBucketName,
            String tempRegion) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "tos";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "endpoint", endpoint);
            putNonEmpty(config, "region", region);
            putNonEmpty(config, "accessKeyId", accessKey);
            putNonEmpty(config, "secretAccessKey", secretKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putNonEmpty(config, "tempBucketName", tempBucketName);
            putNonEmpty(config, "tempRegion", tempRegion);
        }
    }

    /** {@code S3_*}：{@code use_ssl} 缺省为真（Go：{@code !EqualFold(env, "false")}）。 */
    @ConfigurationProperties(prefix = "s3")
    public record S3(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucketName,
            String pathPrefix,
            String useSsl,
            String forcePathStyle) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "s3";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "endpoint", endpoint);
            putNonEmpty(config, "region", region);
            putNonEmpty(config, "accessKeyId", accessKey);
            putNonEmpty(config, "secretAccessKey", secretKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putTrue(config, "useSsl", !"false".equalsIgnoreCase(useSsl));
            putTrue(config, "forcePathStyle", "true".equalsIgnoreCase(forcePathStyle));
        }
    }

    /** {@code OSS_*}（阿里云）：配了临时 bucket 才写 {@code use_temp_bucket}。 */
    @ConfigurationProperties(prefix = "oss")
    public record Oss(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucketName,
            String pathPrefix,
            String tempBucketName,
            String tempRegion) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "oss";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "endpoint", endpoint);
            putNonEmpty(config, "region", region);
            putNonEmpty(config, "accessKeyId", accessKey);
            putNonEmpty(config, "secretAccessKey", secretKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putTrue(config, "useTempBucket", tempBucketName != null && !tempBucketName.isEmpty());
            putNonEmpty(config, "tempBucketName", tempBucketName);
            putNonEmpty(config, "tempRegion", tempRegion);
        }
    }

    /** {@code OBS_*}（华为云）：{@code use_ssl} 缺省为真。 */
    @ConfigurationProperties(prefix = "obs")
    public record Obs(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String bucketName,
            String pathPrefix,
            String useSsl) implements ProviderEnvFamily {

        @Override
        public String provider() {
            return "obs";
        }

        @Override
        public void writeConfig(ObjectNode config) {
            putNonEmpty(config, "endpoint", endpoint);
            putNonEmpty(config, "region", region);
            putNonEmpty(config, "accessKeyId", accessKey);
            putNonEmpty(config, "secretAccessKey", secretKey);
            putNonEmpty(config, "bucketName", bucketName);
            putNonEmpty(config, "pathPrefix", pathPrefix);
            putTrue(config, "useSsl", !"false".equalsIgnoreCase(useSsl));
        }
    }
}
