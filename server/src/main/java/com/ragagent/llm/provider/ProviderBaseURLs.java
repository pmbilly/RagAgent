package com.ragagent.llm.provider;

/**
 * 各厂商默认 BaseURL 常量表。
 *
 * 注意：Azure OpenAI 的 "https://{resource}.openai.azure.com" 是含资源名的模板，
 * 不在本表，见 {@link AzureOpenAIProvider}。
 */
public final class ProviderBaseURLs {

    // ---- Aliyun ----
    /** 阿里云 DashScope Chat/Embedding 的默认 BaseURL */
    public static final String ALIYUN_CHAT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    /** 阿里云 DashScope Rerank 的默认 BaseURL */
    public static final String ALIYUN_RERANK_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    // ---- Anthropic ----
    public static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1";

    // ---- DeepSeek ----
    /** DeepSeek 官方 API BaseURL */
    public static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";

    // ---- Gemini ----
    /** Google Gemini API BaseURL */
    public static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    /** Gemini OpenAI 兼容模式 BaseURL */
    public static final String GEMINI_OPENAI_COMPAT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai";

    // ---- GPUStack ----
    /** GPUStack API BaseURL (OpenAI 兼容模式) */
    public static final String GPUSTACK_BASE_URL = "http://your_gpustack_server_url/v1-openai";
    /** GPUStack Rerank API：虽兼容 OpenAI，但路径不同 (/v1/rerank 而非 /v1-openai/rerank) */
    public static final String GPUSTACK_RERANK_BASE_URL = "http://your_gpustack_server_url/v1";

    // ---- Hunyuan ----
    /** 腾讯混元 API BaseURL (OpenAI 兼容模式) */
    public static final String HUNYUAN_BASE_URL = "https://api.hunyuan.cloud.tencent.com/v1";

    // ---- Jina ----
    public static final String JINA_BASE_URL = "https://api.jina.ai/v1";

    // ---- LiteLLM ----
    /**
     * LiteLLM 占位地址，用户必须替换为可达的 LiteLLM 代理。回环默认值（localhost:4000）会被
     * SSRF 拒绝（除非加入 SSRF_WHITELIST），Docker 也无法通过 localhost 访问宿主代理。
     * 主机名含 "litellm"，故 DetectProvider 能识别该目录默认值。
     */
    public static final String LITELLM_BASE_URL = "http://your_litellm_proxy/v1";

    // ---- LKEAP ----
    /** 腾讯云知识引擎原子能力 (LKEAP) 兼容 OpenAI 协议的 BaseURL */
    public static final String LKEAP_BASE_URL = "https://api.lkeap.cloud.tencent.com/v1";
    /** 腾讯云知识引擎原子能力 Rerank API 域名（TC3 签名） */
    public static final String LKEAP_RERANK_BASE_URL = "https://lkeap.tencentcloudapi.com";

    // ---- LongCat ----
    public static final String LONGCAT_BASE_URL = "https://api.longcat.chat/openai/v1";

    // ---- Mimo ----
    /** 小米 Mimo API BaseURL */
    public static final String MIMO_BASE_URL = "https://api.xiaomimimo.com/v1";

    // ---- MiniMax ----
    /** MiniMax 国际版 API BaseURL */
    public static final String MINIMAX_BASE_URL = "https://api.minimax.io/v1";
    /** MiniMax 国内版 API BaseURL */
    public static final String MINIMAX_CN_BASE_URL = "https://api.minimaxi.com/v1";

    // ---- ModelScope ----
    /** ModelScope API BaseURL (OpenAI 兼容模式) */
    public static final String MODELSCOPE_BASE_URL = "https://api-inference.modelscope.cn/v1";

    // ---- Moonshot ----
    public static final String MOONSHOT_BASE_URL = "https://api.moonshot.ai/v1";

    // ---- Novita ----
    /** Novita OpenAI-compatible API BaseURL */
    public static final String NOVITA_OPENAI_BASE_URL = "https://api.novita.ai/openai/v1";

    // ---- NVIDIA ----
    /** NVIDIA Chat 的默认 BaseURL */
    public static final String NVIDIA_CHAT_BASE_URL = "https://integrate.api.nvidia.com/v1";
    /** NVIDIA Rerank 的默认 BaseURL */
    public static final String NVIDIA_RERANK_BASE_URL = "https://ai.api.nvidia.com/v1/retrieval/nvidia/reranking";

    // ---- OpenAI ----
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    // ---- OpenRouter ----
    public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

    // ---- Qianfan ----
    public static final String QIANFAN_BASE_URL = "https://qianfan.baidubce.com/v2";

    // ---- Qiniu ----
    /** 七牛云 API BaseURL (OpenAI 兼容模式) */
    public static final String QINIU_BASE_URL = "https://api.qnaigc.com/v1";

    // ---- Requesty ----
    public static final String REQUESTY_BASE_URL = "https://router.requesty.ai/v1";

    // ---- SiliconFlow ----
    public static final String SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1";

    // ---- Volcengine ----
    /** 火山引擎 Ark Chat API BaseURL (OpenAI 兼容模式) */
    public static final String VOLCENGINE_CHAT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    /** 火山引擎 Ark Multimodal Embedding API BaseURL */
    public static final String VOLCENGINE_EMBEDDING_BASE_URL =
            "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal";
    /** 火山引擎知识库托管 Rerank API BaseURL */
    public static final String VOLCENGINE_RERANK_BASE_URL = "https://api-knowledgebase.mlp.cn-beijing.volces.com";

    // ---- WeKnoraCloud ----
    /** WeKnoraCloud 服务硬编码 Base URL（统一入口，路径由各实现拼接） */
    public static final String WEKNORA_CLOUD_BASE_URL = "https://weknora.weixin.qq.com";

    // ---- Zhipu ----
    /** 智谱 AI Chat 的默认 BaseURL */
    public static final String ZHIPU_CHAT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    /** 智谱 AI Embedding 的默认 BaseURL */
    public static final String ZHIPU_EMBEDDING_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    /** 智谱 AI Rerank 的默认 BaseURL */
    public static final String ZHIPU_RERANK_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/rerank";

    private ProviderBaseURLs() {
    }
}
