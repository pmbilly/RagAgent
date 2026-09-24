package com.ragagent.sandbox.service;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ragagent.auth.domain.Tenant;
import com.ragagent.auth.service.TenantService;
import com.ragagent.storage.fileserve.StorageFileResolver;
import com.ragagent.storage.fileserve.StoragePaths;

/**
 * 租户感知的 skill 归档存储（A3-3 尾批，{@code @Primary} 接管 {@link SkillBundleStore} 注入）。
 *
 * <p>对照 Go {@code tenant_skill_catalog.go L383-397}：归档经
 * {@code fileServiceForTenant(ctx, tenantID)} 解析出的租户文件服务落盘
 * （{@code fs.SaveBytes(ctx, archive, tenantID, "tenant-skills/catalog/<id>.zip", false)}）。
 * 本类同形：</p>
 *
 * <ul>
 *   <li><b>云租户</b>：走 A3 的 provider 服务——{@code SaveBytes} 的
 *       {@code SafeFileName} 会丢掉目录部分（Go 同样如此：{@code filepath.Base}），
 *       所以 key {@code tenant-skills/catalog/x.zip} 落成 {@code {prefix}{tenant}/exports/{uuid}.zip}，
 *       引用以 provider path 形式进 {@code bundle_ref} 列；读回经 {@code GetFile}，
 *       删除 best-effort（对照 {@code deleteBundleBestEffort}）；</li>
 *   <li><b>本地租户</b>：原样委托 {@link LocalSkillBundleStore}（{@code local://} 布局不变，
 *       golden 的 sbk-files / sbk-reinstall 链路不受影响）；</li>
 *   <li><b>读不到的引用必须抛</b>（对照 Go：GetFile 错误让 trySkillBundle 报"不可用"，
 *       而不是把空字节当内容）。</li>
 * </ul>
 */
@Component
@Primary
public class TenantSkillBundleStore implements SkillBundleStore {

    private static final Logger log = LoggerFactory.getLogger(TenantSkillBundleStore.class);

    private final LocalSkillBundleStore local;
    private final StorageFileResolver resolver;
    private final TenantService tenantService;
    private final String localBaseDir;

    public TenantSkillBundleStore(LocalSkillBundleStore local,
                                  StorageFileResolver resolver,
                                  TenantService tenantService,
                                  @Value("${weknora.storage.local-base-dir:${LOCAL_STORAGE_BASE_DIR:/data/files}}")
                                          String localBaseDir) {
        this.local = local;
        this.resolver = resolver;
        this.tenantService = tenantService;
        this.localBaseDir = localBaseDir;
    }

    @Override
    public String save(long tenantId, String key, byte[] archive) {
        StorageFileResolver.ProviderResolution resolved = resolve(tenantId, null);
        if (resolved.ok()) {
            String ref = resolved.service().saveBytes(archive, tenantId, key, false);
            log.info("stored skill bundle on provider {}: tenant_id={} ref={}",
                    resolved.provider(), tenantId, ref);
            return ref;
        }
        if (resolved.error() != null) {
            // 配了云却解析失败 → 抛出（调用方的 requireStore 分支会把它翻成失败）
            throw new IllegalStateException(
                    "resolve storage provider failed: " + resolved.error());
        }
        return local.save(tenantId, key, archive);
    }

    @Override
    public byte[] load(long tenantId, String ref) {
        if (!isCloudRef(ref)) {
            return local.load(tenantId, ref);
        }
        StorageFileResolver.ProviderResolution resolved =
                resolve(tenantId, StoragePaths.parseProviderScheme(ref));
        if (!resolved.ok()) {
            throw new IllegalStateException("skill bundle unavailable: "
                    + (resolved.error() == null ? "provider not resolvable" : resolved.error()));
        }
        try (InputStream in = resolved.service().getFile(ref)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("read skill bundle failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    @Override
    public void delete(long tenantId, String ref) {
        if (!isCloudRef(ref)) {
            local.delete(tenantId, ref);
            return;
        }
        try {
            StorageFileResolver.ProviderResolution resolved =
                    resolve(tenantId, StoragePaths.parseProviderScheme(ref));
            if (resolved.ok()) {
                resolved.service().deleteFile(ref);
            } else {
                log.warn("delete skill bundle skipped: ref={} err={}", ref, resolved.error());
            }
        } catch (RuntimeException e) {
            // 尽力而为（对照 Go 的 deleteBundleBestEffort 只记日志）
            log.warn("delete skill bundle failed: ref={} err={}", ref, e.toString());
        }
    }

    /** 云引用 = 带 provider scheme 且不是 {@code local://}。 */
    private static boolean isCloudRef(String ref) {
        String provider = StoragePaths.parseProviderScheme(ref);
        return !provider.isEmpty() && !"local".equalsIgnoreCase(provider);
    }

    /** 取不到租户 → (null, "", null)：当作没有云配置（本地布局）。 */
    private StorageFileResolver.ProviderResolution resolve(long tenantId, String providerHint) {
        Tenant tenant = tenantId <= 0 ? null : tenantService.getTenantById(tenantId);
        if (tenant == null) {
            return new StorageFileResolver.ProviderResolution(null, "", null);
        }
        return resolver.resolveProviderService(tenant, "", providerHint, localBaseDir);
    }
}
