package com.ragagent.model.dto;

import java.util.List;
import java.util.Map;

/** 模型提供方列表项：{@code defaultUrls} 为 map（字母序输出），{@code modelTypes} 顺序 = 注册表声明序。 */
public record ModelProviderDTO(
        String value,
        String label,
        String description,
        Map<String, String> defaultUrls,
        List<String> modelTypes) {
}
