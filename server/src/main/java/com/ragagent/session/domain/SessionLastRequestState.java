package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 上次发问时的输入栏状态（对照 Go {@code types.SessionLastRequestState}，
 * internal/types/session.go L279-291）。
 *
 * <p>落在 {@code sessions.agent_config} 这个**遗留 jsonb 列**上——Go 的字段注释写明这是
 * 为了"避免新增迁移"。纯 UI 记忆：**没有任何一个字段驱动后端行为**，只是
 * {@code GetSession} 回给前端、让聊天输入框恢复上次选的 agent / 模型 / KB 范围等。</p>
 *
 * <p>字段序 = 声明序、键名 = Java 字段名（§14.9l S1 换锚）；<b>所有字段恒输出</b>
 * （§1.6：空集合写 {@code []}、未选模型写 {@code ""}，不再有 omitempty 式的条件键）。</p>
 *
 * <p><b>读路径必须宽容</b>：Go 的 {@code Scan} 明确忽略 unmarshal 错误
 * （"行产生于本结构体之前"），所以这里加了 {@code ignoreUnknown=true}——历史行里可能有
 * 现在已删掉的键，不能让整行读不出来。</p>
 *
 * <p>⚠️ <b>存量行必须跑迁移</b>：换锚前写进 {@code agent_config} 的键是下划线形式
 * （{@code agent_id}/{@code knowledge_base_ids}/…），宽松读会把它们**静默吞成空值**——
 * 迁移 SQL 见 HANDOFF §14.9l S1。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionLastRequestState {

    private String agentId;

    /** 无 omitempty：恒输出（false 也要出现）。 */
    private boolean agentEnabled;

    private String modelId;

    private List<String> knowledgeBaseIds;

    private List<String> knowledgeIds;

    private List<String> tagIds;

    private List<String> mcpServiceIds;

    private List<String> skillNames;

    private List<MentionedItem> mentionedItems;

    private boolean webSearchEnabled;

    public SessionLastRequestState() {
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String v) {
        this.agentId = v;
    }

    public boolean isAgentEnabled() {
        return agentEnabled;
    }

    public void setAgentEnabled(boolean v) {
        this.agentEnabled = v;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String v) {
        this.modelId = v;
    }

    public List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<String> v) {
        this.knowledgeBaseIds = v;
    }

    public List<String> getKnowledgeIds() {
        return knowledgeIds;
    }

    public void setKnowledgeIds(List<String> v) {
        this.knowledgeIds = v;
    }

    public List<String> getTagIds() {
        return tagIds;
    }

    public void setTagIds(List<String> v) {
        this.tagIds = v;
    }

    public List<String> getMcpServiceIds() {
        return mcpServiceIds;
    }

    public void setMcpServiceIds(List<String> v) {
        this.mcpServiceIds = v;
    }

    public List<String> getSkillNames() {
        return skillNames;
    }

    public void setSkillNames(List<String> v) {
        this.skillNames = v;
    }

    public List<MentionedItem> getMentionedItems() {
        return mentionedItems;
    }

    public void setMentionedItems(List<MentionedItem> v) {
        this.mentionedItems = v;
    }

    public boolean isWebSearchEnabled() {
        return webSearchEnabled;
    }

    public void setWebSearchEnabled(boolean v) {
        this.webSearchEnabled = v;
    }
}
