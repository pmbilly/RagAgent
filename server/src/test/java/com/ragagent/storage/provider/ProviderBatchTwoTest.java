package com.ragagent.storage.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批次二（oss/cos/tos）的离线契约测试（对照 Go oss.go / cos.go / tos.go）：
 * 路径解析、对象名拼接、遗留 URL 形态、跨后端拒绝，以及工厂对三个 provider 的校验文案。
 *
 * <p><b>为什么只测纯逻辑</b>：OSS/TOS 的构造函数会确保桶存在（真网络），工厂一旦拿到
 * 完整配置就会建客户端——CI 无凭据，因此这里锁住"拼错路径/放行越界 key/校验文案漂移"
 * 这类真正昂贵的错误；真连三家云的对象操作按 Go 的老规矩留给 dev 自检。</p>
 */
class ProviderBatchTwoTest {

    // ── OSS ──

    @Test
    @DisplayName("OSS 路径：oss://{bucket}/{key}；缺 bucket/key 或错 scheme 一律拒绝")
    void ossPathParsing() {
        String[] parsed = OssFileService.parseFilePath("oss://bk/7/kb/x.png");
        assertEquals("bk", parsed[0]);
        assertEquals("7/kb/x.png", parsed[1]);

        assertThrows(IllegalArgumentException.class,
                () -> OssFileService.parseFilePath("s3://bk/k"));
        assertThrows(IllegalArgumentException.class,
                () -> OssFileService.parseFilePath("oss://bk/"));
        assertThrows(IllegalArgumentException.class,
                () -> OssFileService.parseFilePath("oss://bk"));
        assertThrows(IllegalArgumentException.class,
                () -> OssFileService.parseFilePath(null));
    }

    // ── TOS ──

    @Test
    @DisplayName("TOS 路径：tos://{bucket}/{key}；joinObjectKey 逐段 trim、跳空、单斜杠连接")
    void tosPathAndJoin() {
        String[] parsed = TosFileService.parseFilePath("tos://bk/weknora/7/exports/a.csv");
        assertEquals("bk", parsed[0]);
        assertEquals("weknora/7/exports/a.csv", parsed[1]);
        assertThrows(IllegalArgumentException.class,
                () -> TosFileService.parseFilePath("oss://bk/k"));

        assertEquals("a/b/c", TosFileService.joinObjectKey("/a/", "b", "", "c"));
        assertEquals("weknora/7/exports/x.csv",
                TosFileService.joinObjectKey("weknora", "7", "exports", "x.csv"));
        assertEquals("", TosFileService.joinObjectKey("", "/", null));
    }

    // ── COS ──

    @Test
    @DisplayName("COS 路径：provider 形态取第三段；遗留桶 URL 去前缀；其它 provider scheme 明确拒绝")
    void cosPathParsing() {
        CosFileService svc = new CosFileService("bk-125", "ap-guangzhou", "id", "key",
                "weknora", "", "");
        assertEquals("weknora/7/kb/x.png",
                svc.parseObjectName("cos://bk-125/ap-guangzhou/weknora/7/kb/x.png"));
        assertEquals("legacy/1.png",
                svc.parseObjectName("https://bk-125.cos.ap-guangzhou.myqcloud.com/legacy/1.png"));
        // 三段不足 → 原样返回（照 Go 的 SplitN 兜底）
        assertEquals("onlybucket", svc.parseObjectName("cos://onlybucket"));

        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> svc.parseObjectName("oss://bk/key"));
        assertTrue(err.getMessage().contains("cos file service cannot resolve oss path"),
                err.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> svc.parseObjectName("tos://bk/key"));

        assertTrue(svc.bucketUrl().startsWith("https://bk-125.cos.ap-guangzhou.myqcloud.com"));
    }

    // ── 工厂校验（照 Go 的 incomplete 文案） ──

    @Test
    @DisplayName("工厂：oss/cos/tos 缺字段时抛 incomplete 文案；不再报未实现")
    void factoryValidatesBatchTwo() {
        StorageEngineConfig sec = new StorageEngineConfig();

        sec.setOss(new StorageEngineConfig.OssEngineConfig());
        assertEquals("incomplete oss config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("oss", sec, "/tmp")).getMessage());
        sec.getOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        sec.getOss().setRegion("cn-hangzhou");
        sec.getOss().setAccessKey("ak");
        sec.getOss().setSecretKey("sk");
        // 仍缺 bucket → 同一文案
        assertEquals("incomplete oss config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("oss", sec, "/tmp")).getMessage());

        sec.setCos(new StorageEngineConfig.CosEngineConfig());
        assertEquals("incomplete cos config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("cos", sec, "/tmp")).getMessage());
        sec.getCos().setSecretId("id");
        assertEquals("incomplete cos config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("cos", sec, "/tmp")).getMessage());

        sec.setTos(new StorageEngineConfig.TosEngineConfig());
        assertEquals("incomplete tos config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("tos", sec, "/tmp")).getMessage());

        // 批次二已补齐：不再有"未实现"的 provider
        assertTrue(FileServiceFactory.PENDING.isEmpty());
        assertTrue(FileServiceFactory.IMPLEMENTED.containsAll(
                java.util.Set.of("local", "s3", "minio", "obs", "ks3", "oss", "cos", "tos")));
        // 未知 provider 仍是明确报错（不是未实现异常）
        assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("ceph", sec, "/tmp"));
        assertFalse(FileServiceFactory.IMPLEMENTED.contains("ceph"));
    }
}
