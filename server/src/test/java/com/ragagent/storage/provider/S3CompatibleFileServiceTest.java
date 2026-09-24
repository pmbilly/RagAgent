package com.ragagent.storage.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import com.ragagent.common.security.SsrfGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S3 协议族后端的离线契约测试（对照 Go s3.go / fileutil.go / security.go）：
 * 路径解析、path-style 推断、对象名与内容类型规则、以及工厂对四个 provider 的校验。
 *
 * <p>真连 S3/MinIO 的路径不在 CI（与 Go 侧同样只靠 dev 自检）；这里锁住的是
 * <b>纯逻辑</b>——拼错路径/放行越界 key 这类错误正是最贵的。</p>
 */
class S3CompatibleFileServiceTest {

    /** 离线测试用 SSRF 闸：不校验端点（生产装配注入 Spring 管理的单例）。 */
    private static final SsrfGuard NOOP_GUARD = new SsrfGuard() {
        @Override
        public void validateURLForSSRF(String rawURL) {
            // 测试环境无 DNS；端点策略由 SsrfGuardTest 单测覆盖
        }
    };

    private static S3CompatibleFileService service(String provider, String bucket, String prefix,
                                                    boolean forcePathStyle) {
        return new S3CompatibleFileService(new S3CompatibleFileService.Config(
                provider, "", "ak", "sk", bucket, "cn-north-1", prefix, forcePathStyle), null);
    }

    @Test
    @DisplayName("parseFilePath：scheme/bucket/key 三段校验；bucket 不符与越界 key 一律拒绝")
    void parseFilePathRules() {
        S3CompatibleFileService svc = service("s3", "my-bucket", "", false);

        assertEquals("7/kb/x.png", svc.parseFilePath("s3://my-bucket/7/kb/x.png"));
        assertThrows(IllegalArgumentException.class, () -> svc.parseFilePath("minio://my-bucket/k"));
        assertThrows(IllegalArgumentException.class, () -> svc.parseFilePath("s3://other/k"));
        assertThrows(IllegalArgumentException.class, () -> svc.parseFilePath("s3://my-bucket/"));
        assertThrows(IllegalArgumentException.class, () -> svc.parseFilePath("s3://my-bucket"));
        assertThrows(IllegalArgumentException.class, () -> svc.parseFilePath("s3://my-bucket/../x"));
        // scheme 决定一切：obs 的服务只认 obs://
        assertEquals("k", service("obs", "b", "", true).parseFilePath("obs://b/k"));
    }

    @Test
    @DisplayName("path-style 推断照 Go：forcePathStyle 或非 amazonaws 端点 → path-style")
    void pathStyleInference() {
        S3CompatibleFileService.Config aws = new S3CompatibleFileService.Config(
                "s3", "https://s3.amazonaws.com", "ak", "sk", "b", "us-east-1", "", false);
        assertFalse(S3CompatibleFileService.resolvePathStyle(aws));

        S3CompatibleFileService.Config custom = new S3CompatibleFileService.Config(
                "minio", "http://minio:9000", "ak", "sk", "b", "us-east-1", "", false);
        assertTrue(S3CompatibleFileService.resolvePathStyle(custom));

        S3CompatibleFileService.Config forced = new S3CompatibleFileService.Config(
                "s3", "https://s3.amazonaws.com", "ak", "sk", "b", "us-east-1", "", true);
        assertTrue(S3CompatibleFileService.resolvePathStyle(forced));

        S3CompatibleFileService.Config noEndpoint = new S3CompatibleFileService.Config(
                "s3", "", "ak", "sk", "b", "us-east-1", "", false);
        assertFalse(S3CompatibleFileService.resolvePathStyle(noEndpoint));
    }

    @Test
    @DisplayName("Config：AK/SK 必须成对；pathPrefix 补尾斜杠；scheme = provider://")
    void configAndScheme() {
        assertThrows(IllegalArgumentException.class, () -> new S3CompatibleFileService.Config(
                "s3", "", "ak", "", "b", "r", "", false));

        S3CompatibleFileService svc = service("ks3", "b", "weknora", true);
        assertEquals("weknora/", svc.pathPrefix());
        assertEquals("ks3://", svc.objectScheme());
    }

