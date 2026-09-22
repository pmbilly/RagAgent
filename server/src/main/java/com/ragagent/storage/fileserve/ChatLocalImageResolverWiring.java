package com.ragagent.storage.fileserve;

import java.io.IOException;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.llm.chat.ImageResolver;
import com.ragagent.storage.domain.StoredResource;
import com.ragagent.storage.service.ResourceCatalogService;

/**
 * 把 {@link ImageResolver} 的 {@code LocalImageResolver} 全局钩子接到存储模块
 * （对照 Go {@code internal/container/container.go} 的
 * {@code registerChatLocalImageResolver}，L489-536，调用点 L466
 * {@code container.Invoke}）——这是备案「阶段 4.0 已知差异 #6」
 * （docs/known-issues/00-foundation.md）的正式回补：storage 模块在 W5c 落地后，
 * 备案理由（装配点缺积木）已过期。
 *
 * <p>装配形态仿 {@code audit.service.RbacDeniedAuditorRegistrar}：构造注入 +
 * {@link InitializingBean#afterPropertiesSet()} 里注册静态钩子；
 * {@link DisposableBean#destroy()} 复位（多 Spring 上下文并存的测试环境里，
 * 陈旧钩子会指向已关闭的上下文）。</p>
 *
 * <h2>解析链（逐行对照 Go 闭包，行号 = container.go）</h2>
 * <ol>
 *   <li>{@code resourceCatalog.ResolvePath}（L498）：{@code resource://} 手柄换物理
 *       路径并带回资源行；非 resource 引用原样透传、资源行为空（见
 *       {@link ResourceCatalogService#resolvePath}）；解析失败 → {@code null}。</li>
 *   <li>租户判定（L502-506）：先从存储路径取第一段无符号整数
 *       （{@code secutils.ParseTenantIDFromStoragePath}）；resource 命中时用
 *       {@code resource.TenantID} 覆盖——存储里的 {@code local://} URL 不编码归属
 *       租户配置的 PathPrefix，跨租户共享资源（共享 KB 图片）也以资源行为准。0 → {@code null}。</li>
 *   <li>查租户（L508-511）：查不到 → {@code null}。</li>
 *   <li>后端解析（L513-524）：剥 {@code storage://<backendID>/} 包装；resource 行带
 *       {@code StorageBackendID} 时覆盖；provider 从剥包装后的路径前缀解析（缺省 local）。</li>
 *   <li>{@code storageResolver.ResolveFileService}（L526-529）：用归属租户的存储配置
 *       重建 FileService（→ {@link StorageFileResolver#resolveFileService}）；出错 → {@code null}。</li>
 *   <li>{@code fileSvc.GetFile(物理路径)} + 全量读字节（L530-539）：失败 → {@code null}。</li>
 * </ol>
 *
 * <h2>失败语义（与 Go 的兜底关系，逐行核对过调用侧 image_resolve.go L46-60）</h2>
 * <p>Go 的 {@code readLocalStorageBytes} 是「先问钩子，{@code ok=false} 时<b>仍然</b>落到
 * {@code LOCAL_STORAGE_BASE_DIR} 的磁盘拼接兜底」，Java 侧
 * {@link ImageResolver#readLocalStorageBytes} 已是同一形状——所以本类任何一步失败
 * 都返回 {@code null}（= Go 的 {@code (nil, false)}）并<b>绝不抛出</b>：解析器给不出
 * 字节时，LLM 侧对 {@code resolveImageUrlForLlm} 拿到的是原始存储 URL 原文（对
 * Ollama 拿到 {@code null}）。Go 用 {@code context.Background()}，本类不读
 * TenantContext（ThreadLocal），全部走显式 tenant 参数。</p>
 */
