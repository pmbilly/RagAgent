package com.ragagent.storage.provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.UploadPartRequest;
import com.ragagent.common.security.SsrfGuard;

/**
 * 阿里云 OSS 后端。
 *
 * <p>语义：对象名 {@code {pathPrefix}{tenantId}/{knowledgeId}/{uuid}{ext}}
 * （pathPrefix 补尾斜杠）、SaveBytes 主桶 {@code {prefix}{tenantId}/exports/{uuid}{ext}} /
 * 临时桶 {@code exports/{tenantId}/{uuid}{ext}}、路径形态 {@code oss://{bucket}/{key}}、
 * 构造期确保桶存在（不存在则建，409 视为已存在）、预签名 24 小时、服务端 CopyObject、
 * 跨后端复制拒绝、取/删/签名时**按路径里的 bucket 选主/临时客户端**。</p>
 *
 * <p><b>大文件分片</b>：{@code >10MB}
 * 走 {@code initiateMultipartUpload → uploadPart ×N（3 并发）→ completeMultipartUpload}，
 * 失败时 {@code abortMultipartUpload} 清理（best-effort）；
 * 小文件仍走单次 {@code putObject}。错误前缀区分：分片 {@code failed to upload file to
 * OSS (multipart): …}、单次 {@code failed to upload file to OSS: …}。</p>
 */
public class OssFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(OssFileService.class);

    static final String SCHEME = "oss://";
    /** 预签名有效期：24h。 */
    static final long PRESIGN_TTL_MILLIS = 24L * 3600 * 1000;

    /** 分片阈值（照 Go {@code multipartThreshold = 10 * 1024 * 1024}）。 */
    static final long MULTIPART_THRESHOLD = 10L * 1024 * 1024;
    /** 每片大小（照 Go {@code uo.PartSize = 10 * 1024 * 1024}）。 */
    static final long PART_SIZE = 10L * 1024 * 1024;
    /** 分片并发度（照 Go {@code uo.ParallelNum = 3}）。 */
    static final int PARALLEL_NUM = 3;

    private final OSS client;
    private final OSS tempClient;
    private final String bucketName;
    private final String tempBucketName;
    private final String pathPrefix;
    /** 分片参数（测试可注入小值；生产即上面的 Go 常量）。 */
    private final long partSize;
    private final long multipartThreshold;

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
        this.partSize = PART_SIZE;
        this.multipartThreshold = MULTIPART_THRESHOLD;
    }

    /**
     * 测试口：注入客户端与分片参数（不建桶、不触网）。生产请用上面的公开构造器。
     */
    OssFileService(OSS client, OSS tempClient, String bucketName, String tempBucketName,
                   String pathPrefix, long partSize, long multipartThreshold) {
        this.client = client;
        this.tempClient = tempClient;
        this.bucketName = bucketName;
        this.tempBucketName = tempBucketName == null ? "" : tempBucketName.trim();
        String prefix = pathPrefix == null ? "" : pathPrefix.trim();
        this.pathPrefix = !prefix.isEmpty() && !prefix.endsWith("/") ? prefix + "/" : prefix;
        this.partSize = partSize;
        this.multipartThreshold = multipartThreshold;
    }

    private static OSS buildClient(String endpoint, String region, String accessKey,
                                   String secretKey) {
        // Java SDK 的 endpoint/region 语义：endpoint 已含 region 信息（如
        // https://oss-cn-hangzhou.aliyuncs.com），region 仅作备份与签名参考。
        // 裸域名（校验层允许）在 aliyun-sdk-java 下默认按 http:// 拨——AK/SK 与数据
        // 走明文；这里补齐 https 前缀。
        String normalized = endpoint == null ? "" : endpoint.trim();
        if (!normalized.isEmpty() && !normalized.contains("://")) {
            normalized = "https://" + normalized;
        }
        return new OSSClientBuilder().build(normalized, accessKey, secretKey);
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
            if (file.size() > multipartThreshold) {
                // 照 Go：>10MB 走 Uploader（10MB/片、3 并发）
                uploadMultipart(objectName, contentType, in);
            } else {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentType(contentType);
                client.putObject(bucketName, objectName, in, metadata);
            }
        } catch (MultipartFailure e) {
            throw new IllegalStateException(
                    "failed to upload file to OSS (multipart): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload file to OSS: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
        return SCHEME + bucketName + "/" + objectName;
    }

    /** 分片路径的失败标记（用于区分 {@code (multipart)} 错误前缀）。 */
    private static final class MultipartFailure extends RuntimeException {
        MultipartFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * {@code initiateMultipartUpload} →
     * {@code uploadPart}（10MB/片、**3 并发**、单遍读流）→ {@code completeMultipartUpload}；
     * 任一步失败都 best-effort {@code abortMultipartUpload} 后抛错。
     */
    private void uploadMultipart(String objectName, String contentType, InputStream in) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        String uploadId;
        try {
            InitiateMultipartUploadResult init = client.initiateMultipartUpload(
                    new InitiateMultipartUploadRequest(bucketName, objectName, metadata));
            uploadId = init.getUploadId();
        } catch (RuntimeException e) {
            throw new MultipartFailure("initiate: " + e.getMessage(), e);
        }

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(PARALLEL_NUM);
        try {
            List<java.util.concurrent.Future<PartETag>> futures = new ArrayList<>();
            byte[] buffer = new byte[(int) Math.min(partSize, Integer.MAX_VALUE)];
            int partNumber = 1;
            while (true) {
                int filled;
                try {
                    filled = readFully(in, buffer);
                } catch (java.io.IOException e) {
                    throw new MultipartFailure("read part " + partNumber + ": " + e.getMessage(), e);
                }
                if (filled <= 0) {
                    break;
                }
                byte[] payload = Arrays.copyOf(buffer, filled);
                final int number = partNumber;
                futures.add(pool.submit(() -> client.uploadPart(new UploadPartRequest(bucketName,
                        objectName, uploadId, number, new ByteArrayInputStream(payload),
                        payload.length)).getPartETag()));
                partNumber++;
            }
            List<PartETag> eTags = new ArrayList<>(futures.size());
            for (java.util.concurrent.Future<PartETag> future : futures) {
                try {
                    eTags.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MultipartFailure("interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new MultipartFailure("upload part: " + cause.getMessage(), cause);
                }
            }
            client.completeMultipartUpload(
                    new CompleteMultipartUploadRequest(bucketName, objectName, uploadId, eTags));
        } catch (MultipartFailure e) {
            abortQuietly(objectName, uploadId);
            throw e;
        } catch (RuntimeException e) {
            abortQuietly(objectName, uploadId);
            throw new MultipartFailure("complete: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    /** best-effort 清理（失败只记日志——照 Go SDK Uploader 的 abort 收尾姿态）。 */
    private void abortQuietly(String objectName, String uploadId) {
        try {
            client.abortMultipartUpload(
                    new AbortMultipartUploadRequest(bucketName, objectName, uploadId));
        } catch (RuntimeException e) {
            log.warn("failed to abort OSS multipart upload {}: {}", objectName, e.getMessage());
        }
    }

    /** 读满 buffer 或到 EOF（单遍读流；返回实际读到的字节数）。 */
    private static int readFully(InputStream in, byte[] buffer) throws java.io.IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
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
