package com.ragagent.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** credentials 子资源 PUT 请求：全指针——均为 null 时等价于"查询已配置状态" */
public record ModelCredentialsPutRequest(
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("app_secret") String appSecret) {
}
