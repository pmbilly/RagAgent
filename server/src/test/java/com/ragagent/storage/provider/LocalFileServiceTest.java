package com.ragagent.storage.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ragagent.auth.domain.tenantconfig.StorageEngineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本地后端 + 工厂 + 租户回退的契约测试（对照 Go local.go / factory.go / resolve_tenant.go）：
 * 路径布局、{@code local://} 形态、路径遍历拒绝、跨后端复制拒绝、缺省链与回退规则。
 */
class LocalFileServiceTest {

    private Path base;
    private LocalFileService service;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createTempDirectory("ragagent-local-fs-");
        service = new LocalFileService(base.toString(), "");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(base)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理尽力而为
                }
            });
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("SaveBytes → local://{tenant}/exports/{基名}_{nano}{ext}；读回一致；删除后不存在")
    void saveBytesRoundTrip() throws Exception {
        byte[] data = "hello 世界".getBytes(StandardCharsets.UTF_8);
        String path = service.saveBytes(data, 7L, "报告.csv", false);

        assertTrue(path.startsWith("local://7/exports/报告_"), path);
        assertTrue(path.endsWith(".csv"), path);
        assertEquals("7", String.valueOf(LocalFileService.parseTenantIdFromStoragePath(path)));

        assertArrayEquals(data, readAll(service.getFile(path)));
        assertEquals(path, service.getFileURL(path)); // 无 externalURL → 原样返回 provider 路径

        service.deleteFile(path);
        assertThrows(RuntimeException.class, () -> service.getFile(path));
    }

    @Test
    @DisplayName("SaveFile → local://{tenant}/{knowledge}/{nano}{ext}；文件名非法或路径越界一律拒绝")
    void saveFileLayoutAndSafety() throws Exception {
        byte[] content = "png-bytes".getBytes(StandardCharsets.UTF_8);
        String path = service.saveFile(new FileService.UploadFile("图 1.png", content.length,
                () -> new java.io.ByteArrayInputStream(content)), 42L, "kb-9");

        assertTrue(path.startsWith("local://42/kb-9/"), path);
        assertTrue(path.endsWith(".png"), path);

        // 路径遍历：baseDir 之外一律拒绝
        assertThrows(RuntimeException.class, () -> service.getFile("../../etc/passwd"));
        assertThrows(RuntimeException.class, () -> service.getFile(base.getParent().resolve("x").toString()));
        // 文件名非法（分隔符 / ..）
        assertThrows(IllegalArgumentException.class, () -> service.saveBytes(content, 1L, "../x.txt", false));
        assertThrows(IllegalArgumentException.class, () -> service.saveBytes(content, 1L, "a/b.txt", false));
    }

    @Test
    @DisplayName("CopyFile：真字节复制到新知识名下；非 local:// 源 → CrossBackendCopyException")
    void copyFileSemantics() throws Exception {
        byte[] data = "artifact".getBytes(StandardCharsets.UTF_8);
        String src = service.saveBytes(data, 7L, "a.txt", false);

        String copied = service.copyFile(src, 8L, "kb-2");
        assertTrue(copied.startsWith("local://8/kb-2/"), copied);
        assertArrayEquals(data, readAll(service.getFile(copied)));

        // 删源不影响副本
        service.deleteFile(src);
        assertArrayEquals(data, readAll(service.getFile(copied)));

        FileService.CrossBackendCopyException err = assertThrows(
                FileService.CrossBackendCopyException.class,
                () -> service.copyFile("s3://bucket/key.png", 8L, "kb-2"));
        assertTrue(err.getMessage().contains("s3://bucket/key.png"));
    }

    @Test
    @DisplayName("CheckConnectivity：缺目录/非目录都报错")
    void connectivity() throws Exception {
        service.checkConnectivity();
        Path file = Files.createFile(base.resolve("not-a-dir"));
        LocalFileService broken = new LocalFileService(file.toString(), "");
        assertThrows(RuntimeException.class, broken::checkConnectivity);
    }

    @Test
    @DisplayName("GetFileURL：配 externalURL 但未接预签名 → 记降级并返回 local:// 路径（照 Go 缺密钥路径）")
    void getFileURLWithoutSignerFallsBack() throws Exception {
        LocalFileService withUrl = new LocalFileService(base.toString(), "https://files.example.com/");
        String path = withUrl.saveBytes("x".getBytes(StandardCharsets.UTF_8), 7L, "b.txt", false);
        // 不注入 signer：与 Go 未配 SYSTEM_AES_KEY 时一致
        assertEquals(path, withUrl.getFileURL(path));
    }

    @Test
    @DisplayName("工厂：local 用入参根目录；default_provider 回落；待实现 provider 抛未实现；未知 provider 报错")
    void factoryRules() {
        StorageEngineConfig sec = new StorageEngineConfig();
        sec.setDefaultProvider("local");
        FileServiceFactory.Created created = FileServiceFactory.fromStorageConfig(
                "", sec, base.resolve("sub").toString());
        assertEquals("local", created.provider());
        assertTrue(((LocalFileService) created.service()).baseDir()
                .endsWith(Path.of("sub")));

        // path_prefix 叠加；越界前缀被忽略
        StorageEngineConfig withPrefix = new StorageEngineConfig();
        withPrefix.setLocal(new StorageEngineConfig.LocalEngineConfig());
        withPrefix.getLocal().setPathPrefix("t-1");
        LocalFileService prefixed = (LocalFileService) FileServiceFactory.fromStorageConfig(
                "local", withPrefix, base.toString()).service();
        assertTrue(prefixed.baseDir().endsWith(Path.of("t-1")));

        StorageEngineConfig escaping = new StorageEngineConfig();
        escaping.setLocal(new StorageEngineConfig.LocalEngineConfig());
        escaping.getLocal().setPathPrefix("../../escape");
        LocalFileService kept = (LocalFileService) FileServiceFactory.fromStorageConfig(
                "local", escaping, base.toString()).service();
        assertEquals(base.toAbsolutePath().normalize(), kept.baseDir());

        // 批次二已补齐：oss 无配置时是"不完整"（而非"未实现"）
        assertEquals("incomplete oss config", assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("oss", sec, base.toString()))
                .getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("nope", sec, base.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FileServiceFactory.fromStorageConfig("  ", null, base.toString()));
    }

    @Test
    @DisplayName("租户回退：解析失败且 provider == STORAGE_TYPE → 全局兜底；provider 不同 → ok=false")
    void tenantFallbackRules() {
        String globalType = TenantFileServiceResolver.globalStorageType();
        FileService global = service;
        TenantFileServiceResolver.TenantResolver throwing = (tenantId, backendId, provider, dir) -> {
            throw new IllegalStateException("tenant config broken");
        };

        TenantFileServiceResolver.Fallback ok = TenantFileServiceResolver.resolveWithFallback(
                "test", 7L, null, "", globalType, base.toString(), throwing, global);
        assertTrue(ok.ok());
        assertEquals(global, ok.service());
        assertEquals(globalType, ok.provider());

        String other = globalType.equals("s3") ? "local" : "s3";
        TenantFileServiceResolver.Fallback no = TenantFileServiceResolver.resolveWithFallback(
                "test", 7L, null, "", other, base.toString(), throwing, global);
        assertFalse(no.ok());
        assertTrue(no.service() == null);

        // 无租户配置且无解析器 → 走回退判定（provider 匹配则兜底）
        TenantFileServiceResolver.Fallback cfgless = TenantFileServiceResolver.resolveWithFallback(
                "test", 7L, null, "", globalType, base.toString(), null, global);
        assertTrue(cfgless.ok());
    }
}
