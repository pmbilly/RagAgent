package com.ragagent.model.dto;

/**
 * 更新模型请求。
 *
 * <p>覆盖语义：{@code displayName} 为 null 时不修改；{@code name} 为 null/空串时不修改；
 * {@code type}/{@code source}/{@code description}/{@code parameters} 无条件覆盖。</p>
 */
public record UpdateModelRequest(
        String name,
        String displayName,
        String description,
        ModelParametersRequest parameters,
        String source,
        String type) {
}
