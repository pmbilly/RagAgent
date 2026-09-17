package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go provider.WeKnoraCloudProvider（weknoracloud.go）。
 * 四个模型类型共用同一硬编码入口 URL（路径由各实现拼接）。
 */
public class WeKnoraCloudProvider implements Provider {

    @Override
    public ProviderInfo info() {
        return ProviderInfo.of(
                ProviderName.WEKNORA_CLOUD,
                "WeKnoraCloud",
                "WeKnora云服务，模型：chat, embedding, rerank, vlm",
                Map.of(
                        ModelType.KNOWLEDGE_QA, ProviderBaseURLs.WEKNORA_CLOUD_BASE_URL,
                        ModelType.EMBEDDING, ProviderBaseURLs.WEKNORA_CLOUD_BASE_URL,
                        ModelType.RERANK, ProviderBaseURLs.WEKNORA_CLOUD_BASE_URL,
                        ModelType.VLLM, ProviderBaseURLs.WEKNORA_CLOUD_BASE_URL),
                List.of(ModelType.KNOWLEDGE_QA, ModelType.EMBEDDING, ModelType.RERANK, ModelType.VLLM),
                true);
    }

    /**
     * 对照 Go (*WeKnoraCloudProvider).ValidateConfig：AppID/AppSecret 通过专用初始化接口
     * 写入，此处仅做结构校验（该字段当前实际承载上游 API Key）——即恒返回 nil，什么都不校验。
     */
    @Override
    public void validateConfig(Config config) {
        // 与 Go 一致：无条件通过
    }
}
