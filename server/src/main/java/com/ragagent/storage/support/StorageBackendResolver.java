package com.ragagent.storage.support;

/**
 * 按租户配置解析存储后端。
 *
 * <p><b>接线状态</b>：生产实现是
 * {@code com.ragagent.storage.fileserve.FileserveStorageBackendResolver}（{@code @Component}）
 * ——按 tenantId 取租户实体后走 {@code StorageFileResolver} 的完整语义
 * （backend 优先 / legacy alias / 环境回落 / resource 装饰）。解析不出时回传空，
 * {@link FileServiceResolver} 即回落到进程级默认服务。</p>
 */
public interface StorageBackendResolver {

    /**
     * 解析结果。中间的 provider 名本包的两处调用点都没被使用，故不建模。
     */
    record Resolved(FileService fileService) {
    }

    /**
     * 按租户 + backendId/provider 解析文件服务。
     *
     * @param backendId {@code storage://<id>/…} 里的 id，无则为 {@code ""}
     * @param provider  已归一化的 provider 名（{@code local}/{@code minio}/…）
     * @param localBaseDir 本地存储根目录取值
     */
    Resolved resolveFileService(long tenantId, String backendId, String provider, String localBaseDir);
}
