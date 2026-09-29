package com.ragagent.knowledge.domain;


public class KnowledgeBaseStorageProviderConfig {

    private String provider = "";

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
}
