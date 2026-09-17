package com.ragagent.wiki.service;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * {@link WikiIdentityClaimStore} 的<b>进程内</b>实现（默认装配）。
 *
 * <p>用 {@code ConcurrentHashMap} + 每条认领自带过期时刻，逐条复刻 Go 的 Lua 脚本：
 * 权威认领直接覆盖；否则既有且前缀匹配的认领获胜并续期；否则用提议值覆盖。
 * 过期条目在每次访问该键时惰性清除——与 Redis 的 {@code EX ttl} 语义等价
 * （唯一差别是我们不会在无人访问时主动回收内存，条目数上限是 KB 内不同标题数）。</p>
 *
 * <p><b>⚠️ 多实例下不成立</b>：见接口注释——本条是多副本部署最容易出问题的地方。</p>
 */
@Component
public class InProcessWikiIdentityClaimStore implements WikiIdentityClaimStore {

    /**
     * 对照 Go 的 {@code redis.call('SET'/'GET'/'EXPIRE')} 键值对。
     * value 是 (slug, 过期毫秒)。
     */
    private record Claim(String slug, long expiresAtMillis) {}

    private final ConcurrentHashMap<String, Claim> claims = new ConcurrentHashMap<>();

    @Override
    public String claim(String kbId, String pageType, String identity,
                        String proposedSlug, boolean authoritative, String requiredPrefix) {
        String key = claimKey(kbId, pageType, identity);
        long expiry = System.currentTimeMillis() + WikiIngestConstants.IDENTITY_CLAIM_TTL.toMillis();

        // 对照 Lua：ARGV[3] == '1' → 无条件 SET 并返回 proposed
        if (authoritative) {
            claims.put(key, new Claim(proposedSlug, expiry));
            return proposedSlug;
        }

        String prefix = requiredPrefix == null ? "" : requiredPrefix;
        // 用 compute 保证"读-判-写"是一个原子步骤（对照 Lua 脚本本身的原子性）
        Claim result = claims.compute(key, (k, existing) -> {
            if (existing != null
                    && existing.expiresAtMillis() >= System.currentTimeMillis()
                    && existing.slug().startsWith(prefix)) {
                // 有效的既有 slug 获胜，续期
                return new Claim(existing.slug(), expiry);
            }
            // 缺失、已过期或前缀不符（脏值）→ 用提议值替换
            return new Claim(proposedSlug, expiry);
        });
        return result.slug();
    }

    @Override
    public void release(String kbId, String pageType, String identity) {
        claims.remove(claimKey(kbId, pageType, identity));
    }

    /** 供测试：当前活跃认领数 */
    public int claimCount() {
        return claims.size();
    }

    private static String claimKey(String kbId, String pageType, String identity) {
        return WikiIngestConstants.identityClaimKey(kbId, pageType, identity);
    }
}
