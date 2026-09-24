package com.ragagent.storage.fileserve;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.storageurl.FileService;
import com.ragagent.storageurl.StorageBackendResolver;

/**
 * {@code com.ragagent.storageurl.StorageBackendResolver} 的生产实现（A3-3 接线）。
 *
 * <p>此前该端口<b>没有实现</b>：HTTP 面的三处调用点（{@code SessionStreamController} /
 * {@code KnowledgeQaController} / {@code MessageController}）都按
 * {@code ObjectProvider<StorageBackendResolver>} 注入，拿到空 → 重写器一路走
 * "解析不出 HTTP URL → 引用原样保留"的降级分支。本 bean 把它接到
 * {@link StorageFileResolver}（fileserve 的运行时解析器，含 backend 优先 / legacy alias /
 * 环境回落 / resource 装饰四段语义），于是配了云后端的租户引用能换成真预签名 URL。</p>
 *
 * <p>解析失败的处置刻意<b>不回传错误</b>：{@code Resolved} 只有服务位，
 * 而 {@code FileServiceResolver} 在拿到 null 时会打 WARN 并回落进程级默认服务
 * （对照 Go 的 nil 分支）——错误细节留在 {@link StorageFileResolver} 自己的日志里。</p>
 */
@Component
public class FileserveStorageBackendResolver implements StorageBackendResolver {

    private final StorageFileResolver storageFileResolver;
    private final TenantService tenantService;

    public FileserveStorageBackendResolver(StorageFileResolver storageFileResolver,
                                           TenantService tenantService) {
        this.storageFileResolver = storageFileResolver;
        this.tenantService = tenantService;
    }

    @Override
    public Resolved resolveFileService(long tenantId, String backendId, String provider,
            String localBaseDir) {
        if (tenantId <= 0) {
            // 对照 Go：workspace context missing（无租户 → 调用方回落）
            return new Resolved(null);
        }
        Tenant tenant = tenantService.getTenantById(tenantId);
        if (tenant == null) {
            return new Resolved(null);
        }
        StorageFileResolver.Resolution resolution =
                storageFileResolver.resolveFileService(tenant, backendId, provider, localBaseDir);
        if (!resolution.ok() || resolution.service() == null) {
            return new Resolved(null);
        }
        return new Resolved(new ContentServiceUrlPort(resolution.service()));
    }

    /** storageurl 窄口（只有 GetFileURL）→ fileserve 读取面的适配。 */
    private record ContentServiceUrlPort(FileContentService inner) implements FileService {

        @Override
        public String getFileURL(String filePath) {
            try {
                return inner.getFileURL(filePath);
            } catch (IOException e) {
                // 对照 storageurl.FileService 的契约：失败抛 RuntimeException，
                // Rewriter 会捕获、WARN、把引用原样留下
                throw new IllegalStateException(
                        e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
    }
}
