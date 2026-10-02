package com.ragagent.storage.config;

import java.util.function.Function;

/**
 * 存储域 provider 环境族的查找面（B6 批 8）。
 *
 * <p>三个 provider 读取器（{@code StorageFileResolver} / {@code FileServiceFactory} /
 * {@code StorageBackendService}）都按**运行期分支决定的键名**读环境
 * （{@code MINIO_*} / {@code OBS_*} / {@code S3_*} / {@code APP_EXTERNAL_URL} …），
 * 键名不是常量、无法用 {@code @ConfigurationProperties} 静态绑定，故这里给一个
 * <b>启动期装配的查找面</b>：值仍来自 Spring {@code Environment}（系统环境变量本就是它的
 * 一个 property source），从此「读环境」只有这一个入口，且可被属性源 / 命令行参数覆盖。</p>
 *
 * <p><b>只允许装配层调用 {@link #install}</b>；未装配时一律视为未配置（{@code null}）
 * ——各调用方原有的「空值→缺省」语义保持不变。</p>
 */
public final class StorageEnvLookup {

    private static volatile Function<String, String> lookup = key -> null;

    private StorageEnvLookup() {
    }

    /** 装配层安装（{@code null} → 未配置）。 */
    public static void install(Function<String, String> fn) {
        lookup = fn == null ? key -> null : fn;
    }

    /** 读一个 provider 家族键；未配置返回 {@code null}。 */
    public static String get(String key) {
        return lookup.apply(key);
    }
}
