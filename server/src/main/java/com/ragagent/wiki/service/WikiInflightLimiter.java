package com.ragagent.wiki.service;

/**
 * 按 KB 的在途批次上限（对照 Go {@code wikiIngestService.reserveInflightSlot}，
 * wiki_ingest.go L969-1011；Redis 侧是 {@code wiki:inflight:<kbID>} 的 ZSET + Lua）。
 *
 * <p><b>存在理由</b>（Go L102-124 注释）：Phase 3 允许同一 KB 的多个批次并发跑，
 * 于是单个 KB 的批量导入可能占满整个 wiki worker 池、饿死其它 KB。每个运行中的批次
 * 在一个按 KB 的集合里占一个槽位（score = 过期时间）；{@code ZREMRANGEBYSCORE}
 * 清掉崩溃 worker 遗留的槽位，因此上限是<b>自愈</b>的，不需要显式锁。</p>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现的上限只在<b>单个 JVM</b> 内生效。Go 的 Redis
 * 实现是全局的。单实例部署下两者等价；多副本部署时每个副本各自允许 {@code maxInflight}
 * 个批次，实际并发是"副本数 × 上限"。要恢复 Go 的语义需换成 Redis 实现。</p>
 *
 * <p><b>fail-open</b>：与 Go 一致，协调层故障时应当放行（{@link Reservation#granted()}
 * 为 true 且 release 是 no-op）——一次 Redis 抖动不该让 wiki 生成停摆，
 * 池子大小本身仍然兜住总工作量。</p>
 */
public interface WikiInflightLimiter {

    /**
     * 对照 Go {@code reserveInflightSlot}。
     *
     * @param kbId        知识库 id
     * @param maxInflight 上限；{@code <= 0} 时一律放行（对照 Go 的早退分支）
     * @return 预留结果。{@code granted == false} 表示该 KB 已达上限，
     *         调用方应重排后续触发并放弃本批次；{@code granted == true} 时
     *         <b>必须在批次结束时调用</b> {@link Reservation#release()}。
     */
    Reservation reserve(String kbId, int maxInflight);

    /**
     * 一次预留。对照 Go 的 {@code (release func(), ok bool)} 返回：
     * release 是非 nil 时调用方必须执行（Go 用 {@code defer releaseSlot()}）。
     */
    record Reservation(Runnable release, boolean granted) {

        /** 对照 Go {@code return func() {}, true}：放行的 no-op 槽位 */
        public static Reservation allow() {
            return new Reservation(() -> { }, true);
        }

        /** 对照 Go {@code return nil, false}：被上限挡回 */
        public static Reservation deny() {
            return new Reservation(null, false);
        }

        /** 安全释放（对照 Go 的 {@code defer}，重复调用无害） */
        public void releaseQuietly() {
            if (release != null) {
                release.run();
            }
        }
    }
}
