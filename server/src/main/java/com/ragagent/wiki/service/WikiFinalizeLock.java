package com.ragagent.wiki.service;

/**
 * 按 KB 的 finalize 独占锁（对照 Go {@code wiki:finalize:active:<kbID>} 的
 * {@code SetNX + Expire}，wiki_ingest_batch.go L934-972）。
 *
 * <h2>为什么需要它（照搬 Go 注释）</h2>
 * <p>它与 ingest 的 active 锁<b>分离</b>，因此 finalize 与 ingest 批次永不互相阻塞。
 * 用 {@code asynq.TaskID} 做的合流已经保证每个 KB 最多一个 finalize 待执行；
 * 这把锁守护的是"重排重叠窗口"里与并发索引页写入的竞争
 * （{@code GetPage → 改 → UpdatePage} 的丢失更新）。</p>
 *
 * <h2>失败语义（与 Go 逐条一致）</h2>
 * <ul>
 *   <li>取锁<b>报错</b>时 {@code fail CLOSED}（返回错误让调用方重试），
 *       因为无锁执行会让两次 finalize 排空同一批 {@code PeekBatch} 行并重复重建索引页；</li>
 *   <li>取锁失败（别人持有）时安全地 no-op——对方会排空通道并在还有行时重排。</li>
 * </ul>
 *
 * <p><b>装配</b>：与 {@link WikiSlugLock} 同一模式——{@link InProcessWikiFinalizeLock}
 * 是无条件 {@code @Component}（Lite 模式 / 单副本），多副本部署应注册
 * {@link RedisWikiFinalizeLock} 为 {@code @Primary}。</p>
 */
public interface WikiFinalizeLock {

    /**
     * 尝试取得该 KB 的 finalize 独占权。
     *
     * @return {@link AcquireResult#ACQUIRED}（调用方必须配对 {@link #release(String)}）、
     *         {@link AcquireResult#BUSY}（别人持有 → 安全 no-op）、
     *         {@link AcquireResult#FAILED}（协调层故障 → 调用方应让任务失败并重试）
     */
    AcquireResult tryAcquire(String kbId);

    /** 释放该 KB 的 finalize 独占权。未持有时是安全的 no-op。 */
    void release(String kbId);

    /** 对照 Go 的 {@code (acquired bool, err error)} 三态返回 */
    enum AcquireResult {
        /** 取得 */
        ACQUIRED,
        /** 已被别人持有：安全 no-op */
        BUSY,
        /** 协调层故障：fail closed，让任务重试 */
        FAILED
    }

    /** 对照 Go {@code key := "wiki:finalize:active:" + payload.KnowledgeBaseID} */
    static String lockKey(String kbId) {
        return WikiIngestConstants.FINALIZE_LOCK_PREFIX + kbId;
    }
}
