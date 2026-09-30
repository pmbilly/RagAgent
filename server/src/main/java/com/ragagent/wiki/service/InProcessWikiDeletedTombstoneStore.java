package com.ragagent.wiki.service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * {@link WikiDeletedTombstoneStore} 的<b>进程内</b>实现（默认装配）。
 *
 * <p>写入即带绝对过期时刻，读取时惰性判过期，过期即视为不存在。
 * 惰性清理由每次 {@link #exists} 顺手完成
 * ——墓碑的键空间是"最近删除的文档数"，量级很小，不为此单开清理线程。</p>
 *
 * <p>局限见接口注释：单 JVM 可见，但 {@code isKnowledgeGone} 有 DB 回落，
 * 因此不构成正确性依赖。</p>
 */
@Component
public class InProcessWikiDeletedTombstoneStore implements WikiDeletedTombstoneStore {

    private final ConcurrentHashMap<String, Long> tombstones = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String kbId, String knowledgeId) {
        String key = WikiIngestConstants.deletedTombstoneKey(kbId, knowledgeId);
        Long expiresAt = tombstones.get(key);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            tombstones.remove(key, expiresAt);
            return false;
        }
        return true;
    }

    @Override
    public void markDeleted(String kbId, String knowledgeId) {
        tombstones.put(WikiIngestConstants.deletedTombstoneKey(kbId, knowledgeId),
                System.currentTimeMillis() + WikiIngestConstants.DELETED_TTL.toMillis());
    }

    @Override
    public void clear(String kbId, String knowledgeId) {
        tombstones.remove(WikiIngestConstants.deletedTombstoneKey(kbId, knowledgeId));
    }

    /** 供测试/可观测：当前未过期的墓碑数 */
    public int size() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = tombstones.entrySet().iterator();
        int live = 0;
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
            } else {
                live++;
            }
        }
        return live;
    }
}
