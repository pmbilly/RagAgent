package com.ragagent.llm.provider;

import java.util.List;
import java.util.Map;

/**
 * 对照 Go provider.ProviderInfo（provider.go）。
 *
 * Go 各 provider 文件的 Info() 返回该结构；字段序 = Go 结构体声明序
 * （Name, DisplayName, Description, DefaultURLs, ModelTypes, RequiresAuth, ExtraFields）。
 * Go 侧该结构体没有 json tag，HTTP 目录响应另有 golden 数据承载
 * （com.ragagent.model.service.ProviderRegistry + ModelProviderDTO），此处的 Info 供运行时
 * 路由/校验使用。
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
        // Go 零值归一：nil map / nil slice 都是"空"（零值语义，约定 §9）
        displayName = displayName == null ? "" : displayName;
        description = description == null ? "" : description;
        defaultUrls = defaultUrls == null ? Map.of() : Map.copyOf(defaultUrls);
        modelTypes = modelTypes == null ? List.of() : List.copyOf(modelTypes);
        extraFields = extraFields == null ? List.of() : List.copyOf(extraFields);
    }

    /** 便捷构造：无额外字段（对照 Go 里不写 ExtraFields 的 provider，其零值为 nil） */
    public static ProviderInfo of(ProviderName name, String displayName, String description,
                                  Map<ModelType, String> defaultUrls, List<ModelType> modelTypes,
                                  boolean requiresAuth) {
        return new ProviderInfo(name, displayName, description, defaultUrls, modelTypes,
                requiresAuth, List.of());
    }

    /**
     * 对照 Go (*ProviderInfo).GetDefaultURL：
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

    /** 对照 Go ListByModelType 里的 `for _, t := range info.ModelTypes { if t == modelType }` */
    public boolean supportsModelType(ModelType modelType) {
        return modelTypes.contains(modelType);
    }
}
