package com.ragagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ragagent.storage.fileserve.LocalFileContentService;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.WritableFileContentService;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * 存储写字节面（2026-09-24 批，对照 Go {@code file/local.go SaveBytes/DeleteFile} +
 * {@code resourceCatalogFileService.SaveBytes} + {@code resourceCatalog.Register/Bind}）。
 *
 * <p>写读回环：SaveBytes → 资源注册（resource:// 手柄）→ ResolvePath → 物理路径回读；
 * 绑定行落 resource_bindings；软删后解析失败；同物理路径重复注册复用手柄。</p>
 */
@SpringBootTest
class StorageWriteFaceContractTest {

    private static final long TENANT = 10002L;

    @Autowired
    private StorageFileResolver resolver;
    @Autowired
    private ResourceCatalogService catalog;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void saveBytesRegistersAndRoundTrips(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        WritableFileContentService svc = resolver.globalFileService(tmp.toString());
        byte[] data = "页面正文".getBytes(StandardCharsets.UTF_8);
        String ref = svc.saveBytes(data, TENANT, "web-abc.md", false);

        // resource:// 手柄 + 22 字符 handle（对照 BuildResourcePath）
        assertThat(ref).startsWith("resource://");
        assertThat(ref.substring("resource://".length())).hasSize(22);

        // 物理文件在 baseDir/{tenant}/exports/ 下，内容经服务回环
        //（resolvePath 返回的是 provider 作用域路径 local://…，不是文件系统路径）
        var resolved = catalog.resolvePath(ref);
        assertThat(resolved.error()).isFalse();
        assertThat(resolved.physicalPath()).contains("/exports/");
        String physicalFs = tmp.resolve(
                resolved.physicalPath().substring("local://".length())).toString();
        assertThat(Files.readAllBytes(Path.of(physicalFs))).isEqualTo(data);

        // 注册行字段（kind/mime/原名/尺寸/生命周期）
        var resource = catalog.resolve(ref).orElseThrow();
        assertThat(resource.getKind()).isEqualTo("file");
        assertThat(resource.getMimeType()).isEqualTo("text/markdown; charset=utf-8");
        assertThat(resource.getOriginalName()).isEqualTo("web-abc.md");
        assertThat(resource.getSize()).isEqualTo(data.length);
        assertThat(resource.getLifecycle()).isEqualTo("persistent");
        assertThat(resource.getContentHash()).hasSize(64);
        // 状态由 repo 默认 active
        assertThat(resource.getState()).isEqualTo("active");
    }

    @Test
    void samePhysicalPathReusesHandle(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        WritableFileContentService svc = resolver.globalFileService(tmp.toString());
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        String ref1 = svc.saveBytes(data, TENANT, "a.txt", false);
        String ref2 = svc.saveBytes(data, TENANT, "b.txt", false);
        assertThat(ref1).isNotEqualTo(ref2); // 唯一名 → 不同物理路径 → 不同手柄

        // 同一物理路径（local://… 形态，即 location hash 的输入）再注册 → 复用手柄
        String again = catalog.register(TENANT, ref1,
                new ResourceCatalogService.ResourceRegistration("file", "", "a.txt", 1, "", false));
        assertThat(again).isEqualTo(ref1);
    }

    @Test
    void deleteFileRemovesPhysicalAndMarksDeleted(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        WritableFileContentService svc = resolver.globalFileService(tmp.toString());
        String ref = svc.saveBytes("d".getBytes(StandardCharsets.UTF_8), TENANT, "d.md", false);
        String physical = tmp.resolve(catalog.resolvePath(ref).physicalPath()
                .substring("local://".length())).toString();
        assertThat(Files.exists(Path.of(physical))).isTrue();

        svc.deleteFile(ref);
        assertThat(Files.exists(Path.of(physical))).isFalse();
        // 资源软删 → 解析失败
        assertThat(catalog.resolve(ref)).isEmpty();
        assertThat(catalog.resolvePath(ref).error()).isTrue();
    }

    @Test
    void bindInsertsBindingRowForActiveResource(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        WritableFileContentService svc = resolver.globalFileService(tmp.toString());
        String ref = svc.saveBytes("b".getBytes(StandardCharsets.UTF_8), TENANT, "b.md", false);
        String resourceId = catalog.resolve(ref).orElseThrow().getId();

        catalog.bind(ref, "message", "msg-1", "web_page");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_bindings WHERE resource_id = ? AND owner_type = 'message' "
                        + "AND owner_id = 'msg-1' AND relation = 'web_page'",
                Integer.class, resourceId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void safeFileNameRejectsTraversalAndEmpty() throws java.io.IOException {
        assertThatThrownBy(() -> LocalFileContentService.safeFileName(""))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> LocalFileContentService.safeFileName(null))
                .isInstanceOf(java.io.IOException.class);
        // Go 语义：Base(Clean("../escape.md")) = "escape.md" 合法（写死在 exports 下，
        // 穿越不成立）；Base 含 ".." 才拒
        assertThat(LocalFileContentService.safeFileName("../escape.md")).isEqualTo("escape.md");
        assertThat(LocalFileContentService.safeFileName("sub/dir.md")).isEqualTo("dir.md");
        assertThatThrownBy(() -> LocalFileContentService.safeFileName("a..b.md"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> LocalFileContentService.safeFileName(".."))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void registerRejectsForeignOrMalformedPaths() {
        assertThatThrownBy(() -> catalog.register(0, "local://a", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.register(TENANT, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        // 无 provider scheme 的物理路径 → unsupported
        assertThatThrownBy(() -> catalog.register(TENANT, "plain/path.txt", null))
                .isInstanceOf(IllegalArgumentException.class);
        // 已经是 resource://（22 字符 handle）→ 原样返回
        String handle22 = "a".repeat(22);
        assertThat(catalog.register(TENANT, "resource://" + handle22, null))
                .isEqualTo("resource://" + handle22);
    }
}
