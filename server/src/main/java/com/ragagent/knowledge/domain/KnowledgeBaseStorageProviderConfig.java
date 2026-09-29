package com.ragagent.knowledge.domain;


/** 存储提供方配置的 jsonb 形状（列 {@code storage_provider_config}），仅含 {@code provider}。 */
public class KnowledgeBaseStorageProviderConfig {

    private String provider = "";

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v == null ? "" : v; }
}
