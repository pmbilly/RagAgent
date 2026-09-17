package com.ragagent.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * credentials 子资源响应：{"fields":{"api_key":{"configured":...},...}}。
 * Go map 序列化按 key 字母序：api_key < app_secret——LinkedHashMap 按序插入
 * （Map.of 的迭代顺序未定义，会导致字节漂移）。
 */
public record CredentialsResponse(
        @JsonProperty("fields") Map<String, CredentialFieldMetadata> fields) {

    public static CredentialsResponse of(boolean apiKeyConfigured, boolean appSecretConfigured) {
        Map<String, CredentialFieldMetadata> fields = new LinkedHashMap<>();
        fields.put("api_key", new CredentialFieldMetadata(apiKeyConfigured));
        fields.put("app_secret", new CredentialFieldMetadata(appSecretConfigured));
        return new CredentialsResponse(fields);
    }
}
