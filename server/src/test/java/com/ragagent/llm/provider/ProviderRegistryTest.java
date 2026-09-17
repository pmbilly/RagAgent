package com.ragagent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ragagent.common.error.BizException;
import com.ragagent.model.domain.Model;

/**
 * 对照 Go internal/models/provider/provider_test.go（TestProviderRegistry / TestDetectProvider /
 * TestListByModelType / TestLiteLLMProviderValidation 的注册部分）与 TestLiteLLM 目录断言。
 * 纯单元测试，不依赖 Spring 上下文。
 */
class ProviderRegistryTest {

    /** 对照 Go TestProviderRegistry/default providers registered */
    @Test
    void defaultProvidersRegistered() {
        List<ProviderInfo> providers = ProviderRegistry.list();
        assertFalse(providers.isEmpty(), "should have registered providers");

        for (ProviderName name : List.of(ProviderName.OPENAI, ProviderName.ALIYUN,
                ProviderName.ZHIPU, ProviderName.GENERIC)) {
            Optional<Provider> p = ProviderRegistry.get(name);
            assertTrue(p.isPresent(), "provider " + name + " should be registered");
            assertNotNull(p.get(), "provider " + name + " should not be null");
        }
    }

    /** List() 顺序 = Go AllProviders() 声明序，且全部 27 个厂商都已注册 */
    @Test
    void listFollowsAllProvidersOrder() {
        assertEquals(27, ProviderRegistry.allProviders().size());
        assertEquals(ProviderRegistry.allProviders(),
                ProviderRegistry.list().stream().map(ProviderInfo::name).toList());
    }

    /** 对照 Go TestProviderRegistry/GetOrDefault fallback */
    @Test
    void getOrDefaultFallsBackToGeneric() {
        // Go: GetOrDefault("nonexistent") → 未注册 → generic
        assertSame(ProviderName.GENERIC,
                ProviderRegistry.getOrDefault(ProviderName.fromValue("nonexistent")).info().name());
        assertSame(ProviderName.GENERIC, ProviderRegistry.getOrDefault(null).info().name());
    }

