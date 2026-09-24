package com.ragagent.storage.provider;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.common.security.SsrfGuard;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3 协议族后端（<b>s3 / minio / obs / ks3</b>）——一次实现覆盖 Go 的四条路线：
 *
 * <table border="1">
 *   <tr><th>provider</th><th>Go 实现</th><th>本类</th></tr>
 *   <tr><td>s3</td><td>aws-sdk-go-v2（endpoint + path-style 推断）</td>
 *       <td>同款语义：{@code endpointOverride} 时按 {@code forcePathStyle ||
 *       !endpoint.contains("amazonaws.com")} 决定 path-style；非 AWS 端点放宽
 *       校验和协商（{@code WHEN_REQUIRED}）</td></tr>
 *   <tr><td>obs</td><td>aws-sdk-go-v2 + obsEndpointResolver（HostnameImmutable +
 *       UsePathStyle=true）</td><td>{@code endpointOverride} + {@code forcePathStyle(true)}</td></tr>
 *   <tr><td>ks3</td><td>ks3sdklib/aws-sdk-go（金山云的 AWS SDK 分支）</td>
 *       <td>同 obs：端点 + path-style</td></tr>
 *   <tr><td>minio</td><td>minio-go/v7（{@code Secure: useSSL}）</td>
 *       <td>S3 协议客户端 + path-style（MinIO 兼容 S3）——<b>差异备案</b>：presign
 *       细节与 minio-go 略有差别，功能等价</td></tr>
 * </table>
 *
 * <p>其余语义逐条照 {@code s3.go}：对象名
 * {@code {pathPrefix}{tenantId}/{knowledgeId}/{uuid}{ext}}（SaveBytes 走
 * {@code {pathPrefix}{tenantId}/exports/{uuid}{ext}}）、路径形态
 * {@code {scheme}{bucket}/{key}}、bucket 不匹配拒绝、{@code SafeObjectKey} 校验、
 * 服务端 CopyObject（数据不出云）、预签名下载 24 小时。</p>
 */
