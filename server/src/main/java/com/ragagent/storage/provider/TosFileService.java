package com.ragagent.storage.provider;

import java.io.InputStream;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ragagent.common.security.SsrfGuard;
import com.volcengine.tos.TOSClientConfiguration;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.auth.StaticCredentials;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.model.bucket.CreateBucketV2Input;
import com.volcengine.tos.model.bucket.HeadBucketV2Input;
import com.volcengine.tos.model.object.CopyObjectV2Input;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.DeleteObjectInput;
import com.volcengine.tos.model.object.GetObjectV2Input;
import com.volcengine.tos.model.object.GetObjectV2Output;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.volcengine.tos.model.object.PutObjectInput;

/**
 * 火山引擎 TOS 后端。
 *
 * <p>语义：pathPrefix 去两端 {@code /}、对象名用
 * {@code joinTOSObjectKey}（各段 trim {@code /} 后跳过空段，再以 {@code /} 连接）——
 * 因此 {@code {prefix}/{tenantId}/{knowledgeId}/{uuid}{ext}}、SaveBytes 主桶
 * {@code {prefix}/{tenantId}/exports/{uuid}{ext}} / 临时桶
 * {@code exports/{tenantId}/{uuid}{ext}}、路径形态 {@code tos://{bucket}/{key}}、
 * 构造期确保桶存在（HeadBucket 404 → CreateBucket，409 视为已存在；临时桶用短命客户端
 * 按 tempRegion 探测）、服务端 CopyObject、预签名 24 小时、跨后端拒绝。</p>
 */
