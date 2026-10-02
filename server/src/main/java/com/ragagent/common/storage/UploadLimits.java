package com.ragagent.common.storage;

/**
 * 上传大小限额（环境变量 {@code MAX_FILE_SIZE_MB}，缺省 50，非法值回落缺省）——**纯规则**。
 *
 * <p>为什么在 common：这条规则被本地存储服务（knowledge 的文件上传）与 model 的调试端点共用。
 * 原先它作为静态方法挂在 {@code knowledge.storage.LocalStorageService} 上，导致 model 为了两个
 * 纯规则方法反向 import 知识域（{@code knowledge ⇄ model} 环的一半）。纯规则/配置放最底层。</p>
 */
public final class UploadLimits {

    /** 环境变量名（对照 Go 读取的 MAX_FILE_SIZE_MB）。 */
    public static final String MAX_FILE_SIZE_MB_ENV = "MAX_FILE_SIZE_MB";

    private static final int DEFAULT_MB = 50;

    private UploadLimits() {
    }

    /**
     * 配置原始值（{@code MAX_FILE_SIZE_MB}）——**启动期快照**（B6 批 6）。
     *
     * <p>本类是纯静态规则（调用点散布 knowledge/model/initialization 四处），没有 Spring
     * 装配点，故值由 {@code config.RuntimeSnapshotWiring} 在启动期写入一次；
     * <b>只允许装配层调用 install</b>。</p>
     */
    private static volatile String configuredRaw = "";

    /** 启动期安装限额原始值（{@code null} → 空串，回落缺省 50MB）。 */
    public static void installFileSizeMb(String raw) {
        configuredRaw = raw == null ? "" : raw;
    }

    /** 限额的字节数。 */
    public static long maxFileSizeBytes() {
        String env = configuredRaw;
        int mb = DEFAULT_MB;
        if (env != null && !env.isBlank()) {
            try {
                mb = Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
                // 非法值回落缺省（对照原实现）
            }
        }
        return (long) mb * 1024 * 1024;
    }

    /** 限额的 MB 数（错误消息用）。 */
    public static long maxFileSizeMb() {
        return maxFileSizeBytes() / (1024 * 1024);
    }
}
