package com.ragagent.knowledge.domain;


/** ASRConfig：三字段恒输出 */
public class KbAsrConfig {

    private boolean enabled;
    private String modelId = "";
    private String language = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { modelId = v == null ? "" : v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v == null ? "" : v; }
}
