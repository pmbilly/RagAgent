package com.ragagent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 模型名判定函数测试，逐字对照 Go：
 * <ul>
 *   <li>provider_test.go 的 TestAliyunModelDetection（IsQwen3Model / IsDeepSeekModel）</li>
 *   <li>openai_test.go 的 TestIsOpenAIReasoningOrGPT5Model（全表照抄，含边界用例）</li>
 *   <li>moonshot.go / lkeap.go 的判定函数（Go 无测试，用例由函数文档注释推导）</li>
 * </ul>
 */
class ModelPredicateTest {

    // ---------------- aliyun.go ----------------

    /** 对照 Go TestAliyunModelDetection/DeepSeek model detection + Qwen3 model detection */
    @ParameterizedTest(name = "isQwen3Model({0}) = {1}")
    @CsvSource({
            "qwen3-32b, true",
            "qwen3-72b, true",
            "QWEN3-32B, true",
            "qwen-max, false",
            "qwen2.5-72b, false",
            "'', false"
    })
    void isQwen3Model(String modelName, boolean expected) {
        assertEquals(expected, AliyunProvider.isQwen3Model(modelName));
    }

    /** 对照 Go TestAliyunModelDetection/DeepSeek model detection */
    @ParameterizedTest(name = "isDeepSeekModel({0}) = {1}")
    @CsvSource({
            "deepseek-chat, true",
            "deepseek-v3.1, true",
            "DeepSeek-Chat, true",
            "qwen-max, false",
            "'', false"
    })
    void isDeepSeekModel(String modelName, boolean expected) {
        assertEquals(expected, AliyunProvider.isDeepSeekModel(modelName));
    }

    /** 对照 Go IsQwenThinkingModel：前缀匹配（qwen3 / qwen-plus / qwen-max / qwen-turbo） */
    @ParameterizedTest(name = "isQwenThinkingModel({0}) = {1}")
    @CsvSource({
            "qwen3-32b, true",
            "Qwen-Plus, true",
            "qwen-plus-latest, true",
            "qwen-max, true",
            "qwen-turbo, true",
            "qwen2.5-72b, false",
            "qwen-long, false",
            "gpt-4, false",
            "'', false"
    })
    void isQwenThinkingModel(String modelName, boolean expected) {
        assertEquals(expected, AliyunProvider.isQwenThinkingModel(modelName));
    }

    // ---------------- openai.go ----------------

    /** 对照 Go TestIsOpenAIReasoningOrGPT5Model：用例逐条照抄（issue #1283） */
    @ParameterizedTest(name = "isOpenAIReasoningOrGPT5Model({0}) = {1}")
    @CsvSource({
            "'', false",

            "gpt-5, true",
            "gpt-5-mini, true",
            "gpt-5.2, true",
            "gpt-5.5-pro, true",
            "GPT-5.4-Mini, true",

            "o1, true",
            "o1-mini, true",
            "o1-preview, true",
            "o3, true",
            "o3-mini, true",
            "o4-mini, true",

            "gpt-4, false",
            "gpt-4o, false",
            "gpt-4o-mini, false",
            "gpt-3.5-turbo, false",

            "olympus-1, false",
            "openai-gpt-4, false",
            "o3xtra, false",
            "qwen-max, false",

            // Go 用 TrimSpace 后再判前缀，故首尾空白不影响
            "'  gpt-5-mini  ', true",
            "'  gpt-4o  ', false"
    })
    void isOpenAIReasoningOrGPT5Model(String modelName, boolean expected) {
        assertEquals(expected, OpenAIProvider.isOpenAIReasoningOrGPT5Model(modelName));
    }

    /** 空模型名与 null 均判否（Go 的 name == "" 分支） */
    @Test
    void openAiReasoningNullIsFalse() {
        assertFalse(OpenAIProvider.isOpenAIReasoningOrGPT5Model(null));
        assertFalse(OpenAIProvider.isOpenAIReasoningOrGPT5Model("   "));
    }

    // ---------------- moonshot.go ----------------

    /**
     * 对照 Go IsMoonshotFixedTempModel（moonshot-v1 前缀 + kimi-k2.5/k2.6 精确相等）。
     * 注意：先 TrimSpace 再 ToLower；kimi 分支是精确匹配，kimi-k2.5-turbo 不命中。
     */
    @ParameterizedTest(name = "isMoonshotFixedTempModel({0}) = {1}")
    @CsvSource({
            "moonshot-v1-8k, true",
            "moonshot-v1-32k, true",
            "moonshot-v1-128k, true",
            "Moonshot-V1-8k, true",
            "'  moonshot-v1-8k  ', true",
            "kimi-k2.5, true",
            "Kimi-K2.6, true",
            "kimi-k2.5-turbo, false",
            "kimi-k2, false",
            "kimi-k2-turbo, false",
            "kimi-k2-thinking, false",
            "moonshot, false",
            "'', false"
    })
    void isMoonshotFixedTempModel(String modelName, boolean expected) {
        assertEquals(expected, MoonshotProvider.isMoonshotFixedTempModel(modelName));
    }

    // ---------------- lkeap.go ----------------

    /** 对照 Go IsLKEAPDeepSeekV3Model / IsLKEAPDeepSeekR1Model / IsLKEAPThinkingModel */
    @ParameterizedTest(name = "isLKEAPThinkingModel({0}) = {1}")
    @CsvSource({
            "deepseek-r1, true",
            "DeepSeek-R1-Distill, true",
            "deepseek-v3, true",
            "deepseek-v3-0324, true",
            "DeepSeek-V3.1, true",
            "glm-4, false",
            "deepseek-chat, false",
            "qwen-max, false",
            "'', false"
    })
    void isLKEAPThinkingModel(String modelName, boolean expected) {
        assertEquals(expected, LKEAPProvider.isLKEAPThinkingModel(modelName));
    }

    /** R1 / V3 两个子判定各自独立（与 Go 的两个 Contains 一致） */
    @Test
    void lkeapSubPredicates() {
        assertTrue(LKEAPProvider.isLKEAPDeepSeekR1Model("deepseek-r1"));
        assertFalse(LKEAPProvider.isLKEAPDeepSeekR1Model("deepseek-v3"));
        assertTrue(LKEAPProvider.isLKEAPDeepSeekV3Model("deepseek-v3"));
        assertFalse(LKEAPProvider.isLKEAPDeepSeekV3Model("deepseek-r1"));
    }
}
