package com.ragagent.wiki.service;

/**
 * 「该 KB 是否正在跑 ingest 批次」的短命信号端口
 * （对照 Go {@code wikiPageService.GetStats} 里的
 * {@code s.redisClient.Exists(ctx, "wiki:active:"+kbID)}，wiki_page.go L860-866）。
 *
 * <p>Go 的用法同样是 nil 容忍的：{@code if s.redisClient != nil { ... } }，
 * Redis 缺席时 {@code isActive} 恒为 false。</p>
 *
 * <p><b>⚠️ 这条读的是历史遗留键</b>：Go wiki_ingest.go L44-49 的注释说明 Phase 3
 * 已<b>移除</b>了独占式的 {@code wiki:active:<kbID>} 批次锁（改为行认领 + 按 slug 锁），
 * 也就是说这个键在新代码里<b>没有任何写入方</b>。GetStats 仍读它属于残留代码，
 * 真实部署下 {@code is_active} 实际恒为 false。Java 侧照抄该行为（提供一个可插拔
 * 端口，而不是硬编码 false），以免将来 Go 补上写入方时两边漂移。</p>
 */
public interface WikiActiveFlag {

    /** 对照 Go 的键前缀 {@code "wiki:active:"} */
    String KEY_PREFIX = "wiki:active:";

    /** 对照 Go {@code redisClient.Exists(...) > 0}：键存在即视为活跃 */
    boolean isActive(String kbId);
}
