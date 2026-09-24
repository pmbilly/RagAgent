package com.ragagent.storageurl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragagent.auth.domain.Tenant;

/**
 * A3-3 接线：{@code storageurl.FileServiceResolver} 的 provider 级文件服务此前恒为
 * {@code defaultSvc}（重写器因此永远拿不到 HTTP URL、引用原样保留），现在按 Go 的
 * {@code BuildFileServiceForProvider} 顺序取：真实服务 → local/默认 → null。
 *
 * <p>用 {@code cos} 代表云 provider（构造函数不触网）。断言"解析出非空服务"即
 * 证明工厂被接上；真正的 URL 生成要凭据，属部署态。</p>
 */
class FileServiceResolverWiringTest {

    @Test
    @DisplayName("租户配了 cos：cos:// 引用解析出真 provider 服务；无配置则回落 defaultSvc")
    void resolvesProviderServiceFromTenantConfig() {
        Tenant tenant = new Tenant();
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("default_provider", "cos");
        ObjectNode cos = sec.putObject("cos");
        cos.put("secret_id", "id");
        cos.put("secret_key", "key");
        cos.put("bucket_name", "bk-125");
        cos.put("region", "ap-guangzhou");
        tenant.setStorageEngineConfig(sec);

        FileServiceResolver resolver = new FileServiceResolver(tenant, null, null);
        FileService svc = resolver.resolveFileService("cos://bk-125/ap-guangzhou/a/b.png");
        assertNotNull(svc, "cos 配置完备时应解析出真 provider 服务");

        // 同一 (backendId, provider) 第二次命中缓存（同一实例）
        assertEquals(svc, resolver.resolveFileService("cos://bk-125/ap-guangzhou/a/c.png"));
    }

    @Test
    @DisplayName("租户没配存储 / 配置不完备：回落 defaultSvc（引用按 handle 保留）")
    void fallsBackToDefaultService() {
        Tenant empty = new Tenant(); // 无 storageEngineConfig
        FileServiceResolver noConfig = new FileServiceResolver(empty, null, null);
        assertNull(noConfig.resolveFileService("cos://bk/ap-guangzhou/a/b.png"));

        // 引用不带 provider scheme → 用租户 default_provider；仍无 → null（不解析）
        assertNull(noConfig.resolveFileService("plain/path.png"));

        // 不完备的 cos 段 → 工厂抛"incomplete cos config" → 回落 defaultSvc（此处非空）
        Tenant partial = new Tenant();
        ObjectNode sec = new ObjectMapper().createObjectNode();
        sec.put("default_provider", "cos");
        sec.putObject("cos").put("secret_id", "id");
        partial.setStorageEngineConfig(sec);
        StubUrlFileService fallback = new StubUrlFileService();
        FileServiceResolver partialResolver = new FileServiceResolver(partial, fallback, null);
        assertEquals(fallback, partialResolver.resolveFileService("cos://bk/r/a.png"));
    }

    private static final class StubUrlFileService implements FileService {
        @Override
        public String getFileURL(String filePath) {
            return "local://" + filePath;
        }
    }
}
