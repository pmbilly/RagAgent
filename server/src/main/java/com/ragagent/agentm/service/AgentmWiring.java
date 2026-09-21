package com.ragagent.agentm.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ragagent.common.security.SsrfGuard;
import com.ragagent.llm.ollama.OllamaService;

/**
 * initialization 收尾批（W5b）的进程级装配：
 * <ul>
 *   <li>{@link OllamaService} 单例 bean——对照 Go container.Provide(ollama.GetOllamaService)
 *       的<b>单例</b>供给（container.go:1552）。此前 Java 侧只有
 *       {@code ObjectProvider<OllamaService>} 空注入；bean 落地后 chat/embed 管线的
 *       {@code getIfAvailable()} 与 Go 同形（local 源模型拿得到真实 ollama 服务）。
 *       注意 {@code isAvailable} 标志是跨请求共享状态——CheckOllamaModels 的
 *       "已可用则跳过 StartService" 分支依赖这一点，不能每请求新建。</li>
 *   <li>{@link AsrTranscriber} 缺省实现——OpenAI 兼容 transcription 薄复刻
 *       （见接缝类注释的降级范围说明）。</li>
 *   <li>{@link ExtractPrompts}——config.yaml extract 段的 vendor 装载。</li>
 * </ul>
 */
@Configuration
public class AgentmWiring {

    @Bean
    public OllamaService ollamaService() {
        // 对照 GetOllamaService：基址取 OLLAMA_BASE_URL（缺省 localhost:11434），
        // OLLAMA_OPTIONAL=true 时可用性失败不报错。Go 每次调用新建、容器层单例化，
        // 这里在 bean 层承担同一单例语义。
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
