package com.ragagent.llm.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务商注册表 + DetectProvider。
 *
 * ⚠️ 与 com.ragagent.model.service.ProviderRegistry 不是一回事：那个是模型模块的 HTTP 目录
 * 响应（golden 数据 + 前端字符串映射），本类供运行时路由与校验使用。
 *
 * 注册由本类静态块按 {@link #allProviders()} 的顺序显式完成
 * （新增厂商时两处都要加：本静态块 + {@link #allProviders()}）。
 * 并发访问用 {@link ConcurrentHashMap}。
 */
public final class ProviderRegistry {

    private static final Map<ProviderName, Provider> REGISTRY = new ConcurrentHashMap<>();

    static {
        // 顺序 = allProviders() 声明序（List/ListByModelType 的输出顺序由 allProviders() 决定）
        register(new GenericProvider());
        register(new WeKnoraCloudProvider());
        register(new AliyunProvider());
        register(new ZhipuProvider());
        register(new VolcengineProvider());
        register(new HunyuanProvider());
        register(new SiliconFlowProvider());
        register(new DeepSeekProvider());
        register(new MiniMaxProvider());
        register(new MoonshotProvider());
        register(new ModelScopeProvider());
        register(new QianfanProvider());
        register(new QiniuProvider());
        register(new OpenAIProvider());
        register(new AnthropicProvider());
        register(new GeminiProvider());
        register(new OpenRouterProvider());
        register(new LiteLLMProvider());
        register(new RequestyProvider());
        register(new JinaProvider());
        register(new MimoProvider());
        register(new LongCatProvider());
        register(new LKEAPProvider());
        register(new GPUStackProvider());
        register(new NvidiaProvider());
        register(new NovitaProvider());
        register(new AzureOpenAIProvider());
    }

    private ProviderRegistry() {
    }

    /**
     * 所有注册的提供者名称，顺序即 List/ListByModelType 的输出顺序。
     * 注意 weknoracloud 排在第二位（紧跟在 generic 之后）。
     */
    public static List<ProviderName> allProviders() {
        return List.of(
                ProviderName.GENERIC,
                ProviderName.WEKNORA_CLOUD,
                ProviderName.ALIYUN,
                ProviderName.ZHIPU,
                ProviderName.VOLCENGINE,
                ProviderName.HUNYUAN,
                ProviderName.SILICONFLOW,
                ProviderName.DEEPSEEK,
                ProviderName.MINIMAX,
                ProviderName.MOONSHOT,
                ProviderName.MODELSCOPE,
                ProviderName.QIANFAN,
                ProviderName.QINIU,
                ProviderName.OPENAI,
                ProviderName.ANTHROPIC,
                ProviderName.GEMINI,
                ProviderName.OPENROUTER,
                ProviderName.LITELLM,
                ProviderName.REQUESTY,
                ProviderName.JINA,
                ProviderName.MIMO,
                ProviderName.LONGCAT,
                ProviderName.LKEAP,
                ProviderName.GPUSTACK,
                ProviderName.NVIDIA,
                ProviderName.NOVITA,
                ProviderName.AZURE_OPEN_AI);
    }

    /** 按 name 覆盖注册（后注册者胜） */
    public static void register(Provider p) {
        REGISTRY.put(p.info().name(), p);
    }

    /** 未注册返回空 */
    public static Optional<Provider> get(ProviderName name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGISTRY.get(name));
    }

    /**
     * 未找到时返回 generic（静态块保证 generic 恒注册，故非 null）。
     */
    public static Provider getOrDefault(ProviderName name) {
        return get(name).orElseGet(() -> REGISTRY.get(ProviderName.GENERIC));
    }

    /** 按 AllProviders() 顺序返回已注册提供者的元数据 */
    public static List<ProviderInfo> list() {
        List<ProviderInfo> result = new ArrayList<>();
        for (ProviderName name : allProviders()) {
            Provider p = REGISTRY.get(name);
            if (p != null) {
                result.add(p.info());
            }
        }
        return result;
    }

    /** 按 AllProviders() 顺序返回支持指定模型类型的提供者 */
    public static List<ProviderInfo> listByModelType(ModelType modelType) {
        List<ProviderInfo> result = new ArrayList<>();
        for (ProviderName name : allProviders()) {
            Provider p = REGISTRY.get(name);
            if (p == null) {
                continue;
            }
            ProviderInfo info = p.info();
            if (info.supportsModelType(modelType)) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * 通过 BaseURL 检测服务商。
     *
     * 判定顺序即语义（先命中者胜），不得重排或合并：
     * dashscope → bigmodel/zhipu → openrouter → litellm → requesty → siliconflow → jina →
     * azure → openai → anthropic → deepseek → gemini → volces/volcengine → hunyuan →
     * minimax → xiaomimimo → gpustack → modelscope → qiniu → moonshot → qianfan/baidubce →
     * longcat → lkeap → nvidia → novita → weknora；全部未命中返回 generic。
     *
     * 匹配为大小写敏感的子串匹配（String.contains）。
     */
    public static ProviderName detectProvider(String baseURL) {
        String url = baseURL == null ? "" : baseURL;
        if (containsAny(url, "dashscope.aliyuncs.com")) {
            return ProviderName.ALIYUN;
        } else if (containsAny(url, "open.bigmodel.cn", "zhipu")) {
            return ProviderName.ZHIPU;
        } else if (containsAny(url, "openrouter.ai")) {
            return ProviderName.OPENROUTER;
        } else if (containsAny(url, "litellm")) {
            // Hostname/path containing "litellm" (including the catalog placeholder
            // your_litellm_proxy). Loopback URLs such as localhost:4000 stay generic
            // because they are SSRF-blocked unless explicitly whitelisted.
            return ProviderName.LITELLM;
        } else if (containsAny(url, "router.requesty.ai", "requesty.ai")) {
            return ProviderName.REQUESTY;
        } else if (containsAny(url, "siliconflow.cn")) {
            return ProviderName.SILICONFLOW;
        } else if (containsAny(url, "api.jina.ai")) {
            return ProviderName.JINA;
        } else if (containsAny(url, "openai.azure.com")) {
            return ProviderName.AZURE_OPEN_AI;
        } else if (containsAny(url, "api.openai.com")) {
            return ProviderName.OPENAI;
        } else if (containsAny(url, "api.anthropic.com")) {
            return ProviderName.ANTHROPIC;
        } else if (containsAny(url, "api.deepseek.com")) {
            return ProviderName.DEEPSEEK;
        } else if (containsAny(url, "generativelanguage.googleapis.com")) {
            return ProviderName.GEMINI;
        } else if (containsAny(url, "volces.com", "volcengine")) {
            return ProviderName.VOLCENGINE;
        } else if (containsAny(url, "hunyuan.cloud.tencent.com")) {
            return ProviderName.HUNYUAN;
        } else if (containsAny(url, "minimax.io", "minimaxi.com")) {
            return ProviderName.MINIMAX;
        } else if (containsAny(url, "xiaomimimo.com")) {
            return ProviderName.MIMO;
        } else if (containsAny(url, "gpustack")) {
            return ProviderName.GPUSTACK;
        } else if (containsAny(url, "modelscope.cn")) {
            return ProviderName.MODELSCOPE;
        } else if (containsAny(url, "qiniuapi.com", "qiniu")) {
            return ProviderName.QINIU;
        } else if (containsAny(url, "moonshot.ai")) {
            return ProviderName.MOONSHOT;
        } else if (containsAny(url, "qianfan.baidubce.com", "baidubce.com")) {
            return ProviderName.QIANFAN;
        } else if (containsAny(url, "longcat.chat")) {
            return ProviderName.LONGCAT;
        } else if (containsAny(url, "lkeap.cloud.tencent.com", "api.lkeap", "lkeap.tencentcloudapi.com")) {
            return ProviderName.LKEAP;
        } else if (containsAny(url, "nvidia.com")) {
            return ProviderName.NVIDIA;
        } else if (containsAny(url, "api.novita.ai", "novita.ai")) {
            return ProviderName.NOVITA;
        } else if (containsAny(url, "weknora.weixin.qq.com")) {
            return ProviderName.WEKNORA_CLOUD;
        }
        return ProviderName.GENERIC;
    }

    /** 任一子串命中即真（大小写敏感） */
    private static boolean containsAny(String s, String... substrs) {
        for (String sub : substrs) {
            if (s.contains(sub)) {
                return true;
            }
        }
        return false;
    }
}
