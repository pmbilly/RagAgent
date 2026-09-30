package com.ragagent.wiki.service;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * {@link WikiFinalizeLock} 的<b>进程内</b>实现（默认装配）。
 *
 * <p>同一 KB 已经有 finalize 在跑时返回
 * {@link AcquireResult#BUSY}，调用方安全 no-op。</p>
 *
 * <p><b>⚠️ 多副本差异</b>：进程内实现只在单 JVM 内互斥，
 * {@link RedisWikiFinalizeLock} 才是跨实例全局的；
 * 多副本部署必须换用它，否则两个副本可能同时重建索引页
 * （{@code GetPage → 改 → UpdatePage} 丢失更新）。</p>
 */
@Component
public class InProcessWikiFinalizeLock implements WikiFinalizeLock {

    /** 当前持有 finalize 锁的 KB 集合 */
    private final ConcurrentHashMap<String, Boolean> held = new ConcurrentHashMap<>();

    @Override
    public AcquireResult tryAcquire(String kbId) {
        if (kbId == null || kbId.isEmpty()) {
            return AcquireResult.BUSY;
        }
        return held.putIfAbsent(kbId, Boolean.TRUE) == null
                ? AcquireResult.ACQUIRED
                : AcquireResult.BUSY;
    }

    @Override
    public void release(String kbId) {
        if (kbId != null && !kbId.isEmpty()) {
            held.remove(kbId);
        }
    }

    /** 供测试：当前被持有的 KB 数 */
    public int heldCount() {
        return held.size();
    }
}
