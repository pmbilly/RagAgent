package com.ragagent.embed.service;

import java.time.Duration;

/**
 * embed session token 的存取口。
 *
 * <p>抽成接口的原因：契约测试环境没有 Redis（测试 yml 明确声明不依赖外部 redis），
 * 而 golden 里 exchange / preview-session 必须发出可用的 {@code ems_} token（200）。
 * 生产装配是 {@link RedisEmbedTokenStore}（键空间 {@code embed:session:<token>}）；
 * 测试以 @Primary 内存实现替换。「无可用 store」由"容器里没有可用实现"承载
 * （此时 issuance 走 503 分支）。</p>
 */
public interface EmbedTokenStore {

    /** 写入 token → channelID，带 TTL（键 {@code embed:session:<token>}）。 */
    void put(String token, String channelId, Duration ttl);

    /** 读取：键不存在返回 null。 */
    String get(String token);
}
