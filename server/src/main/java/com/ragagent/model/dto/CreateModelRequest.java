package com.ragagent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建模型请求：{@code name}/{@code type}/{@code source}/{@code parameters} 必填。
 *
 * <p>message 显式给出「字段名: 原因」：不依赖 Validator 的内置消息资源，
 * 避免文案随 JVM locale / Accept-Language 漂移（匿名客户端与浏览器看到同一文案）。</p>
 */
public record CreateModelRequest(
        @NotBlank(message = "name: 不能为空") String name,
        String displayName,
        @NotBlank(message = "type: 不能为空") String type,
        @NotBlank(message = "source: 不能为空") String source,
        String description,
        @NotNull(message = "parameters: 不能为空") ModelParametersRequest parameters) {
}
