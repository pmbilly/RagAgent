package com.ragagent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ragagent.common.error.BizException;

/**
 * 对照 Go internal/models/provider/provider_test.go 的各厂商 ValidateConfig/Info 子测试
 * （TestAnthropicProviderValidation / TestOpenAIProviderValidation / TestAliyunProviderValidation /
 * TestMiniMaxProviderValidation / TestZhipuProviderValidation / TestRequestyProviderValidation）。
 * 校验文案与 Go 的 fmt.Errorf 逐字一致。
 */
class ProviderValidationTest {

    private static Config config(String apiKey, String modelName) {
        return new Config(null, "", apiKey, modelName, "", null);
    }

    private static String messageOf(BizException e) {
        return e.appError().message();
    }

    // ---------------- Anthropic ----------------

    @Test
    void anthropicProviderValidation() {
        AnthropicProvider p = new AnthropicProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("sk-ant-test", "claude-sonnet-4-5")));

        BizException missingKey = assertThrows(BizException.class,
                () -> p.validateConfig(config("", "claude-sonnet-4-5")));
        assertTrue(messageOf(missingKey).contains("API key"), messageOf(missingKey));

        ProviderInfo info = p.info();
        assertEquals(ProviderName.ANTHROPIC, info.name());
        assertEquals(ProviderBaseURLs.ANTHROPIC_BASE_URL, info.getDefaultURL(ModelType.KNOWLEDGE_QA));
        assertTrue(info.modelTypes().contains(ModelType.KNOWLEDGE_QA));
        assertTrue(info.requiresAuth());
    }

    // ---------------- OpenAI ----------------

    @Test
    void openAiProviderValidation() {
        OpenAIProvider p = new OpenAIProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("sk-test", "gpt-4")));

        BizException missingKey = assertThrows(BizException.class,
                () -> p.validateConfig(config("", "gpt-4")));
        assertTrue(messageOf(missingKey).contains("API key"), messageOf(missingKey));

        BizException missingModel = assertThrows(BizException.class,
                () -> p.validateConfig(config("sk-test", "")));
        assertTrue(messageOf(missingModel).contains("model name"), messageOf(missingModel));
    }

    // ---------------- Aliyun ----------------

    @Test
    void aliyunProviderValidation() {
        AliyunProvider p = new AliyunProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("sk-test", "qwen-max")));

        ProviderInfo info = p.info();
        assertEquals(ProviderName.ALIYUN, info.name());
        assertTrue(info.modelTypes().contains(ModelType.KNOWLEDGE_QA));
        assertTrue(info.modelTypes().contains(ModelType.EMBEDDING));
        assertTrue(info.modelTypes().contains(ModelType.RERANK));
    }

    // ---------------- MiniMax ----------------

    @Test
    void miniMaxProviderValidation() {
        MiniMaxProvider p = new MiniMaxProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("test-key", "MiniMax-M2.7")));

        BizException missingKey = assertThrows(BizException.class,
                () -> p.validateConfig(config("", "MiniMax-M2.7")));
        assertTrue(messageOf(missingKey).contains("API key"), messageOf(missingKey));

        BizException missingModel = assertThrows(BizException.class,
                () -> p.validateConfig(config("test-key", "")));
        assertTrue(messageOf(missingModel).contains("model name"), messageOf(missingModel));

        ProviderInfo info = p.info();
        assertEquals(ProviderName.MINIMAX, info.name());
        assertEquals("MiniMax", info.displayName());
        assertTrue(info.modelTypes().contains(ModelType.KNOWLEDGE_QA));
        assertTrue(info.requiresAuth());
        assertTrue(info.description().contains("M2.7"));
    }

    // ---------------- Zhipu ----------------

    @Test
    void zhipuProviderValidation() {
        ZhipuProvider p = new ZhipuProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("test-key", "glm-4")));

        ProviderInfo info = p.info();
        assertEquals(ProviderName.ZHIPU, info.name());
        assertEquals(ProviderBaseURLs.ZHIPU_CHAT_BASE_URL, info.getDefaultURL(ModelType.KNOWLEDGE_QA));
        assertEquals(ProviderBaseURLs.ZHIPU_EMBEDDING_BASE_URL, info.getDefaultURL(ModelType.EMBEDDING));
    }

    // ---------------- Requesty ----------------

    @Test
    void requestyProviderValidation() {
        RequestyProvider p = new RequestyProvider();

        assertDoesNotThrow(() -> p.validateConfig(config("test-key", "openai/gpt-4o-mini")));

        BizException missingKey = assertThrows(BizException.class,
                () -> p.validateConfig(config("", "openai/gpt-4o-mini")));
        assertTrue(messageOf(missingKey).contains("API key"), messageOf(missingKey));

        ProviderInfo info = p.info();
        assertEquals(ProviderName.REQUESTY, info.name());
        assertEquals("Requesty", info.displayName());
        assertEquals(ProviderBaseURLs.REQUESTY_BASE_URL, info.getDefaultURL(ModelType.KNOWLEDGE_QA));
        assertEquals(ProviderBaseURLs.REQUESTY_BASE_URL, info.getDefaultURL(ModelType.EMBEDDING));
        assertTrue(info.modelTypes().contains(ModelType.KNOWLEDGE_QA));
        assertTrue(info.requiresAuth());
    }

    // ---------------- 其余厂商的校验顺序（Go 原文 baseURL → key → model） ----------------

    /** 对照 Go LongCatProvider/MoonshotProvider/QiniuProvider 等：baseURL 缺失先报 base URL */
    @Test
    void providersWithBaseUrlCheckReportItFirst() {
        for (Provider p : new Provider[]{new LongCatProvider(), new MoonshotProvider(),
                new QiniuProvider(), new GPUStackProvider(), new ModelScopeProvider(),
                new QianfanProvider()}) {
            BizException e = assertThrows(BizException.class,
                    () -> p.validateConfig(config("k", "m")), p.info().name().value());
            assertTrue(messageOf(e).contains("base URL"),
                    p.info().name() + " 应先报 base URL，实际: " + messageOf(e));
        }
    }

    /** 对照 Go WeKnoraCloudProvider.ValidateConfig：无条件通过（结构校验） */
    @Test
    void weKnoraCloudAcceptsAnything() {
        assertDoesNotThrow(() -> new WeKnoraCloudProvider().validateConfig(config("", "")));
    }

    /** 对照 Go AzureOpenAIProvider：五项检验顺序 baseURL 最后（key → model → baseURL） */
    @Test
    void azureOpenAiValidation() {
        AzureOpenAIProvider p = new AzureOpenAIProvider();
        BizException e = assertThrows(BizException.class,
                () -> p.validateConfig(new Config(null, "", "k", "gpt-4o", "", null)));
        assertTrue(messageOf(e).contains("base URL"), messageOf(e));

        assertDoesNotThrow(() -> p.validateConfig(
                new Config(null, "https://res.openai.azure.com", "k", "gpt-4o", "", null)));

        ProviderInfo info = p.info();
        assertEquals(1, info.extraFields().size());
        ExtraFieldConfig apiVersion = info.extraFields().get(0);
        assertEquals("api_version", apiVersion.key());
        assertEquals("API Version", apiVersion.label());
        assertEquals("2024-10-21", apiVersion.defaultValue());
        assertEquals("https://{resource}.openai.azure.com", info.getDefaultURL(ModelType.KNOWLEDGE_QA));
    }
}
