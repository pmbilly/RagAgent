package com.ragagent.model.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ragagent.model.dto.ModelProviderDTO;
import org.springframework.stereotype.Component;

/**
 * 模型厂商注册表（对照 Go internal/models/provider 的 registry + 各 provider 文件 Info()）。
 *
 * 静态数据由 golden（contracts/model-providers.json，Go dev server 2026-09-17 实录）生成，
 * 顺序 = Go AllProviders() 声明序；defaultUrls/modelTypes 输出形态（前端字符串）与 golden 字节一致。
 * 阶段 2 仅消费 List / ListByModelType；DetectProvider / ValidateConfig 随阶段 7 运行时客户端翻译。
 */
@Component
public class ProviderRegistry {

    /** 后端类型 → 前端字符串（对照 modelTypeToFrontend） */
    public static String toFrontend(String backendType) {
        return switch (backendType) {
            case "KnowledgeQA" -> "chat";
            case "Embedding" -> "embedding";
            case "Rerank" -> "rerank";
            case "VLLM" -> "vllm";
            case "ASR" -> "asr";
            default -> backendType;
        };
    }

    /** 前端查询参数 → 后端类型（对照 ListModelProviders 的映射） */
    public static String queryToBackend(String modelType) {
        return switch (modelType) {
            case "chat" -> "KnowledgeQA";
            case "embedding" -> "Embedding";
            case "rerank" -> "Rerank";
            case "vllm" -> "VLLM";
            case "asr" -> "ASR";
            default -> modelType;
        };
    }

    private record ProviderEntry(String value, String label, String description,
                                 Map<String, String> defaultUrls, List<String> modelTypes,
                                 List<String> backendTypes) {}

