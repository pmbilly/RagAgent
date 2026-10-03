package com.ragagent.llm.chat;

import com.ragagent.common.security.SsrfGuard;

import org.springframework.context.annotation.Configuration;

/**
 * 把 Spring 管理的 {@link SsrfGuard} 单例交给 {@link LlmTransport}，全进程共享。
 *
 * <p>必要性：{@code SsrfGuard} 的白名单可被系统设置模块在运行时调谐
 * （{@link SsrfGuard#reloadWhitelist(String)}）；若 LLM 传输层自己 new 一个实例，
 * 就会一直用启动时的 ENV 白名单，运行时的调谐对它不可见。</p>
 */
@Configuration
public class LlmTransportWiring {

    public LlmTransportWiring(SsrfGuard ssrfGuard) {
        LlmTransport.setSsrfGuard(ssrfGuard);
    }
}