    @Test
    @DisplayName("SaveBytes 的文件名安全校验：分隔符与 .. 拒绝")
    void saveBytesFileNameGuard() {
        assertThrows(IllegalArgumentException.class,
                () -> S3CompatibleFileService.safeFileNameOrThrow("../x.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> S3CompatibleFileService.safeFileNameOrThrow("a/b.txt"));
        assertEquals("ok.txt", S3CompatibleFileService.safeFileNameOrThrow("ok.txt"));
    }

    @Test
    @DisplayName("对象存储 helper：SafeObjectKey / 主动内容降级 / 扩展名与 MIME 表")
    void objectHelpers() {
        StorageObjects.safeObjectKey("a/b/c.png");
        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeObjectKey(""));
        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeObjectKey("a/../b"));

        assertTrue(StorageObjects.isActiveBrowserContentExt(".SVG"));
        assertTrue(StorageObjects.isActiveBrowserContentExt(".html"));
        assertFalse(StorageObjects.isActiveBrowserContentExt(".png"));
        // 主动内容一律降级为 octet-stream（防存储型 XSS）
        assertEquals("application/octet-stream", StorageObjects.contentTypeByExt(".svg"));
        assertEquals("application/octet-stream", StorageObjects.contentTypeByExt(".js"));
        assertEquals("image/png", StorageObjects.contentTypeByExt(".png"));
        assertEquals("text/csv; charset=utf-8", StorageObjects.contentTypeByExt(".csv"));
        assertEquals("application/octet-stream", StorageObjects.contentTypeByExt(".unknown"));

        assertEquals(".png", StorageObjects.extensionOf("a/b.png"));
        assertEquals("", StorageObjects.extensionOf("a/b"));
        assertEquals("", StorageObjects.extensionOf(null));
    }

    @Test
    @DisplayName("工厂：四个 S3 族 provider 的配置校验文案与默认前缀照 Go")
    void factoryValidatesS3Family() {
        StorageEngineConfig sec = new StorageEngineConfig();
        sec.setS3(new StorageEngineConfig.S3EngineConfig());
        sec.getS3().setBucketName("b");
        sec.getS3().setRegion("cn-north-1");
        // 只给 AK 不给 SK → 成对校验失败（Go 的 incomplete s3 config）
        sec.getS3().setAccessKey("ak");
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("s3", sec, "/tmp"));
        assertEquals("incomplete s3 config", err.getMessage());

        sec.getS3().setSecretKey("sk");
        FileServiceFactory.Created created = FileServiceFactory.fromStorageConfig("s3", sec, "/tmp");
        assertEquals("s3", created.provider());
        assertEquals("weknora/", ((S3CompatibleFileService) created.service()).pathPrefix());

        StorageEngineConfig obsSec = new StorageEngineConfig();
        obsSec.setObs(new StorageEngineConfig.ObsEngineConfig());
        obsSec.getObs().setEndpoint("https://obs.cn-north-4.myhuaweicloud.com");
        obsSec.getObs().setRegion("cn-north-4");
        obsSec.getObs().setAccessKey("ak");
        obsSec.getObs().setSecretKey("sk");
        obsSec.getObs().setBucketName("bk");
        FileServiceFactory.Created obs = FileServiceFactory.fromStorageConfig(
                "obs", obsSec, "/tmp", NOOP_GUARD);
        assertEquals("obs", obs.provider());
        assertEquals("obs://", ((S3CompatibleFileService) obs.service()).objectScheme());

        StorageEngineConfig ks3Sec = new StorageEngineConfig();
        ks3Sec.setKs3(new StorageEngineConfig.Ks3EngineConfig());
        assertEquals("incomplete ks3 config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("ks3", ks3Sec, "/tmp")).getMessage());

        // minio remote 模式：字段齐 → 用 use_ssl 组 scheme
        StorageEngineConfig minioSec = new StorageEngineConfig();
        minioSec.setMinio(new StorageEngineConfig.MinioEngineConfig());
        minioSec.getMinio().setMode("remote");
        minioSec.getMinio().setEndpoint("minio:9000");
        minioSec.getMinio().setAccessKeyId("ak");
        minioSec.getMinio().setSecretAccessKey("sk");
        minioSec.getMinio().setBucketName("bk");
        minioSec.getMinio().setUseSsl(true);
        FileServiceFactory.Created minio = FileServiceFactory.fromStorageConfig(
                "minio", minioSec, "/tmp", NOOP_GUARD);
        assertEquals("minio", minio.provider());
        assertEquals("minio://", ((S3CompatibleFileService) minio.service()).objectScheme());

        // 批次二已补齐：oss 无自己的配置段时是"不完整"
        assertEquals("incomplete oss config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("oss", minioSec, "/tmp")).getMessage());
    }
}
