package com.ragagent.wiki.service;

/**
 * 「该 KB 是否正在跑 ingest 批次」的短命信号端口。
 *
 * <p>Redis 缺席时 {@code isActive} 恒为 false（实现侧 null 容忍）。</p>
 *
 * <p><b>⚠️ 这条读的是历史遗留键</b>：独占式的 {@code wiki:active:<kbID>} 批次锁已被
 * <b>移除</b>（改为行认领 + 按 slug 锁），这个键在新代码里<b>没有任何写入方</b>。
 * 统计侧仍读它属于残留行为，真实部署下 {@code is_active} 实际恒为 false。
 * 仍提供可插拔端口而不是硬编码 false，以免将来补上写入方时两边漂移。</p>
 */
public interface WikiActiveFlag {

    /** Redis 键前缀 */
    String KEY_PREFIX = "wiki:active:";

    /** 键存在即视为活跃 */
    boolean isActive(String kbId);
}
