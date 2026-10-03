package com.ragagent.session.dto;

import java.util.List;

import com.ragagent.session.domain.Message;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/v1/sessions/{sessionId}/generate_title} 的请求体。
 *
 * <p>{@code messages} 必填但**允许空数组**（历史行为：validator 对切片是"非 nil 即通过"，
 * {@code {"messages":[]}} 会一路走到模型查找）。</p>
 *
 * <p>⚠️ 这里仍然收整条 {@link Message}：生成标题需要消息的 role/content/tool 细节，
 * 为一个"把消息喂给标题模型"的动作再定义一套镜像 DTO 只会带来漂移风险。</p>
 */
public record GenerateTitleRequest(
        @NotNull(message = "messages: 不能为空") List<Message> messages) {
}
