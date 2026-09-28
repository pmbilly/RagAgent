package com.ragagent.llm.chat;

import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;
import com.ragagent.llm.LlmChatClient;
import com.ragagent.llm.domain.ChatConfig;
import com.ragagent.llm.limiter.ConcurrencyGovernor;
import com.ragagent.llm.ollama.OllamaService;
import com.ragagent.llm.provider.ProviderName;
import com.ragagent.llm.provider.ProviderRegistry;

/**
 * 聊天实例工厂（对照 Go chat.NewChat / NewRemoteChat，
 * internal/models/chat/chat.go:167-198）。
 *
 * 两级分发：
 * 1. **传输层**：Source = "local" → Ollama；"remote" → 远程厂商；其他报错
 *    （types.ModelSource 有很多值，但只有这两个是合法的 chat source）
 * 2. **协议层**：Anthropic 走独立的 Messages 协议实现；其余 27 个 OpenAI 兼容
 *    厂商统一由 {@link RemoteApiChat} 处理，厂商特有行为在构造时通过
 *    {@link ProviderAdapter} 解析
 *
 * 装饰器从内到外：内层实现 → 并发闸门（最外，只包住真正的 provider 往返）。
 *
 * **Java 侧未实现的两个装饰器（已在约定文档 §9 登记）**：
 * - `wrapChatDebug`（Go 的 llm_debug_wrapper）：受 LLMDebugEnabled 环境变量控制，
 *   未启用时 Go 也只是返回原对象——Java 侧直接省略，等价于"未启用"
 * - `wrapChatLangfuse`：Go 在未启用 tracing 时不安装包装器（零成本）。Java 侧
 *   的 chat/embedding/rerank 包装发生在 ModelRuntimeFactory 装饰层
 *   （LangfuseChatClient/Embedder/Reranker.wrap，dbf4cff 起），不再在本类包
 */
public final class LlmChatClients {

    private LlmChatClients() {
    }

    /**
     * 创建聊天实例（对照 Go NewChat）。
     *
     * @param config        模型配置；null 时报错
     * @param ollamaService 本地 Ollama 服务（仅在 source=local 时使用，可为 null）
     * @param governor      并发闸门（进程级单例）
     */
    public static LlmChatClient create(ChatConfig config, OllamaService ollamaService,
                                       ConcurrencyGovernor governor) {
        if (config == null) {
            throw new BizException(AppError.badRequest("chat config is required"));
        }
        String source = config.getSource() == null ? "" : config.getSource().toLowerCase();
        LlmChatClient client = switch (source) {
            case "local" -> new OllamaChat(config, ollamaService);
            case "remote" -> newRemoteChat(config);
            default -> throw new BizException(AppError.badRequest(
                    "unsupported chat model source: " + config.getSource()));
        };
        return new ConcurrencyChatClient(client, config.getMaxConcurrency(), governor);
    }

    /** 按 provider 创建远程聊天实例（对照 Go NewRemoteChat）。 */
    public static LlmChatClient newRemoteChat(ChatConfig config) {
        ProviderName providerName = ProviderName.fromValue(config.getProvider());
        if (providerName == null) {
            providerName = ProviderRegistry.detectProvider(config.getBaseUrl());
        }
        if (providerName == ProviderName.ANTHROPIC) {
            return new AnthropicChat(config);
        }
        return new RemoteApiChat(config);
    }
}
