package com.ragagent.wiki.service.ingest;

/**
 * 按 KB 的在途批次上限。
 *
 * <p><b>存在理由</b>：允许同一 KB 的多个批次并发跑，
 * 于是单个 KB 的批量导入可能占满整个 wiki worker 池、饿死其它 KB。每个运行中的批次
 * 在一个按 KB 的集合里占一个槽位（score = 过期时间）；过期扫描
 * 清掉崩溃 worker 遗留的槽位，因此上限是<b>自愈</b>的，不需要显式锁。</p>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现的上限只在<b>单个 JVM</b> 内生效；Redis
 * 实现是全局的。单实例部署下两者等价；多副本部署时每个副本各自允许 {@code maxInflight}
 * 个批次，实际并发是"副本数 × 上限"。多副本需要全局上限时换 Redis 实现。</p>
 *
 * <p><b>fail-open</b>：协调层故障时应当放行（{@link Reservation#granted()}
 * 为 true 且 release 是 no-op）——一次 Redis 抖动不该让 wiki 生成停摆，
 * 池子大小本身仍然兜住总工作量。</p>
 */
public interface WikiInflightLimiter {

    /**
     * 预留一个在途批次槽位。
     *
     * @param kbId        知识库 id
     * @param maxInflight 上限；{@code <= 0} 时一律放行
     * @return 预留结果。{@code granted == false} 表示该 KB 已达上限，
     *         调用方应重排后续触发并放弃本批次；{@code granted == true} 时
     *         <b>必须在批次结束时调用</b> {@link Reservation#release()}。
     */
    Reservation reserve(String kbId, int maxInflight);

    /**
     * 一次预留：{@code release} 非 null 时调用方必须执行释放。
     */
    record Reservation(Runnable release, boolean granted) {

        /** 放行的 no-op 槽位 */
        public static Reservation allow() {
            return new Reservation(() -> { }, true);
        }

        /** 被上限挡回 */
        public static Reservation deny() {
            return new Reservation(null, false);
        }

        /** 安全释放（重复调用无害） */
        public void releaseQuietly() {
            if (release != null) {
                release.run();
            }
        }
    }
}
