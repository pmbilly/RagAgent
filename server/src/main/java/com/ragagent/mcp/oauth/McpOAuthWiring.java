package com.ragagent.mcp.oauth;

import com.ragagent.mcp.mapper.McpOAuthRepository;
import com.ragagent.mcp.mapper.McpServiceMapper;
import com.ragagent.mcp.protocol.McpOAuthSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MCP OAuth 装配。
 *
 * <p>本类是<b>新文件</b>，不触碰 {@code config/WebConfig.java}（路由注册由主会话统一做）。</p>
 *
 * <h3>Redis 状态存储的开关</h3>
 * <p>state 存 Redis 还是内存由属性开关决定。Spring Data Redis 的
 * {@code StringRedisTemplate} <b>只要依赖在 classpath 上就存在</b>，
 * 无法用它表达"没配 Redis"。故显式用属性开关，默认<b>内存</b>（单实例语义）：</p>
 * <pre>{@code
 * weknora:
 *   mcp-oauth:
 *     redis-state-store: true   # 多副本部署必须打开，否则回调落到别的副本会找不到 state
 * }</pre>
 *
 * <h3>刻意不依赖 McpServiceService</h3>
 * <p>{@code OAuthManager} 用 {@link McpServiceMapper} 而非 {@code McpServiceService}
 * 加载服务——后者依赖 {@code Optional<McpOAuthSupport>}，直接依赖会形成 Spring 循环。
 * 查询语义相同（租户自有 + 内置 + 未软删）。</p>
 */
@Configuration
public class McpOAuthWiring {

    /** 配置开启时 state 落 Redis（跨副本可见）。 */
    @Bean
    @ConditionalOnProperty(prefix = "weknora.mcp-oauth", name = "redis-state-store",
            havingValue = "true")
    public OAuthStateRedis oauthStateRedis(StringRedisTemplate template) {
        return new SpringOAuthStateRedis(template);
    }

    /**
     * 有 {@link OAuthStateRedis} 就用它，
     * 否则退化为带 TTL 的内存 map（单实例 / Lite）。
     */
    @Bean
    public OAuthStateStore oauthStateStore(ObjectProvider<OAuthStateRedis> redis) {
        return new OAuthStateStore(redis.getIfAvailable());
    }

    /** 仓储端口到具体实现的绑定。 */
    @Bean
    @ConditionalOnMissingBean(OAuthRepository.class)
    public OAuthRepository oauthRepository(McpOAuthRepository delegate) {
        return new McpOAuthRepositoryAdapter(delegate);
    }

    /** OAuth 管理器 bean。 */
    @Bean
    public OAuthManager oauthManager(OAuthRepository repository, McpServiceMapper serviceMapper,
                                     OAuthStateStore stateStore) {
        return new OAuthManager(repository, serviceMapper, stateStore);
    }

    /**
     * <b>接上协议层注入点</b>：{@code McpClientFactory} / {@code McpServiceService} /
     * {@code McpMetadataService} 都通过 {@code Optional<McpOAuthSupport>} 消费本 bean。
     */
    @Bean
    public McpOAuthSupport mcpOAuthSupport(OAuthRepository repository) {
        return new McpOAuthSupportImpl(repository);
    }
}
