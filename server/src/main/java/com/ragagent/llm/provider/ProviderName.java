package com.ragagent.llm.provider;

/**
 * 对照 Go provider.ProviderName（internal/models/provider/provider.go + weknoracloud.go）。
 *
 * Go 侧是 string 类型别名（值即 "openai"/"anthropic"/... ），既做注册表 key 也做
 * 路由判断的返回值。Java 用枚举承载同一组字面量，{@link #value()} 与 Go 字符串逐字相同
 * （含下划线的 azure_openai）。
 *
 * ⚠️ 与 Go 的差异（枚举的固有限制，调用方需知悉）：Go 允许任意字符串（例如 DB 里写入的
 * 未知厂商名）作为 ProviderName 继续流转，下游 switch 落到 default 分支；Java 侧
 * {@link #fromValue(String)} 对未知值返回 null，调用方必须把 null 当作 Go 的 default 分支处理。
 */
public enum ProviderName {

    // ---- 声明序 = Go provider.go 的常量声明序（OpenAI 起，Azure OpenAI 止），weknoracloud 排在最后 ----
    /** 对照 ProviderOpenAI */
    OPENAI("openai"),
    /** 对照 ProviderAnthropic */
    ANTHROPIC("anthropic"),
    /** 对照 ProviderAliyun */
    ALIYUN("aliyun"),
    /** 对照 ProviderZhipu */
    ZHIPU("zhipu"),
    /** 对照 ProviderOpenRouter */
    OPENROUTER("openrouter"),
    /** 对照 ProviderLiteLLM */
    LITELLM("litellm"),
    /** 对照 ProviderRequesty */
    REQUESTY("requesty"),
    /** 对照 ProviderSiliconFlow */
    SILICONFLOW("siliconflow"),
    /** 对照 ProviderJina */
    JINA("jina"),
    /** 对照 ProviderGeneric */
    GENERIC("generic"),
    /** 对照 ProviderDeepSeek */
    DEEPSEEK("deepseek"),
    /** 对照 ProviderGemini */
    GEMINI("gemini"),
    /** 对照 ProviderVolcengine */
    VOLCENGINE("volcengine"),
    /** 对照 ProviderHunyuan */
    HUNYUAN("hunyuan"),
    /** 对照 ProviderMiniMax */
    MINIMAX("minimax"),
    /** 对照 ProviderMimo */
    MIMO("mimo"),
    /** 对照 ProviderGPUStack */
    GPUSTACK("gpustack"),
    /** 对照 ProviderMoonshot */
    MOONSHOT("moonshot"),
    /** 对照 ProviderModelScope */
    MODELSCOPE("modelscope"),
    /** 对照 ProviderQianfan */
    QIANFAN("qianfan"),
    /** 对照 ProviderQiniu */
    QINIU("qiniu"),
    /** 对照 ProviderLongCat */
    LONGCAT("longcat"),
    /** 对照 ProviderLKEAP */
    LKEAP("lkeap"),
    /** 对照 ProviderNvidia */
    NVIDIA("nvidia"),
    /** 对照 ProviderNovita */
    NOVITA("novita"),
    /** 对照 ProviderAzureOpenAI */
    AZURE_OPEN_AI("azure_openai"),
    /** 对照 weknoracloud.go 的 ProviderWeKnoraCloud */
    WEKNORA_CLOUD("weknoracloud");

    private final String value;

    ProviderName(String value) {
        this.value = value;
    }

    /** Go 侧字符串字面量（注册表 key / DetectProvider 返回值 / DB parameters.provider） */
    public String value() {
        return value;
    }

    /** 字符串 → 枚举；未知或空返回 null（对照 Go 的零值 ""；调用方按 default 分支处理 null） */
    public static ProviderName fromValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (ProviderName p : values()) {
            if (p.value.equals(value)) {
                return p;
            }
        }
        return null;
    }
}
