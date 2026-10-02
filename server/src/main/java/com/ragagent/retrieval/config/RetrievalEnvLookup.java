package com.ragagent.retrieval.config;

import java.util.function.Function;

/**
 * 检索域环境变量的查找面（B6 批 9）。
 *
 * <p>本域有一批「按常量键名读环境」的回落点：各引擎仓的集合/表/索引名、
 * Doris 兼容模式、SQLite 路径、多店检索超时、VLM HTTP 超时——键名是常量但读点在
 * <b>静态方法</b>里（且部分在<b>引擎构造期</b>就跑），无法用 {@code @ConfigurationProperties}
 * 逐点绑定，故这里给一个<b>启动期装配的查找面</b>：值仍来自 Spring {@code Environment}
 * （系统环境变量本就是它的一个 property source），从此「读环境」只有这一个入口。</p>
 *
 * <p><b>装配时机</b>：由 {@code config.RetrievalEngineWiringConfig} 的构造器安装——
 * 引擎在本域装配类的 {@code @Bean} 方法里构造，构造器先于其 {@code @Bean} 方法执行，
 * 故安装必然早于任何引擎构造（若改到通用快照装配类里装，bean 实例化顺序不保证，
 * 会静默丢掉 env 配置的集合名）。</p>
 *
 * <p><b>只允许装配层调用 {@link #install}</b>；未装配时一律视为未配置（{@code null}）
 * ——各读点原有的「空值→缺省」语义保持不变。</p>
 */
public final class RetrievalEnvLookup {

    private static volatile Function<String, String> lookup = key -> null;

    private RetrievalEnvLookup() {
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
