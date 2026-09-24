package com.ragagent.storage.provider;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.ragagent.common.security.SsrfGuard;

/**
 * 阿里云 OSS 后端（对照 Go {@code ossFileService}，file/oss.go 全文 366 行）。
 *
 * <p>照抄的语义：对象名 {@code {pathPrefix}{tenantId}/{knowledgeId}/{uuid}{ext}}
 * （pathPrefix 补尾斜杠）、SaveBytes 主桶 {@code {prefix}{tenantId}/exports/{uuid}{ext}} /
 * 临时桶 {@code exports/{tenantId}/{uuid}{ext}}、路径形态 {@code oss://{bucket}/{key}}、
 * 构造期确保桶存在（不存在则建，409 视为已存在）、预签名 24 小时、服务端 CopyObject、
 * 跨后端复制拒绝、取/删/签名时**按路径里的 bucket 选主/临时客户端**。</p>
 *
 * <p><b>与 Go 的一处差异（备案）</b>：Go 对 &gt;10MB 的上传走 SDK 的并发分片 Uploader
 * （10MB/片、3 并发）；Java 侧先用单次 PutObject（SDK 流式 + 重试）。大文件分片留待
 * 需要时补（不影响正确性，只影响大文件上传耗时）。</p>
 */
public class OssFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(OssFileService.class);

    static final String SCHEME = "oss://";
    /** 预签名有效期（对照 Go：{@code oss.PresignExpires(24*time.Hour)}）。 */
    static final long PRESIGN_TTL_MILLIS = 24L * 3600 * 1000;

    private final OSS client;
    private final OSS tempClient;
    private final String bucketName;
    private final String tempBucketName;
    private final String pathPrefix;

    public OssFileService(String endpoint, String region, String accessKey, String secretKey,
                          String bucketName, String pathPrefix, String tempBucketName,
                          String tempRegion, SsrfGuard ssrfGuard) {
        if (endpoint != null && !endpoint.isEmpty() && ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(endpoint);
        }
        this.bucketName = bucketName;
        this.tempBucketName = tempBucketName == null ? "" : tempBucketName.trim();
        this.client = buildClient(endpoint, region, accessKey, secretKey);
        if (!this.tempBucketName.isEmpty()) {
            String effectiveTempRegion = tempRegion == null || tempRegion.trim().isEmpty()
                    ? region : tempRegion;
            this.tempClient = buildClient(endpoint, effectiveTempRegion, accessKey, secretKey);
        } else {
            this.tempClient = null;
        }
        String prefix = pathPrefix == null ? "" : pathPrefix.trim();
        this.pathPrefix = !prefix.isEmpty() && !prefix.endsWith("/") ? prefix + "/" : prefix;

        ensureBucket(client, bucketName);
        if (tempClient != null) {
            ensureBucket(tempClient, this.tempBucketName);
        }
    }

    private static OSS buildClient(String endpoint, String region, String accessKey,
                                   String secretKey) {
        // Java SDK 的 endpoint/region 语义：endpoint 已含 region 信息（如
        // https://oss-cn-hangzhou.aliyuncs.com），region 仅作备份与签名参考
        return new OSSClientBuilder().build(endpoint, accessKey, secretKey);
    }

    /** 对照 {@code ossEnsureBucket}：不存在则建；409（并发建/已存在）视为成功。 */
    static void ensureBucket(OSS client, String bucket) {
        try {
            if (Boolean.TRUE.equals(client.doesBucketExist(bucket))) {
                return;
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to check OSS bucket: " + e.getMessage(), e);
        }
        try {
            client.createBucket(bucket);
        } catch (OSSException e) {
            // 409 情形用错误码判定（Java SDK 的 OSSException 不暴露 HTTP 状态码）
            String code = e.getErrorCode();
            if ("BucketAlreadyExists".equals(code) || "BucketAlreadyOwnedByYou".equals(code)) {
                return;
            }
            throw new IllegalStateException("failed to create OSS bucket: " + e.getMessage(), e);
        }
    }

    @Override
    public void checkConnectivity() {
        try {
            if (!Boolean.TRUE.equals(client.doesBucketExist(bucketName))) {
                throw new IllegalStateException("bucket \"" + bucketName + "\" does not exist");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to check OSS bucket: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
        String ext = StorageObjects.extensionOf(file.fileName());
        String objectName = pathPrefix + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        String contentType = file.contentType().isEmpty()
                ? StorageObjects.contentTypeByExt(ext) : file.contentType();
        try (InputStream in = file.opener().get()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            client.putObject(bucketName, objectName, in, metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload file to OSS: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
        return SCHEME + bucketName + "/" + objectName;
    }

    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) {
        String safeName = StorageObjects.safeFileName(fileName);
        String ext = StorageObjects.extensionOf(safeName);

        String targetBucket = bucketName;
        OSS targetClient = client;
        String objectName = pathPrefix + tenantId + "/exports/" + UUID.randomUUID() + ext;
        if (temp && tempClient != null) {
            targetBucket = tempBucketName;
            targetClient = tempClient;
            objectName = "exports/" + tenantId + "/" + UUID.randomUUID() + ext;
        }

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(StorageObjects.contentTypeByExt(ext));
        metadata.setContentLength(data.length);
        try {
            targetClient.putObject(targetBucket, objectName, new java.io.ByteArrayInputStream(data),
                    metadata);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload bytes to OSS: " + e.getMessage(), e);
        }
        return SCHEME + targetBucket + "/" + objectName;
    }

    @Override
    public InputStream getFile(String filePath) {
        String[] parsed = parseFilePath(filePath);
        String bucket = parsed[0];
        String key = parsed[1];
        StorageObjects.safeObjectKey(key);
        try {
            return clientFor(bucket).getObject(bucket, key).getObjectContent();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to get file from OSS: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        String[] parsed = parseFilePath(filePath);
        String bucket = parsed[0];
        String key = parsed[1];
        StorageObjects.safeObjectKey(key);
        try {
            clientFor(bucket).deleteObject(bucket, key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to delete file from OSS: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) {
        String[] parsed = parseFilePath(filePath);
        String bucket = parsed[0];
        String key = parsed[1];
        StorageObjects.safeObjectKey(key);
        try {
            URL url = clientFor(bucket).generatePresignedUrl(bucket, key,
                    new Date(System.currentTimeMillis() + PRESIGN_TTL_MILLIS));
            return url.toString();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "failed to generate OSS presigned URL: " + e.getMessage(), e);
        }
    }

    @Override
    public String copyFile(String srcPath, long tenantId, String knowledgeId) {
        String[] parsed;
        try {
            parsed = parseFilePath(srcPath);
        } catch (RuntimeException e) {
            throw new FileService.CrossBackendCopyException(
                    "oss copy rejected source \"" + srcPath + "\": " + e.getMessage());
        }
        String srcBucket = parsed[0];
        String srcKey = parsed[1];
        StorageObjects.safeObjectKey(srcKey);

        String ext = StorageObjects.extensionOf(srcPath);
        String destKey = pathPrefix + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        try {
            client.copyObject(bucketName, destKey, srcBucket, srcKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to copy file in OSS: " + e.getMessage(), e);
        }
        String newPath = SCHEME + bucketName + "/" + destKey;
        log.info("Copied OSS object {} to {}", srcPath, newPath);
        return newPath;
    }

    private OSS clientFor(String bucket) {
        return tempClient != null && tempBucketName.equals(bucket) ? tempClient : client;
    }

    /** 对照 {@code parseOssFilePath}：{@code oss://{bucket}/{key}}（不含 bucket 一致性校验）。 */
    static String[] parseFilePath(String filePath) {
        String p = filePath == null ? "" : filePath;
        if (!p.startsWith(SCHEME)) {
            throw new IllegalArgumentException("invalid OSS file path: " + filePath);
        }
        String rest = p.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("invalid OSS file path: " + filePath);
        }
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }
}
