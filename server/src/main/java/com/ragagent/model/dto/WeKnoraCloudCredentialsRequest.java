package com.ragagent.model.dto;

import jakarta.validation.constraints.NotBlank;

/** WeKnoraCloud 凭证保存请求（两字段必填；message 显式给出，不随 locale 漂移）。 */
public record WeKnoraCloudCredentialsRequest(
        @NotBlank(message = "appId: 不能为空") String appId,
        @NotBlank(message = "appSecret: 不能为空") String appSecret) {
}
