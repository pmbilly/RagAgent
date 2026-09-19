package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** StorageProviderConfig（对照 Go types/knowledgebase.go L351），gorm 列 storage_provider_config */
@JsonPropertyOrder({"provider"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbStorageProviderConfig {

    @JsonProperty("provider")
    private String provider = "";

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
}
