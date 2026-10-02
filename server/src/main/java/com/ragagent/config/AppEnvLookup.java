package com.ragagent.config;

import java.util.function.Function;

/**
 * 应用级 env 查找面（B6 批 10，收尾兜底）。
 *
 * <p>此前各域已就位：存储（{@code StorageEnvLookup}）、检索（{@code RetrievalEnvLookup}）、
 * 以及启动期快照（{@code RuntimeSnapshotWiring} 装的语言/上传限额/AES/SSRF）。本类收编**剩余
 * 零散点**：它们散布在静态方法里（{@code Gate.pubsubChannel}、{@code OidcConfig.env}、
 * {@code OllamaService.getOllamaService}、{@code interpolateEnv} …），既不是某个域的族群，也没有
 * 可注入的装配点。</p>
 *
 * <p><b>装配时机</b>：由 {@link AppEnvLookupEnvironmentPostProcessor} 在
 * <b>任何 bean 实例化之前</b>装好——这些读点里有启动期就跑的（{@code StartupTaskRecovery.distributed()}
 * 决定 Lite/分布式、{@code BuiltinModelsReconciler} 是启动 runner、API-Key 引导），
 * 放通用 {@code RuntimeSnapshotWiring}（@Configuration 构造器）则 bean 顺序不保证。</p>
 *
 * <p>装的是<b>查找函数</b>而非值：读时实时向 {@code Environment} 取值，故不存在
 * 「装早了看不到后到的 property source」的问题。语义与存储/检索两面一致：
 * 键名原样优先 → 回落属性风格（见 {@code EnvPropertyLookup}）。</p>
 *
 * <p><b>只允许装配层调用 {@link #install}</b>；未装配（单元测试不经 Spring）时视为未配置。</p>
 */
public final class AppEnvLookup {

    private static volatile Function<String, String> lookup = key -> null;

    private AppEnvLookup() {
    }

    /** 装配层安装（{@code null} → 未配置）。 */
    public static void install(Function<String, String> fn) {
        lookup = fn == null ? key -> null : fn;
    }

    /** 读一个键；未配置返回 {@code null}。 */
    public static String get(String key) {
        return lookup.apply(key);
    }
}
