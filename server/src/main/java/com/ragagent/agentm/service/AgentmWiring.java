package com.ragagent.agentm.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.ollama.OllamaService;

/**
 * agentm 模块的进程级装配：
 * <ul>
 *   <li>{@link OllamaService} 单例 bean。
 *       注意 {@code isAvailable} 标志是跨请求共享状态——"已可用则跳过
 *       StartService" 的分支依赖这一点，不能每请求新建。</li>
 *   <li>{@link AsrTranscriber} 缺省实现——OpenAI 兼容 transcription 薄实现
 *       （见接缝类注释的线上行为说明）。</li>
 *   <li>{@link ExtractPrompts}——config.yaml extract 段的 vendor 装载。</li>
 * </ul>
 */
@Configuration
public class AgentmWiring {

    @Bean
    public OllamaService ollamaService() {
        // 基址取 OLLAMA_BASE_URL（缺省 localhost:11434），
        // OLLAMA_OPTIONAL=true 时可用性失败不报错。单例语义由 bean 层承担。
        return OllamaService.getOllamaService();
    }

    @Bean
    public AsrTranscriber asrTranscriber(SsrfGuard ssrfGuard) {
        return new AsrTranscriber.OpenAiAsrTranscriber(ssrfGuard);
    }

    @Bean
    public ExtractPrompts extractPrompts() {
        return new ExtractPrompts();
    }
}
