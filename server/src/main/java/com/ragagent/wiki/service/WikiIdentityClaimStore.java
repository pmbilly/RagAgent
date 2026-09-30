package com.ragagent.wiki.service;

/**
 * 归一化身份（页面类型 + 显示标题）的 slug 预留。
 *
 * <h2>解决什么问题</h2>
 * <p>按 slug 的互斥锁在这里帮不上忙：当两个模型为<b>同一个标题</b>
 * 吐出<b>不同 slug</b> 时，两个 reducer 锁的是不同的键。一个短命的身份认领能让两个
 * 批次在摘要与页面更新落地之前就收敛到同一个 slug。</p>
 *
 * <h2>认领语义（Redis Lua 脚本）</h2>
 * <pre>{@code
 * if ARGV[3] == '1' then                     -- authoritative
 *   SET key proposed EX ttl; return proposed
 * existing = GET key
 * if type(existing) == 'string' and sub(existing,1,#prefix) == prefix then
 *   EXPIRE key ttl; return existing          -- 有效的既有 slug 获胜并续期
 * SET key proposed EX ttl; return proposed   -- 缺失/损坏的值被替换
 * }</pre>
 * <p>「缺失或损坏的值被替换」是刻意的：调用方绝不可以在一个脏键上分叉。
 * 前缀检查保证"另一个页面类型"的残留不会污染本类型的身份。</p>
 *
 * <p><b>⚠️ 多实例差异</b>：进程内实现只在单 JVM 内互斥；Redis 实现是全局的
 * ——<b>这是本模块里对多副本部署最敏感的一处</b>，因为身份认领的失效会直接表现为
 * "同一个标题被建出两个页面"。多副本生产部署必须换 Redis 实现。</p>
 */
public interface WikiIdentityClaimStore {

    /**
     * 认领一个身份 slug。
     *
     * @param kbId           知识库 id
     * @param pageType       页面类型（{@code "entity"} / {@code "concept"}）
     * @param identity       归一化身份键（{@code normalizeWikiIdentityTitle} 的结果）
     * @param proposedSlug   本次提议的 slug
     * @param authoritative  true = 强制覆盖（已物化的页面在此处是权威的）
     * @param requiredPrefix 既有值必须以此前缀开头才被认为是"本类型的有效认领"
     * @return 最终应当使用的 slug（可能是既有认领，也可能是本次提议）
     */
    String claim(String kbId, String pageType, String identity,
                 String proposedSlug, boolean authoritative, String requiredPrefix);

    /**
     * 释放认领（清理过期/失效认领时也会用到）。
     * 未持有该认领时是安全的 no-op。
     */
    void release(String kbId, String pageType, String identity);
}
