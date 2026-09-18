package com.ragagent.storageurl;

/**
 * 按租户配置解析存储后端（对照 Go {@code interfaces.StorageBackendResolver}，
 * internal/types/interfaces/storagebackend.go:28-31）。
 *
 * <p>Go 的接口有两个方法，本包<b>只用 {@code ResolveFileService}</b>
 * （{@code ResolveBackend} 未收窄进来，用不到）。</p>
 *
 * <p><b>接线状态</b>：与 {@link FileService} 一样，目前<b>无生产实现</b>——
 * 它属于未翻译的存储后端模块。传 {@code null} 时 {@link FileServiceResolver}
 * 会回落到进程级默认服务，与 Go 的 nil 分支一致。</p>
 */
public interface StorageBackendResolver {

    /**
     * 解析结果。对照 Go 的 {@code (FileService, string, error)} 三元组——
     * 中间那个 string 在本包的两处调用点都没被使用，故不建模。
     */
    record Resolved(FileService fileService) {
    }

    /**
     * 对照 Go {@code ResolveFileService(ctx, tenant, backendID, provider, localBaseDir)}。
     *
     * @param backendId 对照 Go {@code backendID}（{@code storage://<id>/…} 里的 id），无则为 {@code ""}
     * @param provider  已归一化的 provider 名（{@code local}/{@code minio}/…）
     * @param localBaseDir 对照 Go 的 {@code LOCAL_STORAGE_BASE_DIR} 取值
     */
    Resolved resolveFileService(long tenantId, String backendId, String provider, String localBaseDir);
}
