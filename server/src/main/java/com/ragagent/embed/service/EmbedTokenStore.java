package com.ragagent.embed.service;

import java.time.Duration;

/**
 * embed session token 的存取口（对照 Go 里 {@code s.redis.Set/Get} 的两个调用点，
 * internal/application/service/embed_session.go）。
 *
 * <p>抽成接口的原因：契约测试环境没有 Redis（测试 yml 明确声明不依赖外部 redis），
 * 而 golden 里 exchange / preview-session 必须发出可用的 {@code ems_} token（200）。
 * 生产装配是 {@link RedisEmbedTokenStore}（跨语言键空间 {@code embed:session:<token>}）；
 * 测试以 @Primary 内存实现替换。Go 的 {@code s.redis == nil → ErrEmbedSessionUnavailable}
 * 语义由"容器里没有可用实现"承载（此时 issuance 走 503 分支）。</p>
 */
public interface EmbedTokenStore {

    /** 对照 {@code redis.Set(ctx, "embed:session:"+token, channelID, ttl)}。 */
    void put(String token, String channelId, Duration ttl);

    /** 对照 {@code redis.Get}：键不存在返回 null（对照 redis.Nil）。 */
    String get(String token);
}
