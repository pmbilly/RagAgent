package com.ragagent.llm.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ragagent.model.dto.ModelProviderDTO;

/**
 * 目录一致性哨兵：把本包（运行时元数据）与
 * com.ragagent.model.service.ProviderRegistry（HTTP 目录，数据由 golden 钉住）
 * 逐字段比对。
 *
 * <p>两层同源，任何一侧漏改（新增厂商、改 DisplayName/Description/
 * 默认 URL/模型类型）都会在这里立刻暴露。</p>
 *
 * <p>两侧的差异是**表示层**而非数据：目录层用前端字符串（chat/embedding/rerank/vllm/asr），
 * 运行时层用 {@code ModelType} 枚举字面量（KnowledgeQA/...），通过
 * {@code com.ragagent.model.service.ProviderRegistry.toFrontend} 对齐。</p>
 */
class ProviderCatalogParityTest {

    @Test
    void runtimeRegistryMatchesHttpCatalog() {
        var catalog = new com.ragagent.model.service.ProviderRegistry().list();
        var runtime = ProviderRegistry.list();

        assertEquals(catalog.size(), runtime.size(), "厂商数量必须一致");
        for (int i = 0; i < runtime.size(); i++) {
            ProviderInfo info = runtime.get(i);
            ModelProviderDTO dto = catalog.get(i);

            assertEquals(dto.value(), info.name().value(), "第 " + i + " 个厂商的标识不一致");
            assertEquals(dto.label(), info.displayName(), info.name() + " 的 DisplayName 不一致");
            assertEquals(dto.description(), info.description(), info.name() + " 的 Description 不一致");
            // 目录层的 modelTypes 是前端字符串（chat/embedding/...），运行时层是 ModelType 枚举字面量
            assertEquals(dto.modelTypes(),
                    info.modelTypes().stream()
                            .map(t -> com.ragagent.model.service.ProviderRegistry.toFrontend(t.value()))
                            .toList(),
                    info.name() + " 的 ModelTypes 不一致");

            Map<String, String> expectedUrls = new LinkedHashMap<>();
            info.defaultUrls().forEach((type, url) ->
                    expectedUrls.put(com.ragagent.model.service.ProviderRegistry.toFrontend(type.value()), url));
            assertEquals(expectedUrls, dto.defaultUrls(), info.name() + " 的 DefaultURLs 不一致");
        }
    }
}
