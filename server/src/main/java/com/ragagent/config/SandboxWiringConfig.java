package com.ragagent.config;

import com.ragagent.session.mapper.SessionMapper;
import com.ragagent.sandbox.runtime.RemoteSessionLifecycle;
import com.ragagent.sandbox.runtime.SessionSandboxBindingRedis;
import com.ragagent.sandbox.runtime.SessionSandboxBindingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 沙箱运行时单例装配（对照 Go internal/container/sandbox.go 的
 * {@code selectSessionBindingStore} / {@code sessionExistenceCheckerFor}）。
 *
 * <p><b>绑定存储选择</b>（Go 同款）：有 Redis → Redis 权威存储（键
 * {@code weknora:sandbox:session:{<ns>:<tenant>:<session>}:binding}，跨实例互操作）；
 * 无 Redis → 内存存储（单实例，降级时 WARN）。命名空间取
 * {@code WEKNORA_REDIS_NAMESPACE} env，缺省 {@code weknora}（Go 逐字）。
 * 内存实现见 {@link SessionSandboxBindingStore} 的嵌套类。</p>
 *
 * <p><b>会话存在性检查器</b>：生命周期协调器回收孤儿绑定前用它确认会话真的
 * 消失了——注入真会话仓储查询（tenant + id + 软删过滤），不用宽容实现。</p>
 */
@Configuration
public class SandboxWiringConfig {

    @Bean
    public SessionSandboxBindingStore sandboxBindingStore(StringRedisTemplate redisTemplate) {
        String namespace = System.getenv("WEKNORA_REDIS_NAMESPACE");
        if (namespace == null || namespace.strip().isEmpty()) {
            namespace = "weknora";
        }
        return new SessionSandboxBindingRedis(redisTemplate, namespace);
    }

    @Bean
    public RemoteSessionLifecycle.SessionExistenceChecker sandboxSessionExistenceChecker(
            SessionMapper sessionMapper) {
        return key -> sessionMapper.countSessionExists(key.tenantId(), key.sessionId()) > 0;
    }
}
