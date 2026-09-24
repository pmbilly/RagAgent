package com.ragagent.storage.provider;

import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;

/**
 * 腾讯云 COS 后端（对照 Go {@code cosFileService}，file/cos.go 全文 275 行）。
 *
 * <p>照抄的语义：路径形态 {@code cos://{bucket}/{region}/{objectKey}} 与
 * <b>遗留 URL 形态</b> {@code https://{bucket}.cos.{region}.myqcloud.com/{objectKey}}；
 * 其它 provider scheme 一律拒绝（{@code cos file service cannot resolve X path}）；
 * 对象名 {@code {pathPrefix}/{tenantId}/{knowledgeId}/{uuid}{ext}}（pathPrefix 默认
 * {@code weknora}，<b>不</b>补斜杠——照 Go）、SaveBytes 主桶
 * {@code {prefix}/{tenantId}/exports/{uuid}{ext}} / 临时桶
 * {@code exports/{tenantId}/{uuid}{ext}} 且返回<b>遗留桶 URL</b>（自动过期桶兼容旧格式）；
 * 服务端 CopyObject（源用无 scheme 的 host 形式）；预签名 24 小时。</p>
 */
public class CosFileService implements FileService {

    private static final Logger log = LoggerFactory.getLogger(CosFileService.class);

    static final String SCHEME = "cos://";
    /** 预签名有效期（对照 Go：24h）。 */
    static final long PRESIGN_TTL_MILLIS = 24L * 3600 * 1000;
    /** 其它 provider 的 scheme（照 Go 的拒绝清单）。 */
    static final String[] OTHER_SCHEMES = {
        "local://", "minio://", "s3://", "tos://", "oss://", "ks3://", "obs://"};

    private final COSClient client;
    private final COSClient tempClient;
    private final String bucketName;
    private final String region;
    private final String bucketUrl;
    private final String tempBucketUrl;
    private final String pathPrefix;

    public CosFileService(String bucketName, String region, String secretId, String secretKey,
                          String pathPrefix, String tempBucketName, String tempRegion) {
        this.bucketName = bucketName;
        this.region = region;
        this.bucketUrl = "https://" + bucketName + ".cos." + region + ".myqcloud.com/";
        this.client = buildClient(bucketName, region, secretId, secretKey);
        String temp = tempBucketName == null ? "" : tempBucketName.trim();
        if (!temp.isEmpty()) {
            String effectiveTempRegion = tempRegion == null || tempRegion.trim().isEmpty()
                    ? region : tempRegion;
            this.tempClient = buildClient(temp, effectiveTempRegion, secretId, secretKey);
            this.tempBucketUrl = "https://" + temp + ".cos." + effectiveTempRegion
                    + ".myqcloud.com/";
        } else {
            this.tempClient = null;
            this.tempBucketUrl = "";
        }
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix.trim();
    }

    private static COSClient buildClient(String bucket, String region, String secretId,
                                         String secretKey) {
        BasicCOSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig config = new ClientConfig(new Region(region));
        return new COSClient(credentials, config);
    }

    @Override
    public void checkConnectivity() {
        if (!client.doesBucketExist(bucketName)) {
            throw new IllegalStateException("bucket \"" + bucketName + "\" does not exist");
        }
    }

