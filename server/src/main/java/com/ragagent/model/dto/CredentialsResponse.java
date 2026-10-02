package com.ragagent.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * credentials 子资源响应：{@code {"fields":{"apiKey":{"configured":...},"appSecret":{...}}}}。
 * 字段标识符恒为 api_key/app_secret（同时也是 DELETE /credentials/{field} 的取值域）。
 */
public record CredentialsResponse(
        Map<String, CredentialFieldMetadata> fields) {

    public static CredentialsResponse of(boolean apiKeyConfigured, boolean appSecretConfigured) {
        Map<String, CredentialFieldMetadata> fields = new LinkedHashMap<>();
        fields.put("apiKey", new CredentialFieldMetadata(apiKeyConfigured));
        fields.put("appSecret", new CredentialFieldMetadata(appSecretConfigured));
        return new CredentialsResponse(fields);
    }
}