public class S3CompatibleFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(S3CompatibleFileService.class);

    /** 预签名有效期（对照 Go：{@code WithPresignExpires(24*time.Hour)}）。 */
    static final Duration PRESIGN_EXPIRY = Duration.ofHours(24);

    /** 构造参数（provider 名决定 scheme；其余照 Go 的各构造函数入参）。 */
    public record Config(String provider, String endpoint, String accessKey, String secretKey,
                         String bucketName, String region, String pathPrefix,
                         boolean forcePathStyle) {

        /** 校验 AK/SK 必须成对（照 Go 的 "must be provided together"）。 */
        public Config {
            provider = provider == null ? "" : provider.trim().toLowerCase();
            endpoint = endpoint == null ? "" : endpoint.trim();
            accessKey = accessKey == null ? "" : accessKey.trim();
            secretKey = secretKey == null ? "" : secretKey.trim();
            bucketName = bucketName == null ? "" : bucketName.trim();
            region = region == null || region.trim().isEmpty() ? "us-east-1" : region.trim();
            pathPrefix = pathPrefix == null ? "" : pathPrefix.trim();
            if (accessKey.isEmpty() != secretKey.isEmpty()) {
                throw new IllegalArgumentException(
                        "S3 access key and secret key must be provided together");
            }
        }
    }

    private final String scheme;
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucketName;
    private final String pathPrefix;

    public S3CompatibleFileService(Config cfg, SsrfGuard ssrfGuard) {
        if (!cfg.endpoint().isEmpty() && ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(cfg.endpoint());
        }
        this.scheme = cfg.provider() + "://";
        this.bucketName = cfg.bucketName();
        // 对照 Go：非空 pathPrefix 补尾斜杠
        this.pathPrefix = !cfg.pathPrefix().isEmpty() && !cfg.pathPrefix().endsWith("/")
                ? cfg.pathPrefix() + "/" : cfg.pathPrefix();
        this.client = buildClient(cfg);
        this.presigner = buildPresigner(cfg);
    }

    private static S3Client buildClient(Config cfg) {
        var builder = software.amazon.awssdk.services.s3.S3Client.builder()
                .region(Region.of(cfg.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(resolvePathStyle(cfg))
                        .build());
        if (!cfg.accessKey().isEmpty()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        }
        if (!cfg.endpoint().isEmpty()) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
            if (!cfg.endpoint().contains("amazonaws.com")) {
                // 对照 Go：S3 兼容服务常拒绝 SDK 的默认尾校验和协商
                builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
            }
        }
        return builder.build();
    }

    private static S3Presigner buildPresigner(Config cfg) {
        var builder = S3Presigner.builder()
                .region(Region.of(cfg.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(resolvePathStyle(cfg))
                        .build());
        if (!cfg.accessKey().isEmpty()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        }
        if (!cfg.endpoint().isEmpty()) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        return builder.build();
    }

    /** 对照 Go：{@code forcePathStyle || !endpoint.contains("amazonaws.com")}（无端点 → 非 path-style）。 */
    static boolean resolvePathStyle(Config cfg) {
        if (cfg.endpoint().isEmpty()) {
            return cfg.forcePathStyle();
        }
        return cfg.forcePathStyle() || !cfg.endpoint().contains("amazonaws.com");
    }

    @Override
    public void checkConnectivity() {
        // 对照 Go：10 秒超时；bucket 已配 → 只探测它；未配 → ListBuckets
        if (!bucketName.isEmpty()) {
            if (!bucketExists()) {
                throw new IllegalStateException("bucket \"" + bucketName + "\" does not exist");
            }
            return;
        }
        client.listBuckets();
    }

    /** 对照 Go {@code bucketExists}：HeadBucket，NotFound → false。 */
    public boolean bucketExists() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** 对照 Go {@code createBucket}。 */
    public void createBucket() {
        client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    }

    @Override
    public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
        String ext = StorageObjects.extensionOf(file.fileName());
        String objectName = pathPrefix + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        String contentType = StorageObjects.contentTypeByExt(ext);
        try (InputStream in = file.opener().get()) {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectName)
                            .contentLength(file.size())
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromInputStream(in, file.size()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload file to S3: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
        return scheme + bucketName + "/" + objectName;
    }

    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) {
        String safeName = safeFileNameOrThrow(fileName);
        String ext = StorageObjects.extensionOf(safeName);
        String objectName = pathPrefix + tenantId + "/exports/" + UUID.randomUUID() + ext;
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectName)
                            .contentLength((long) data.length)
                            .contentType(StorageObjects.contentTypeByExt(ext))
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload bytes to S3: " + e.getMessage(), e);
        }
        return scheme + bucketName + "/" + objectName;
    }

    @Override
    public InputStream getFile(String filePath) {
        String objectName = parseFilePath(filePath);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(bucketName).key(objectName).build());
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to get file from S3: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) {
        String objectName = parseFilePath(filePath);
        try {
            var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(PRESIGN_EXPIRY)
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucketName).key(objectName).build())
                    .build());
            return presigned.url().toString();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to generate presigned URL: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        String objectName = parseFilePath(filePath);
        try {
            client.deleteObject(r -> r.bucket(bucketName).key(objectName));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to delete file: " + e.getMessage(), e);
        }
    }

    @Override
    public String copyFile(String srcPath, long tenantId, String knowledgeId) {
        String srcKey;
        try {
            srcKey = parseFilePath(srcPath);
        } catch (RuntimeException e) {
            throw new FileService.CrossBackendCopyException(
                    "s3 copy rejected source \"" + srcPath + "\": " + e.getMessage());
        }
        String ext = StorageObjects.extensionOf(srcPath);
        String destKey = pathPrefix + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        try {
            // 用 (bucket, key) 重载：SDK 自己处理编码，避免 "/" 被转义成 %2F 破坏 bucket/key 切分
            client.copyObject(CopyObjectRequest.builder()
                    .destinationBucket(bucketName)
                    .destinationKey(destKey)
                    .sourceBucket(bucketName)
                    .sourceKey(srcKey)
                    .build());
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to copy file in S3: " + e.getMessage(), e);
        }
        String newPath = scheme + bucketName + "/" + destKey;
        log.info("Copied S3 object {} to {}", srcPath, newPath);
        return newPath;
    }

    /**
     * 对照 Go {@code parseS3FilePath}：必须 {@code {scheme}{bucket}/{key}}、
     * bucket 与本服务一致、key 过 {@code SafeObjectKey}。
     */
    String parseFilePath(String filePath) {
        String p = filePath == null ? "" : filePath;
        if (!p.startsWith(scheme)) {
            throw new IllegalArgumentException("invalid S3 file path: " + filePath);
        }
        String rest = p.substring(scheme.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("invalid S3 file path: " + filePath);
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        if (!bucket.equals(bucketName)) {
            throw new IllegalArgumentException(
                    "bucket mismatch in path: got " + bucket + ", want " + bucketName);
        }
        StorageObjects.safeObjectKey(key);
        return key;
    }

    /** 对照 Go 的 {@code utils.SafeFileName}（对象存储侧同样禁分隔符与 ..）。 */
    static String safeFileNameOrThrow(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.equals(".")) {
            throw new IllegalArgumentException("invalid file name: " + fileName);
        }
        return name;
    }

    /** 供装配/测试观察。 */
    public String objectScheme() {
        return scheme;
    }

    public String bucketName() {
        return bucketName;
    }

    public String pathPrefix() {
        return pathPrefix;
    }
}