public class TosFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(TosFileService.class);

    static final String SCHEME = "tos://";
    /** 预签名有效期：24h。 */
    static final int PRESIGN_TTL_SECONDS = 24 * 3600;

    private final TOSV2 client;
    private final String bucketName;
    private final String tempBucketName;
    private final String pathPrefix;

    public TosFileService(String endpoint, String region, String accessKey, String secretKey,
                          String bucketName, String pathPrefix, String tempBucketName,
                          String tempRegion, SsrfGuard ssrfGuard) {
        if (endpoint != null && !endpoint.isEmpty() && ssrfGuard != null) {
            ssrfGuard.validateURLForSSRF(endpoint);
        }
        this.client = buildClient(endpoint, region, accessKey, secretKey);
        this.bucketName = bucketName;
        this.tempBucketName = tempBucketName == null ? "" : tempBucketName.trim();
        this.pathPrefix = trimSlashes(pathPrefix);

        ensureBucket(client, bucketName);
        if (!this.tempBucketName.isEmpty()) {
            String effectiveTempRegion = tempRegion == null || tempRegion.trim().isEmpty()
                    ? region : tempRegion;
            // 临时桶可能属于另一 region：用短命客户端探测（照 Go）
            TOSV2 tempProbe = buildClient(endpoint, effectiveTempRegion, accessKey, secretKey);
            ensureBucket(tempProbe, this.tempBucketName);
        }
    }

    private static TOSV2 buildClient(String endpoint, String region, String accessKey,
                                     String secretKey) {
        // Java SDK 的入口：TOSClientConfiguration.builder() + TOSV2ClientBuilder（照官方用法）
        TOSClientConfiguration config = TOSClientConfiguration.builder()
                .endpoint(endpoint)
                .region(region)
                .credentials(new StaticCredentials(accessKey, secretKey))
                .build();
        return new TOSV2ClientBuilder().build(config);
    }

    /** 对照 {@code ensureTOSBucket}：HeadBucket 404 → 建桶；建桶 409 → 视为已存在。 */
    static void ensureBucket(TOSV2 client, String bucket) {
        try {
            client.headBucket(new HeadBucketV2Input().setBucket(bucket));
            return;
        } catch (TosServerException e) {
            if (e.getStatusCode() != 404) {
                throw new IllegalStateException("failed to check TOS bucket: " + e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to check TOS bucket: " + e.getMessage(), e);
        }
        try {
            client.createBucket(new CreateBucketV2Input().setBucket(bucket));
        } catch (TosServerException e) {
            if (e.getStatusCode() == 409) {
                return;
            }
            throw new IllegalStateException("failed to create TOS bucket: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to create TOS bucket: " + e.getMessage(), e);
        }
    }

    /** 对照 {@code joinTOSObjectKey}：各段 trim 斜杠、跳过空段、以 {@code /} 连接。 */
    static String joinObjectKey(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = trimSlashes(part);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    @Override
    public void checkConnectivity() {
        try {
            client.headBucket(new HeadBucketV2Input().setBucket(bucketName));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to check TOS bucket: " + e.getMessage(), e);
        }
    }

    @Override
    public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
        String ext = StorageObjects.extensionOf(file.fileName());
        String objectName = joinObjectKey(pathPrefix, String.valueOf(tenantId), knowledgeId,
                UUID.randomUUID() + ext);
        String contentType = file.contentType().isEmpty()
                ? StorageObjects.contentTypeByExt(ext) : file.contentType();
        try (InputStream in = file.opener().get()) {
            client.putObject(new PutObjectInput()
                    .setBucket(bucketName)
                    .setKey(objectName)
                    .setOptions(new ObjectMetaRequestOptions().setContentType(contentType))
                    .setContentLength(file.size())
                    .setContent(in));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload file to TOS: " + e.getMessage(), e);
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
        String objectName = joinObjectKey(pathPrefix, String.valueOf(tenantId), "exports",
                UUID.randomUUID() + ext);
        if (temp && !tempBucketName.isEmpty()) {
            targetBucket = tempBucketName;
            objectName = joinObjectKey("exports", String.valueOf(tenantId),
                    UUID.randomUUID() + ext);
        }

        try {
            client.putObject(new PutObjectInput()
                    .setBucket(targetBucket)
                    .setKey(objectName)
                    .setOptions(new ObjectMetaRequestOptions()
                            .setContentType(StorageObjects.contentTypeByExt(ext)))
                    .setContentLength(data.length)
                    .setContent(new java.io.ByteArrayInputStream(data)));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload bytes to TOS: " + e.getMessage(), e);
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
            GetObjectV2Output output = client.getObject(new GetObjectV2Input()
                    .setBucket(bucket).setKey(key));
            return output.getContent();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to get file from TOS: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        String[] parsed = parseFilePath(filePath);
        String bucket = parsed[0];
        String key = parsed[1];
        StorageObjects.safeObjectKey(key);
        try {
            client.deleteObject(new DeleteObjectInput().setBucket(bucket).setKey(key));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to delete file from TOS: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) {
        String[] parsed = parseFilePath(filePath);
        String bucket = parsed[0];
        String key = parsed[1];
        StorageObjects.safeObjectKey(key);
        try {
            PreSignedURLOutput output = client.preSignedURL(new PreSignedURLInput()
                    .setHttpMethod(HttpMethod.GET)
                    .setBucket(bucket)
                    .setKey(key)
                    .setExpires(PRESIGN_TTL_SECONDS));
            return output.getSignedUrl();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "failed to generate TOS presigned URL: " + e.getMessage(), e);
        }
    }

    @Override
    public String copyFile(String srcPath, long tenantId, String knowledgeId) {
        String[] parsed;
        try {
            parsed = parseFilePath(srcPath);
        } catch (RuntimeException e) {
            throw new FileService.CrossBackendCopyException(
                    "tos copy rejected source \"" + srcPath + "\": " + e.getMessage());
        }
        String srcBucket = parsed[0];
        String srcKey = parsed[1];
        StorageObjects.safeObjectKey(srcKey);

        String ext = StorageObjects.extensionOf(srcPath);
        String destKey = joinObjectKey(pathPrefix, String.valueOf(tenantId), knowledgeId,
                UUID.randomUUID() + ext);
        try {
            client.copyObject(new CopyObjectV2Input()
                    .setBucket(bucketName)
                    .setKey(destKey)
                    .setSrcBucket(srcBucket)
                    .setSrcKey(srcKey));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to copy file in TOS: " + e.getMessage(), e);
        }
        String newPath = SCHEME + bucketName + "/" + destKey;
        log.info("Copied TOS object {} to {}", srcPath, newPath);
        return newPath;
    }

    /** 对照 {@code parseTOSFilePath}：{@code tos://{bucket}/{key}}。 */
    static String[] parseFilePath(String filePath) {
        String p = filePath == null ? "" : filePath;
        if (!p.startsWith(SCHEME)) {
            throw new IllegalArgumentException("invalid TOS file path: " + filePath);
        }
        String rest = p.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("invalid TOS file path: " + filePath);
        }
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }

    private static String trimSlashes(String s) {
        String out = s == null ? "" : s.trim();
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
