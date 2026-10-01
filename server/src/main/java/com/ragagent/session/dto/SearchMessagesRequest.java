package com.ragagent.session.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/v1/messages/search} 的请求体。
 *
 * <p>{@code query} 必填（空串与缺失都拒绝，400 {@code query: 不能为空}）；
 * {@code mode} 缺省走服务层的默认检索模式；{@code limit} 缺省 0（服务层再归一化）；
 * {@code sessionIds} 非空即"只在这些会话里搜"。</p>
 */
public record SearchMessagesRequest(
        @NotBlank(message = "query: 不能为空") String query,
        String mode,
        Integer limit,
        List<String> sessionIds) {
}
