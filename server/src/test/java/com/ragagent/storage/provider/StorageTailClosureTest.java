package com.ragagent.storage.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.knowledge.service.LocalStorageService;
import com.ragagent.knowledge.service.TenantFileStorage;
import com.ragagent.sandbox.service.LocalSkillBundleStore;
import com.ragagent.sandbox.service.TenantSkillBundleStore;
import com.ragagent.storage.fileserve.StorageFileResolver;

/**
 * 存储收尾批（A3-3 尾）的离线契约：{@code SafeFileName} 的 Go 语义、导出字节的三态、
 * 以及租户感知 skill 归档存储的分流。
 *
 * <p>云分支只覆盖"解析失败"路径（失败要抛 / 不给导出）——真发请求要凭据，属部署态。</p>
 */
class StorageTailClosureTest {

    // ── 1. SafeFileName：Go 是 Base(Clean(name))，目录部分被丢弃而不是拒绝 ──

    @Test
    @DisplayName("SafeFileName 照 Go：取 basename（skill 归档/FAQ 导出的路径形 key 依赖它）")
    void safeFileNameTakesBasename() {
        assertEquals("x.zip", StorageObjects.safeFileName("tenant-skills/catalog/x.zip"));
        assertEquals("c.txt", StorageObjects.safeFileName("a/b/../c.txt"));
        assertEquals("passwd", StorageObjects.safeFileName("../../etc/passwd"));
        assertEquals("report.pdf", StorageObjects.safeFileName("report.pdf"));

        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeFileName(""));
        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeFileName(null));
        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeFileName(".."));
        assertThrows(IllegalArgumentException.class, () -> StorageObjects.safeFileName("a/x..y"));
        assertThrows(IllegalArgumentException.class,
                () -> StorageObjects.safeFileName("a".repeat(256) + ".txt"));
    }

    // ── 2. 导出字节三态 ──

    @Test
    @DisplayName("导出：本地租户 handled=false（走既有落盘）；云租户解析失败 handled=true 且不给 URL")
    void exportTriState(@TempDir Path dir) {
        byte[] csv = "a,b\n".getBytes(StandardCharsets.UTF_8);

        // 本地：交给调用方（FaqService 自己写盘并回 local:// 引用）
        TenantFileStorage localOnly = new TenantFileStorage(new LocalStorageService(dir.toString()),
                new StorageFileResolver(null, null), tenantService(null), dir.toString());
        TenantFileStorage.Exported exported =
                localOnly.saveExportedBytesToUrl(7L, "faq.csv", csv, true);
        assertFalse(exported.handled());
        assertNull(exported.url());

        // 云：配置不完备 → 已接管（handled=true）但给不出 URL（照 Go 的空串），不落本地
        Tenant broken = new Tenant();
        broken.setId(7L);
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("default_provider", "cos");
        broken.setStorageEngineConfig(sec);
        TenantFileStorage cloudBroken = new TenantFileStorage(new LocalStorageService(dir.toString()),
                new StorageFileResolver(null, null), tenantService(broken), dir.toString());
        TenantFileStorage.Exported failed =
                cloudBroken.saveExportedBytesToUrl(7L, "faq.csv", csv, true);
        assertTrue(failed.handled());
        assertNull(failed.url());
        assertFalse(Files.exists(dir.resolve("7/exports")));
    }

    // ── 3. skill 归档存储分流 ──

    @Test
    @DisplayName("skill 归档：本地租户走既有 local:// 布局；云引用在租户解析失败时抛（不得当空内容）")
    void skillBundleRouting(@TempDir Path dir) {
        LocalSkillBundleStore local = new LocalSkillBundleStore(dir.toString());
        TenantService noTenant = tenantService(null);
        TenantSkillBundleStore store = new TenantSkillBundleStore(local,
                new StorageFileResolver(null, null), noTenant, dir.toString());

        byte[] archive = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        String ref = store.save(7L, "tenant-skills/catalog/c-1.zip", archive);
        assertEquals("local://7/tenant-skills/catalog/c-1.zip", ref);
        assertEquals("zip-bytes", new String(store.load(7L, ref), StandardCharsets.UTF_8));
        store.delete(7L, ref);
        assertFalse(Files.exists(dir.resolve("7/tenant-skills/catalog/c-1.zip")));

        // 云引用 + 解析不出租户 → 抛（对照 Go：GetFile 错误让 trySkillBundle 报"不可用"）
        assertThrows(IllegalStateException.class,
                () -> store.load(7L, "cos://bk-125/ap-guangzhou/a/b.zip"));
        // 删除是 best-effort：不抛
        store.delete(7L, "cos://bk-125/ap-guangzhou/a/b.zip");
    }

    private static TenantService tenantService(Tenant tenant) {
        return new TenantService(null, null, null) {
            @Override
            public Tenant getTenantById(long id) {
                return tenant != null && tenant.getId() != null && tenant.getId() == id ? tenant : null;
            }
        };
    }
}
