package com.ragagent.session.service;

import java.util.ArrayList;
import java.util.List;

import com.ragagent.agent.AgentConfig;
import com.ragagent.agent.tools.SearchTarget.SearchTargets;

/**
 * 装配期 AgentConfig 扩展（波 4.6d 新增文件）。
 *
 * <p>4.6b 的 {@link AgentConfig} 只收引擎消费字段（4.6b 决策点⑥：其余 Go 字段
 * 「随 4.6d agent_service 装配按需补」）。根包既有文件不可改，故以子类携带
 * agent_service.go 装配路径的消费字段——引擎经父类 getter 读取，不受影响；
 * 本类只新增字段与存取器，零行为覆盖。</p>
 */
public class QaAgentConfig extends AgentConfig {

    /** 本轮沙箱镜像里可调用的已安装技能（对照 Go AgentConfig.TenantSkills，实体列表）。 */
    private List<com.ragagent.sandbox.domain.TenantSkillEntity> tenantSkills = new ArrayList<>();

    private int webSearchMaxResults;
    private String webSearchProviderId = "";
    private int historyTurns;
    private boolean memoryEnabled;
    private String mcpSelectionMode = "";
    private List<String> mcpServices = new ArrayList<>();
    private int mcpAuthWaitTimeout;
    private boolean retrieveKbOnlyWhenMentioned;
    private boolean sharedAgentReadOnly;
    private List<String> knowledgeBases = new ArrayList<>();
    private List<String> knowledgeIds = new ArrayList<>();
    private SearchTargets searchTargets;
    private boolean useCustomSystemPrompt;
    private String systemPrompt = "";
    private boolean skillsEnabled;
    private List<String> allowedSkills;
    private List<String> pinnedSkillNames;
    private List<String> pinnedMcpServiceIds;
    private List<String> skillDirs = new ArrayList<>();

    public int getWebSearchMaxResults() { return webSearchMaxResults; }
    public void setWebSearchMaxResults(int v) { webSearchMaxResults = v; }
    public String getWebSearchProviderId() { return webSearchProviderId; }
    public void setWebSearchProviderId(String v) { webSearchProviderId = v == null ? "" : v; }
    public int getHistoryTurns() { return historyTurns; }
    public void setHistoryTurns(int v) { historyTurns = v; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(boolean v) { memoryEnabled = v; }
    public String getMcpSelectionMode() { return mcpSelectionMode; }
    public void setMcpSelectionMode(String v) { mcpSelectionMode = v == null ? "" : v; }
    public List<String> getMcpServices() { return mcpServices; }
    public void setMcpServices(List<String> v) { mcpServices = v == null ? new ArrayList<>() : v; }
    public int getMcpAuthWaitTimeout() { return mcpAuthWaitTimeout; }
    public void setMcpAuthWaitTimeout(int v) { mcpAuthWaitTimeout = v; }
    public boolean isRetrieveKbOnlyWhenMentioned() { return retrieveKbOnlyWhenMentioned; }
    public void setRetrieveKbOnlyWhenMentioned(boolean v) { retrieveKbOnlyWhenMentioned = v; }
    public boolean isSharedAgentReadOnly() { return sharedAgentReadOnly; }
    public void setSharedAgentReadOnly(boolean v) { sharedAgentReadOnly = v; }
    public List<String> getKnowledgeBases() { return knowledgeBases; }
    public void setKnowledgeBases(List<String> v) { knowledgeBases = v == null ? new ArrayList<>() : v; }
    public List<String> getKnowledgeIds() { return knowledgeIds; }
    public void setKnowledgeIds(List<String> v) { knowledgeIds = v == null ? new ArrayList<>() : v; }
    public SearchTargets getSearchTargets() { return searchTargets; }
    public void setSearchTargets(SearchTargets v) { searchTargets = v; }
    public boolean useCustomSystemPrompt() { return useCustomSystemPrompt; }
    public void setUseCustomSystemPrompt(boolean v) { useCustomSystemPrompt = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { systemPrompt = v == null ? "" : v; }
    public boolean isSkillsEnabled() { return skillsEnabled; }
    public void setSkillsEnabled(boolean v) { skillsEnabled = v; }
    public List<String> getAllowedSkills() { return allowedSkills; }
    public void setAllowedSkills(List<String> v) { allowedSkills = v; }
    public List<String> getPinnedSkillNames() { return pinnedSkillNames; }
    public void setPinnedSkillNames(List<String> v) { pinnedSkillNames = v; }
    public List<String> getPinnedMcpServiceIds() { return pinnedMcpServiceIds; }
    public void setPinnedMcpServiceIds(List<String> v) { pinnedMcpServiceIds = v; }
    public List<com.ragagent.sandbox.domain.TenantSkillEntity> getTenantSkills() { return tenantSkills; }
    public void setTenantSkills(List<com.ragagent.sandbox.domain.TenantSkillEntity> v) { tenantSkills = v == null ? new ArrayList<>() : v; }
    public List<String> getSkillDirs() { return skillDirs; }
    public void setSkillDirs(List<String> v) { skillDirs = v == null ? new ArrayList<>() : v; }
}
