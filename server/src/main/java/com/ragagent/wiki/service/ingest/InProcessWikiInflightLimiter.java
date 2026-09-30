package com.ragagent.wiki.service.ingest;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * {@link WikiInflightLimiter} 的<b>进程内</b>实现（默认装配）。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>每个预留是一个带<b>过期时间</b>的 token，
 *       预留前先清掉已过期的 token（自愈）；</li>
 *   <li>后台按 {@link WikiIngestConstants#INFLIGHT_RENEW} 续期，
 *       让长跑批次不会因为"漏了一次续期"就丢槽位；</li>
 *   <li>上限判定与添加在同一次同步块里完成：拆开会让两个并发预留者
 *       都通过检查。</li>
 * </ul>
 *
 * <p>唯一的语义差异是作用域（单 JVM vs 全局），见接口注释。</p>
 */
@Component
public class InProcessWikiInflightLimiter implements WikiInflightLimiter {

    /** kbID → (token → 过期时刻，毫秒) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> slots =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService renewer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wiki-inflight-renew");
                t.setDaemon(true);
                return t;
            });

    public InProcessWikiInflightLimiter() {
        renewer.scheduleAtFixedRate(this::renewAll,
                WikiIngestConstants.INFLIGHT_RENEW.toMillis(),
                WikiIngestConstants.INFLIGHT_RENEW.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public Reservation reserve(String kbId, int maxInflight) {
        if (maxInflight <= 0) {
            // 上限未配置（<= 0）→ 无条件放行的 no-op 槽位
            return Reservation.allow();
        }
        ConcurrentHashMap<String, Long> kbSlots =
                slots.computeIfAbsent(kbId, k -> new ConcurrentHashMap<>());
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + WikiIngestConstants.INFLIGHT_TTL.toMillis();

        // purge + count + add 必须在同一临界区
        synchronized (kbSlots) {
            purgeExpired(kbSlots);
            if (kbSlots.size() >= maxInflight) {
                return Reservation.deny();
            }
            kbSlots.put(token, expiry);
        }
        return new Reservation(() -> {
            ConcurrentHashMap<String, Long> current = slots.get(kbId);
            if (current != null) {
                current.remove(token);
            }
        }, true);
    }

    /** 供测试/可观测：该 KB 当前占用的槽位数 */
    public int inflightCount(String kbId) {
        ConcurrentHashMap<String, Long> kbSlots = slots.get(kbId);
        if (kbSlots == null) {
            return 0;
        }
        synchronized (kbSlots) {
            purgeExpired(kbSlots);
            return kbSlots.size();
        }
    }

    /** 关停续期线程（测试收尾用）。 */
    public void shutdown() {
        renewer.shutdownNow();
    }

    /**
     * 后台续期：把每个 token 的过期时刻向前推，
     * 长跑批次因此不会因为漏掉单次续期而丢槽位。
     */
    private void renewAll() {
        long expiry = System.currentTimeMillis() + WikiIngestConstants.INFLIGHT_TTL.toMillis();
        for (ConcurrentHashMap<String, Long> kbSlots : slots.values()) {
            synchronized (kbSlots) {
                purgeExpired(kbSlots);
                for (Map.Entry<String, Long> e : kbSlots.entrySet()) {
                    e.setValue(expiry);
                }
            }
        }
    }

    private static void purgeExpired(ConcurrentHashMap<String, Long> kbSlots) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = kbSlots.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }
}
