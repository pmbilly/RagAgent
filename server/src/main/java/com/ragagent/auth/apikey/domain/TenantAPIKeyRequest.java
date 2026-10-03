package com.ragagent.auth.apikey.domain;

import java.util.List;

/**
 * 创建 / 更新租户 API Key 的请求体。
 *
 * <p>创建与更新共用同一份字段语义（更新同样走创建的校验规则）：
 * 用同一个 record 表达，避免两份会漂移的定义。</p>
 *
 * <p>{@code expiresAtUnix} 是 **Unix 秒**的可空语义：
 * {@code null} = 不设置到期时间；{@code 0} 是一个合法的（但已过期）时间戳。
 * 所以这里必须用包装类型 {@code Long}，不能用原始 {@code long}。</p>
 */
public record TenantAPIKeyRequest(
        String name,
        boolean fullAccess,
        List<String> knowledgeBaseIds,
        List<String> capabilities,
        Long expiresAtUnix) {
}
