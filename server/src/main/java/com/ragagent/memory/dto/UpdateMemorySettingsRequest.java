package com.ragagent.memory.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /api/v1/memory/settings} 的请求体。
 *
 * <p>{@code enabled} 是**必填的三态开关**：字段缺失与显式 {@code null} 落同一条 400
 * （{@code enabled: 不能为空}）——"没给"不等于"关掉"，只有真正传了 {@code false}
 * 才是用户关掉自己的记忆。</p>
 */
public record UpdateMemorySettingsRequest(
        @NotNull(message = "enabled: 不能为空") Boolean enabled) {
}
