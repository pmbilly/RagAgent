package com.ragagent.storage.config;

/**
 * 存储域静态工具的启动期 env 快照（B6 批 7）。
 *
 * <p>存储域的路径/类型解析是**纯静态工具**（{@code StoragePaths}、{@code TenantFileServiceResolver}），
 * 没有 Spring 装配点，故值由 {@code config.RuntimeSnapshotWiring} 启动期写入一次；
 * <b>只允许装配层调用 {@link #install}</b>。原始串在此集中保存，缺省/大小写归一仍留在各自读点，
 * 语义与改前逐字一致。</p>
 */
public final class StorageRuntimeEnv {

    private static volatile String localStorageBaseDir = "";
    private static volatile String storageType = "";
    private static volatile String resourceUrlMode = "";

    private StorageRuntimeEnv() {
    }

    /** 启动期安装（{@code null} → 空串）。 */
    public static void install(String localStorageBaseDirRaw, String storageTypeRaw,
                               String resourceUrlModeRaw) {
        localStorageBaseDir = localStorageBaseDirRaw == null ? "" : localStorageBaseDirRaw;
        storageType = storageTypeRaw == null ? "" : storageTypeRaw;
        resourceUrlMode = resourceUrlModeRaw == null ? "" : resourceUrlModeRaw;
    }

    /** {@code LOCAL_STORAGE_BASE_DIR} 原始值（未配置 → 空串）。 */
    public static String localStorageBaseDir() {
        return localStorageBaseDir;
    }

    /** {@code STORAGE_TYPE} 原始值（未配置 → 空串）。 */
    public static String storageType() {
        return storageType;
    }

    /** {@code RESOURCE_URL_MODE} 原始值（未配置 → 空串）。 */
    public static String resourceUrlMode() {
        return resourceUrlMode;
    }
}