    // @formatter:off
    private static final List<ProviderEntry> ENTRIES = List.of(
            new ProviderEntry("generic", "自定义 (OpenAI兼容接口)", "Generic API endpoint (OpenAI-compatible)",
                    Map.of(),
                    List.of("chat", "embedding", "rerank", "vllm", "asr"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR")),

            new ProviderEntry("weknoracloud", "WeKnoraCloud", "WeKnora云服务，模型：chat, embedding, rerank, vlm",
                    Map.ofEntries(Map.entry("chat", "https://weknora.weixin.qq.com"), Map.entry("embedding", "https://weknora.weixin.qq.com"), Map.entry("rerank", "https://weknora.weixin.qq.com"), Map.entry("vllm", "https://weknora.weixin.qq.com")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("aliyun", "阿里云 DashScope", "qwen-plus, tongyi-embedding-vision-plus, qwen3-rerank, etc.",
                    Map.ofEntries(Map.entry("chat", "https://dashscope.aliyuncs.com/compatible-mode/v1"), Map.entry("embedding", "https://dashscope.aliyuncs.com/compatible-mode/v1"), Map.entry("rerank", "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"), Map.entry("vllm", "https://dashscope.aliyuncs.com/compatible-mode/v1")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("zhipu", "智谱 BigModel", "glm-4.7, embedding-3, rerank, etc.",
                    Map.ofEntries(Map.entry("chat", "https://open.bigmodel.cn/api/paas/v4"), Map.entry("embedding", "https://open.bigmodel.cn/api/paas/v4"), Map.entry("rerank", "https://open.bigmodel.cn/api/paas/v4/rerank"), Map.entry("vllm", "https://open.bigmodel.cn/api/paas/v4")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("volcengine", "火山引擎 Volcengine", "doubao-1-5-pro-32k-250115, doubao-embedding-vision-250615, doubao-seed-rerank, etc.",
                    Map.ofEntries(Map.entry("chat", "https://ark.cn-beijing.volces.com/api/v3"), Map.entry("embedding", "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal"), Map.entry("rerank", "https://api-knowledgebase.mlp.cn-beijing.volces.com"), Map.entry("vllm", "https://ark.cn-beijing.volces.com/api/v3")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("hunyuan", "腾讯混元 Hunyuan", "hunyuan-pro, hunyuan-standard, hunyuan-embedding, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.hunyuan.cloud.tencent.com/v1"), Map.entry("embedding", "https://api.hunyuan.cloud.tencent.com/v1")),
                    List.of("chat", "embedding"),
                    List.of("KnowledgeQA", "Embedding")),

            new ProviderEntry("siliconflow", "硅基流动 SiliconFlow", "deepseek-ai/DeepSeek-V3.1, etc.",
                    Map.ofEntries(Map.entry("asr", "https://api.siliconflow.cn/v1"), Map.entry("chat", "https://api.siliconflow.cn/v1"), Map.entry("embedding", "https://api.siliconflow.cn/v1"), Map.entry("rerank", "https://api.siliconflow.cn/v1"), Map.entry("vllm", "https://api.siliconflow.cn/v1")),
                    List.of("chat", "embedding", "rerank", "vllm", "asr"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR")),

            new ProviderEntry("deepseek", "DeepSeek", "deepseek-chat, deepseek-reasoner, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.deepseek.com/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("minimax", "MiniMax", "MiniMax-M3, MiniMax-M2.7, MiniMax-M2.7-highspeed, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.minimaxi.com/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("moonshot", "月之暗面 Moonshot", "kimi-k2-turbo-preview, moonshot-v1-8k-vision-preview, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.moonshot.ai/v1"), Map.entry("vllm", "https://api.moonshot.ai/v1")),
                    List.of("chat", "vllm"),
                    List.of("KnowledgeQA", "VLLM")),

            new ProviderEntry("modelscope", "魔搭 ModelScope", "Qwen/Qwen3-8B, Qwen/Qwen3-Embedding-8B, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api-inference.modelscope.cn/v1"), Map.entry("embedding", "https://api-inference.modelscope.cn/v1"), Map.entry("vllm", "https://api-inference.modelscope.cn/v1")),
                    List.of("chat", "embedding", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "VLLM")),

            new ProviderEntry("qianfan", "百度千帆 Baidu Cloud", "ernie-5.0-thinking-preview, embedding-v1, bce-reranker-base, etc.",
                    Map.ofEntries(Map.entry("chat", "https://qianfan.baidubce.com/v2"), Map.entry("embedding", "https://qianfan.baidubce.com/v2"), Map.entry("rerank", "https://qianfan.baidubce.com/v2"), Map.entry("vllm", "https://qianfan.baidubce.com/v2")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("qiniu", "七牛云 Qiniu", "deepseek/deepseek-v3.2-251201, z-ai/glm-4.7, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.qnaigc.com/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("openai", "OpenAI", "gpt-5.2, gpt-5-mini, etc.",
                    Map.ofEntries(Map.entry("asr", "https://api.openai.com/v1"), Map.entry("chat", "https://api.openai.com/v1"), Map.entry("embedding", "https://api.openai.com/v1"), Map.entry("rerank", "https://api.openai.com/v1"), Map.entry("vllm", "https://api.openai.com/v1")),
                    List.of("chat", "embedding", "rerank", "vllm", "asr"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR")),

            new ProviderEntry("anthropic", "Anthropic", "Claude models via native Anthropic Messages API",
                    Map.ofEntries(Map.entry("chat", "https://api.anthropic.com/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("gemini", "Google Gemini", "gemini-3-flash-preview, gemini-2.5-pro, gemini-embedding-2, etc.",
                    Map.ofEntries(Map.entry("chat", "https://generativelanguage.googleapis.com/v1beta/openai"), Map.entry("embedding", "https://generativelanguage.googleapis.com/v1beta")),
                    List.of("chat", "embedding"),
                    List.of("KnowledgeQA", "Embedding")),

            new ProviderEntry("openrouter", "OpenRouter", "openai/gpt-5.2-chat, google/gemini-3-flash-preview, etc.",
                    Map.ofEntries(Map.entry("chat", "https://openrouter.ai/api/v1"), Map.entry("embedding", "https://openrouter.ai/api/v1"), Map.entry("vllm", "https://openrouter.ai/api/v1")),
                    List.of("chat", "embedding", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "VLLM")),

            new ProviderEntry("litellm", "LiteLLM", "Self-hosted LiteLLM proxy: one OpenAI-compatible endpoint to 100+ providers.",
                    Map.ofEntries(Map.entry("chat", "http://your_litellm_proxy/v1"), Map.entry("embedding", "http://your_litellm_proxy/v1"), Map.entry("vllm", "http://your_litellm_proxy/v1")),
                    List.of("chat", "embedding", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "VLLM")),

            new ProviderEntry("requesty", "Requesty", "openai/gpt-4o-mini, anthropic/claude-sonnet-4-5, etc.",
                    Map.ofEntries(Map.entry("chat", "https://router.requesty.ai/v1"), Map.entry("embedding", "https://router.requesty.ai/v1"), Map.entry("vllm", "https://router.requesty.ai/v1")),
                    List.of("chat", "embedding", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "VLLM")),

            new ProviderEntry("jina", "Jina", "jina-clip-v1, jina-embeddings-v2-base-zh, etc.",
                    Map.ofEntries(Map.entry("embedding", "https://api.jina.ai/v1"), Map.entry("rerank", "https://api.jina.ai/v1")),
                    List.of("embedding", "rerank"),
                    List.of("Embedding", "Rerank")),

            new ProviderEntry("mimo", "小米 MiMo", "mimo-v2-flash",
                    Map.ofEntries(Map.entry("chat", "https://api.xiaomimimo.com/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("longcat", "LongCat AI", "LongCat-Flash-Chat, LongCat-Flash-Thinking, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.longcat.chat/openai/v1")),
                    List.of("chat"),
                    List.of("KnowledgeQA")),

            new ProviderEntry("lkeap", "腾讯云 LKEAP", "DeepSeek-R1, DeepSeek-V3, lke-reranker-base 等",
                    Map.ofEntries(Map.entry("chat", "https://api.lkeap.cloud.tencent.com/v1"), Map.entry("rerank", "https://lkeap.tencentcloudapi.com")),
                    List.of("chat", "rerank"),
                    List.of("KnowledgeQA", "Rerank")),

            new ProviderEntry("gpustack", "GPUStack", "Choose your deployed model on GPUStack",
                    Map.ofEntries(Map.entry("asr", "http://your_gpustack_server_url/v1-openai"), Map.entry("chat", "http://your_gpustack_server_url/v1-openai"), Map.entry("embedding", "http://your_gpustack_server_url/v1-openai"), Map.entry("rerank", "http://your_gpustack_server_url/v1"), Map.entry("vllm", "http://your_gpustack_server_url/v1-openai")),
                    List.of("chat", "embedding", "rerank", "vllm", "asr"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM", "ASR")),

            new ProviderEntry("nvidia", "NVIDIA", "deepseek-ai-deepseek-v3_1, nv-embed-v1, rerank-qa-mistral-4b, etc.",
                    Map.ofEntries(Map.entry("chat", "https://integrate.api.nvidia.com/v1"), Map.entry("embedding", "https://integrate.api.nvidia.com/v1"), Map.entry("rerank", "https://ai.api.nvidia.com/v1/retrieval/nvidia/reranking"), Map.entry("vllm", "https://integrate.api.nvidia.com/v1")),
                    List.of("chat", "embedding", "rerank", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "Rerank", "VLLM")),

            new ProviderEntry("novita", "Novita AI", "moonshotai/kimi-k2.5, zai-org/glm-5, minimax/minimax-m2.7, qwen/qwen3-embedding-0.6b, etc.",
                    Map.ofEntries(Map.entry("chat", "https://api.novita.ai/openai/v1"), Map.entry("embedding", "https://api.novita.ai/openai/v1"), Map.entry("vllm", "https://api.novita.ai/openai/v1")),
                    List.of("chat", "embedding", "vllm"),
                    List.of("KnowledgeQA", "Embedding", "VLLM")),

            new ProviderEntry("azure_openai", "Azure OpenAI", "gpt-4o, gpt-4, text-embedding-ada-002, etc.",
                    Map.ofEntries(Map.entry("asr", "https://{resource}.openai.azure.com"), Map.entry("chat", "https://{resource}.openai.azure.com"), Map.entry("embedding", "https://{resource}.openai.azure.com"), Map.entry("rerank", "https://{resource}.openai.azure.com"), Map.entry("vllm", "https://{resource}.openai.azure.com")),
                    List.of("chat", "embedding", "vllm", "asr"),
                    List.of("KnowledgeQA", "Embedding", "VLLM", "ASR"))
    );
    // @formatter:on

    /** 对照 provider.List()：全量（注册表顺序） */
    public List<ModelProviderDTO> list() {
        return ENTRIES.stream().map(ProviderRegistry::toDTO).toList();
    }

    /** 对照 provider.ListByModelType()：按后端类型过滤，保持注册表顺序 */
    public List<ModelProviderDTO> listByModelType(String backendType) {
        return ENTRIES.stream()
                .filter(e -> e.backendTypes().contains(backendType))
                .map(ProviderRegistry::toDTO)
                .toList();
    }

    private static ModelProviderDTO toDTO(ProviderEntry e) {
        // defaultUrls 为 map：TreeMap 保证 JSON key 字母序（对照 Go map 序列化）
        return new ModelProviderDTO(e.value(), e.label(), e.description(),
                new TreeMap<>(e.defaultUrls()), e.modelTypes());
    }
}
