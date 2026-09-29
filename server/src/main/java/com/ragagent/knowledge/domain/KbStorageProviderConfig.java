package com.ragagent.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"provider"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbStorageProviderConfig {

    @JsonProperty("provider")
    private String provider = "";

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
}
