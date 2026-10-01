package com.ragagent.model.dto;

/** credentials 子资源 PUT 请求：两字段均为 null 时等价于"查询已配置状态"。 */
public record ModelCredentialsPutRequest(
        String apiKey,
        String appSecret) {
}