    /**
     * 对照 Go TestDetectProvider 的表（逐条照抄，含顺序敏感用例），并按 Go 的 switch 顺序补充
     * 容易踩坑的用例（azure vs openai、litellm 占位符、local→generic）。
     */
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "https://api.openai.com/v1, OPENAI",
            "https://api.anthropic.com/v1, ANTHROPIC",
            "https://openrouter.ai/api/v1, OPENROUTER",
            "https://litellm.example.com/v1, LITELLM",
            "http://your_litellm_proxy/v1, LITELLM",
            "http://localhost:4000/v1, GENERIC",
            "https://router.requesty.ai/v1, REQUESTY",
            "https://dashscope.aliyuncs.com/compatible-mode/v1, ALIYUN",
            "https://open.bigmodel.cn/api/paas/v4, ZHIPU",
            "https://api.deepseek.com/v1, DEEPSEEK",
            "https://generativelanguage.googleapis.com/v1beta/openai, GEMINI",
            "https://ark.cn-beijing.volces.com/api/v3, VOLCENGINE",
            "https://api.hunyuan.cloud.tencent.com/v1, HUNYUAN",
            "https://api.minimaxi.com/v1, MINIMAX",
            "https://api.minimax.io/v1, MINIMAX",
            "https://api.xiaomimimo.com/v1, MIMO",
            "https://custom-endpoint.example.com/v1, GENERIC",
            "http://localhost:11434/v1, GENERIC",
            "https://integrate.api.nvidia.com/v1, NVIDIA",
            "https://ai.api.nvidia.com/v1/retrieval/nvidia/reranking, NVIDIA",
            // 顺序语义：openai.azure.com 必须先于 api.openai.com 命中
            "https://myres.openai.azure.com/openai/deployments/gpt-4o, AZURE_OPEN_AI",
            // 其余厂商（Go switch 里靠后的分支）
            "https://api.siliconflow.cn/v1, SILICONFLOW",
            "https://api.jina.ai/v1, JINA",
            "https://api-inference.modelscope.cn/v1, MODELSCOPE",
            "https://api.moonshot.ai/v1, MOONSHOT",
            "https://qianfan.baidubce.com/v2, QIANFAN",
            "https://api.longcat.chat/openai/v1, LONGCAT",
            "https://api.lkeap.cloud.tencent.com/v1, LKEAP",
            "https://lkeap.tencentcloudapi.com, LKEAP",
            "https://api.novita.ai/openai/v1, NOVITA",
            "https://weknora.weixin.qq.com, WEKNORA_CLOUD",
            "http://your_gpustack_server_url/v1-openai, GPUSTACK"
    })
    void detectProvider(String url, ProviderName expected) {
        assertSame(expected, ProviderRegistry.detectProvider(url));
    }

    /**
     * ⚠️ Go 侧不一致（照抄，勿"修正"）：七牛云的默认 URL 是 https://api.qnaigc.com/v1，
     * 而 DetectProvider 只认子串 "qiniuapi.com" / "qiniu" —— 两者互不包含，所以把七牛云的
     * 目录默认地址喂回 DetectProvider，Go 会返回 generic（QINIU 分支永远命中不了自家默认 URL）。
     * Java 版按 Go 行为照抄：此断言即该行为的 golden。
     */
    @Test
    void qiniuDefaultUrlIsNotDetectedAsQiniu() {
        assertEquals("https://api.qnaigc.com/v1", ProviderBaseURLs.QINIU_BASE_URL);
        assertSame(ProviderName.GENERIC, ProviderRegistry.detectProvider(ProviderBaseURLs.QINIU_BASE_URL));
        // 只有显式带 "qiniu" 子串的地址才命中 QINIU 分支
        assertSame(ProviderName.QINIU, ProviderRegistry.detectProvider("https://api.qiniuapi.com/v1"));
    }

    /**
     * 各厂商「目录默认 URL → DetectProvider」往返：除七牛云（见上）外都必须认回自己。
     * 这是 DetectProvider 匹配表与各 provider 的 DefaultURLs 保持一致性的哨兵测试。
     */
    @Test
    void defaultUrlsRoundTripToTheirProvider() {
        List<ProviderName> exceptions = List.of(ProviderName.QINIU);
        for (ProviderName name : ProviderRegistry.allProviders()) {
            ProviderInfo info = ProviderRegistry.getOrDefault(name).info();
            String url = info.getDefaultURL(ModelType.KNOWLEDGE_QA);
            if (url.isEmpty() || exceptions.contains(name)) {
                continue;
            }
            assertSame(name, ProviderRegistry.detectProvider(url),
                    name + " 的默认 URL " + url + " 未能认回自己");
        }
    }

    /** 对照 Go TestListByModelType/chat models：支持 chat 的厂商不少于 9 个 */
    @Test
    void listByModelTypeChat() {
        List<ProviderInfo> providers = ProviderRegistry.listByModelType(ModelType.KNOWLEDGE_QA);
        assertFalse(providers.isEmpty());
        assertTrue(providers.size() >= 9, "chat providers >= 9, actual=" + providers.size());
    }

    /** 对照 Go TestListByModelType/rerank models：三家 Rerank URL 必须各归其主 */
    @Test
    void listByModelTypeRerank() {
        List<ProviderInfo> providers = ProviderRegistry.listByModelType(ModelType.RERANK);
        assertFalse(providers.isEmpty());

        boolean foundAliyun = false;
        boolean foundLKEAP = false;
        boolean foundVolcengine = false;
        for (ProviderInfo p : providers) {
            if (p.name() == ProviderName.ALIYUN) {
                foundAliyun = true;
            }
            if (p.name() == ProviderName.LKEAP) {
                foundLKEAP = true;
                assertEquals(ProviderBaseURLs.LKEAP_RERANK_BASE_URL, p.getDefaultURL(ModelType.RERANK));
            }
            if (p.name() == ProviderName.VOLCENGINE) {
                foundVolcengine = true;
                assertEquals(ProviderBaseURLs.VOLCENGINE_RERANK_BASE_URL, p.getDefaultURL(ModelType.RERANK));
            }
        }
        assertTrue(foundAliyun, "Aliyun should support rerank");
        assertTrue(foundLKEAP, "LKEAP should support rerank");
        assertTrue(foundVolcengine, "Volcengine should support rerank");
    }

    /** 对照 Go TestListByModelType/embedding models include openrouter */
    @Test
    void listByModelTypeEmbeddingIncludesOpenRouter() {
        List<ProviderInfo> providers = ProviderRegistry.listByModelType(ModelType.EMBEDDING);
        assertFalse(providers.isEmpty());

        boolean found = false;
        for (ProviderInfo p : providers) {
            if (p.name() == ProviderName.OPENROUTER) {
                found = true;
                assertEquals(ProviderBaseURLs.OPENROUTER_BASE_URL, p.getDefaultURL(ModelType.EMBEDDING));
                break;
            }
        }
        assertTrue(found, "OpenRouter should support embedding");
    }

    /** 对照 Go TestListByModelType/embedding models include gemini（走原生 Gemini API，非兼容端点） */
    @Test
    void listByModelTypeEmbeddingIncludesGemini() {
        List<ProviderInfo> providers = ProviderRegistry.listByModelType(ModelType.EMBEDDING);
        assertFalse(providers.isEmpty());

        boolean found = false;
        for (ProviderInfo p : providers) {
            if (p.name() == ProviderName.GEMINI) {
                found = true;
                assertEquals(ProviderBaseURLs.GEMINI_BASE_URL, p.getDefaultURL(ModelType.EMBEDDING));
                break;
            }
        }
        assertTrue(found, "Gemini should support embedding via the native Gemini API");
    }

    /** 对照 Go TestLiteLLMProviderValidation/info + registered and listed */
    @Test
    void litellmProvider() {
        Provider p = new LiteLLMProvider();
        ProviderInfo info = p.info();
        assertEquals(ProviderName.LITELLM, info.name());
        assertEquals("LiteLLM", info.displayName());
        assertTrue(info.requiresAuth());
        assertEquals(ProviderBaseURLs.LITELLM_BASE_URL, info.defaultUrls().get(ModelType.KNOWLEDGE_QA));
        assertEquals(ProviderBaseURLs.LITELLM_BASE_URL, info.defaultUrls().get(ModelType.EMBEDDING));
        assertEquals(ProviderBaseURLs.LITELLM_BASE_URL, info.defaultUrls().get(ModelType.VLLM));
        assertTrue(info.modelTypes().contains(ModelType.KNOWLEDGE_QA));
        assertTrue(info.modelTypes().contains(ModelType.EMBEDDING));
        assertTrue(info.modelTypes().contains(ModelType.VLLM));

        Optional<Provider> got = ProviderRegistry.get(ProviderName.LITELLM);
        assertTrue(got.isPresent());
        assertNotNull(got.get());

        assertTrue(ProviderRegistry.listByModelType(ModelType.KNOWLEDGE_QA).stream()
                .anyMatch(i -> i.name() == ProviderName.LITELLM), "LiteLLM should appear for chat models");
        assertTrue(ProviderRegistry.listByModelType(ModelType.EMBEDDING).stream()
                .anyMatch(i -> i.name() == ProviderName.LITELLM), "LiteLLM should appear for embedding models");
    }

    /** GetDefaultURL 回退语义（对照 Go (*ProviderInfo).GetDefaultURL）：缺失类型回退 Chat，再缺失 "" */
    @Test
    void getDefaultUrlFallsBackToChat() {
        ProviderInfo info = new DeepSeekProvider().info();
        // DeepSeek 只声明了 Chat URL：其它类型回退到它
        assertEquals(ProviderBaseURLs.DEEPSEEK_BASE_URL, info.getDefaultURL(ModelType.VLLM));
        // 完全没有 URL 的 provider → ""
        assertEquals("", new GenericProvider().info().getDefaultURL(ModelType.KNOWLEDGE_QA));
    }

    /** 全部注册厂商的信息自洽：displayName/description 非空，Deprecated 之外的默认 URL 非占位 */
    @ParameterizedTest
    @ValueSource(strings = {"GENERIC", "WEKNORA_CLOUD", "ALIYUN", "ZHIPU", "VOLCENGINE", "HUNYUAN",
            "SILICONFLOW", "DEEPSEEK", "MINIMAX", "MOONSHOT", "MODELSCOPE", "QIANFAN", "QINIU",
            "OPENAI", "ANTHROPIC", "GEMINI", "OPENROUTER", "LITELLM", "REQUESTY", "JINA", "MIMO",
            "LONGCAT", "LKEAP", "GPUSTACK", "NVIDIA", "NOVITA", "AZURE_OPEN_AI"})
    void everyProviderIsConsistent(ProviderName name) {
        ProviderInfo info = ProviderRegistry.getOrDefault(name).info();
        assertEquals(name, info.name(), "注册表 key 必须与 info().Name 一致");
        assertFalse(info.displayName().isEmpty());
        assertFalse(info.description().isEmpty());
        assertFalse(info.modelTypes().isEmpty());
    }

    /** 对照 Go NewConfigFromModel：provider 为空则用 BaseURL 探测；model 为 nil 报错 */
    @Test
    void newConfigFromModel() {
        Model model = new Model();
        model.setId("model-1");
        model.setName("gpt-4o");
        model.getParameters().setBaseUrl("https://api.openai.com/v1");
        model.getParameters().setApiKey("sk-test");

        Config detected = Config.fromModel(model);
        assertEquals(ProviderName.OPENAI, detected.provider());
        assertEquals("https://api.openai.com/v1", detected.baseUrl());
        assertEquals("sk-test", detected.apiKey());
        assertEquals("gpt-4o", detected.modelName());
        assertEquals("model-1", detected.modelId());

        // provider 显式填写时以它为准（不做 URL 探测）
        model.getParameters().setProvider("deepseek");
        assertEquals(ProviderName.DEEPSEEK, Config.fromModel(model).provider());

        assertThrows(BizException.class, () -> Config.fromModel(null));
    }
}
