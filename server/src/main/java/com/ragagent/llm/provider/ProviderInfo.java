package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

/**
 * 服务商元数据：名称、展示名、描述、各模型类型默认 URL、支持的模型类型、
 * 是否必须鉴权、额外字段配置。
 *
 * 字段序 = 声明序。HTTP 目录响应由
 * com.ragagent.model.service.ProviderRegistry + ModelProviderDTO 承载，
 * 本 record 供运行时路由/校验使用。
 */
public record ProviderInfo(
        ProviderName name,
        String displayName,
        String description,
        Map<ModelType, String> defaultUrls,
        List<ModelType> modelTypes,
        boolean requiresAuth,
        List<ExtraFieldConfig> extraFields) {

    public ProviderInfo {
        // 缺省归一：null map / null 列表 → 空
        displayName = displayName == null ? "" : displayName;
        description = description == null ? "" : description;
        defaultUrls = defaultUrls == null ? Map.of() : Map.copyOf(defaultUrls);
        modelTypes = modelTypes == null ? List.of() : List.copyOf(modelTypes);
        extraFields = extraFields == null ? List.of() : List.copyOf(extraFields);
    }

    /** 便捷构造：无额外字段 */
    public static ProviderInfo of(ProviderName name, String displayName, String description,
                                  Map<ModelType, String> defaultUrls, List<ModelType> modelTypes,
                                  boolean requiresAuth) {
        return new ProviderInfo(name, displayName, description, defaultUrls, modelTypes,
                requiresAuth, List.of());
    }

    /**
     * 取指定模型类型的默认 URL，缺失时回退到 Chat(KnowledgeQA)，再缺失返回 ""。
     */
    public String getDefaultURL(ModelType modelType) {
        String url = defaultUrls.get(modelType);
        if (url != null) {
            return url;
        }
        // 回退到 Chat URL
        String chat = defaultUrls.get(ModelType.KNOWLEDGE_QA);
        return chat != null ? chat : "";
    }

    /** 是否支持指定模型类型 */
    public boolean supportsModelType(ModelType modelType) {
        return modelTypes.contains(modelType);
    }
}