@Component
public class ChatLocalImageResolverWiring implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatLocalImageResolverWiring.class);

    private final ResourceCatalogService catalog;
    private final TenantService tenantService;
    private final StorageFileResolver storageResolver;

    public ChatLocalImageResolverWiring(ResourceCatalogService catalog,
                                        TenantService tenantService,
                                        StorageFileResolver storageResolver) {
        this.catalog = catalog;
        this.tenantService = tenantService;
        this.storageResolver = storageResolver;
    }

    /** 对照 Go container.go L466：容器装配时给 chat.LocalImageResolver 赋值。 */
    @Override
    public void afterPropertiesSet() {
        ImageResolver.setLocalImageResolver(this::resolve);
        log.info("chat LocalImageResolver hook installed (storage module wired)");
    }

    /** 多 Spring 上下文并存的测试环境里，避免陈旧钩子指向已关闭的上下文。 */
    @Override
    public void destroy() {
        ImageResolver.setLocalImageResolver(null);
    }

    /**
     * 对照 Go 闭包体（container.go L496-535）。任何一步失败返回 {@code null}
     * （= Go 的 {@code (nil, false)}），绝不抛出。
     */
    byte[] resolve(String storageUrl) {
        try {
            // L498-501: resourceCatalog.ResolvePath——resource:// 换物理路径，失败即 false
            ResourceCatalogService.ResolvedPath resolved = catalog.resolvePath(storageUrl);
            if (resolved.error()) {
                return null;
            }
            String physicalPath = resolved.physicalPath();
            StoredResource resource = resolved.resource();

            // L502-506: 路径租户段 + resource 命中时以 resource.TenantID 覆盖
            long tenantId = StoragePaths.parseTenantIdFromStoragePath(physicalPath);
            if (resource != null) {
                tenantId = resource.getTenantId();
            }
            if (tenantId == 0) {
                return null;
            }

            // L508-511: tenantRepo.GetTenantByID——查不到（Go: err/nil）→ false
            Tenant tenant = tenantService.getTenantById(tenantId);
            if (tenant == null) {
                return null;
            }

            // L513: baseDir := strings.TrimSpace(os.Getenv("LOCAL_STORAGE_BASE_DIR"))
            //   （缺省归并到 StoragePaths.localStorageBaseDir 的 /data/files 兜底——
            //   与 Go「空串传给工厂、工厂内补缺省」的净效果一致）
            String baseDir = StoragePaths.localStorageBaseDir();

            // L514-521: 剥 storage://<backendID>/ 包装；resource 行带 backendID 时覆盖；
            //   provider 从（scoped 时剥包装后的）路径前缀解析
            StoragePaths.ParsedBackendPath parsed = StoragePaths.parseStorageBackendPath(physicalPath);
            String backendId = parsed.backendId();
            boolean scoped = parsed.ok();
            String inner = parsed.providerPath();
            if (resource != null
                    && resource.getStorageBackendId() != null && !resource.getStorageBackendId().isEmpty()) {
                backendId = resource.getStorageBackendId();
            }
            String providerPath = physicalPath;
            if (scoped) {
                providerPath = inner;
            }
            String provider = StoragePaths.parseProviderScheme(providerPath);
            if (provider == null || provider.isEmpty()) {
                provider = "local";
            }

            // L526-529: storageResolver.ResolveFileService——按归属租户配置重建 FileService
            StorageFileResolver.Resolution resolution =
                    storageResolver.resolveFileService(tenant, backendId, provider, baseDir);
            if (resolution.error() != null || resolution.service() == null) {
                return null;
            }

            // L530-539: fileSvc.GetFile(物理路径) + io.ReadAll
            FileTransport.OpenedFile opened = resolution.service().getFile(physicalPath);
            if (opened.bytes() != null) {
                return opened.bytes();
            }
            return Files.readAllBytes(opened.seekable());
        } catch (RuntimeException | IOException e) {
            // Go: 任何 err → (nil, false)，调用侧回落 LOCAL_STORAGE_BASE_DIR 兜底
            log.debug("[image-resolve] application resolver failed for {}: {}", storageUrl, e.toString());
            return null;
        }
    }
}