    @Override
    public String saveFile(UploadFile file, long tenantId, String knowledgeId) {
        String ext = StorageObjects.extensionOf(file.fileName());
        String objectName = pathPrefix + "/" + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        try (InputStream in = file.opener().get()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.size());
            metadata.setContentType(file.contentType().isEmpty()
                    ? StorageObjects.contentTypeByExt(ext) : file.contentType());
            client.putObject(new PutObjectRequest(bucketName, objectName, in, metadata));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload file to COS: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to open file: " + e.getMessage(), e);
        }
        return SCHEME + bucketName + "/" + region + "/" + objectName;
    }

    @Override
    public String saveBytes(byte[] data, long tenantId, String fileName, boolean temp) {
        String safeName = StorageObjects.safeFileName(fileName);
        String ext = StorageObjects.extensionOf(safeName);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType(StorageObjects.contentTypeByExt(ext));

        if (temp && tempClient != null) {
            String objectName = "exports/" + tenantId + "/" + UUID.randomUUID() + ext;
            try {
                tempClient.putObject(new PutObjectRequest(tempBucketNameOf(), objectName,
                        new java.io.ByteArrayInputStream(data), metadata));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "failed to upload bytes to COS temp bucket: " + e.getMessage(), e);
            }
            // 临时桶保持遗留 URL 形态（自动过期桶向后兼容）
            return tempBucketUrl + objectName;
        }

        String objectName = pathPrefix + "/" + tenantId + "/exports/" + UUID.randomUUID() + ext;
        try {
            client.putObject(new PutObjectRequest(bucketName, objectName,
                    new java.io.ByteArrayInputStream(data), metadata));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to upload bytes to COS: " + e.getMessage(), e);
        }
        return SCHEME + bucketName + "/" + region + "/" + objectName;
    }

    @Override
    public InputStream getFile(String filePath) {
        String objectName = parseObjectName(filePath);
        StorageObjects.safeObjectKey(objectName);
        try {
            COSObject object = client.getObject(bucketName, objectName);
            return object.getObjectContent();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to get file from COS: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String filePath) {
        String objectName = parseObjectName(filePath);
        StorageObjects.safeObjectKey(objectName);
        try {
            client.deleteObject(bucketName, objectName);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to delete file: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileURL(String filePath) {
        // 对照 Go：先判临时桶（按遗留桶 URL 前缀）
        if (tempClient != null && filePath != null && filePath.startsWith(tempBucketUrl)) {
            String objectName = filePath.substring(tempBucketUrl.length());
            StorageObjects.safeObjectKey(objectName);
            return presign(tempClient, tempBucketNameOf(), objectName);
        }
        String objectName = parseObjectName(filePath);
        StorageObjects.safeObjectKey(objectName);
        return presign(client, bucketName, objectName);
    }

    private static String presign(COSClient cosClient, String bucket, String objectName) {
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    bucket, objectName, HttpMethodName.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + PRESIGN_TTL_MILLIS));
            URL url = cosClient.generatePresignedUrl(request);
            return url.toString();
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to generate presigned URL: " + e.getMessage(), e);
        }
    }

    @Override
    public String copyFile(String srcPath, long tenantId, String knowledgeId) {
        String srcObjectKey;
        try {
            srcObjectKey = parseObjectName(srcPath);
        } catch (RuntimeException e) {
            throw new FileService.CrossBackendCopyException(
                    "cos copy rejected source \"" + srcPath + "\": " + e.getMessage());
        }
        StorageObjects.safeObjectKey(srcObjectKey);

        String ext = StorageObjects.extensionOf(srcPath);
        String destKey = pathPrefix + "/" + tenantId + "/" + knowledgeId + "/"
                + UUID.randomUUID() + ext;
        try {
            // 四参构造的参数序经字节码核对为 (源桶, 源 key, 目标桶, 目标 key)——
            // 与 Go 的 Object.Copy(ctx, destKey, sourceURL) 等价（源用 host+key 形式）
            client.copyObject(new CopyObjectRequest(bucketName, srcObjectKey, bucketName, destKey));
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to copy file in COS: " + e.getMessage(), e);
        }
        String newPath = SCHEME + bucketName + "/" + region + "/" + destKey;
        log.info("Copied COS object {} to {}", srcPath, newPath);
        return newPath;
    }

    /** 临时桶名（从桶 URL 反推；无临时桶时返空）。 */
    private String tempBucketNameOf() {
        return tempBucketUrl.isEmpty() ? "" : tempBucketUrl
                .replace("https://", "").split("\\.")[0];
    }

    /**
     * 对照 {@code parseCosObjectName}：其它 provider scheme → 明确拒绝；
     * {@code cos://{bucket}/{region}/{key}}（三段）取第三段；否则按遗留桶 URL 去前缀。
     */
    String parseObjectName(String filePath) {
        String p = filePath == null ? "" : filePath;
        for (String other : OTHER_SCHEMES) {
            if (p.startsWith(other)) {
                throw new IllegalArgumentException("cos file service cannot resolve "
                        + other.substring(0, other.length() - 3) + " path");
            }
        }
        if (p.startsWith(SCHEME)) {
            String rest = p.substring(SCHEME.length());
            String[] parts = rest.split("/", 3);
            if (parts.length == 3) {
                return parts[2];
            }
            return rest;
        }
        // 遗留形态：https://{bucket}.cos.{region}.myqcloud.com/{key}
        return p.startsWith(bucketUrl) ? p.substring(bucketUrl.length()) : p;
    }

    public String bucketUrl() {
        return bucketUrl;
    }
}
