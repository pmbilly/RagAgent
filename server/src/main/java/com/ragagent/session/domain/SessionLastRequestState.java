package com.ragagent.session.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 上次发问时的输入栏状态（对照 Go {@code types.SessionLastRequestState}，
 * internal/types/session.go L279-291）。
 *
 * <p>落在 {@code sessions.agent_config} 这个**遗留 jsonb 列**上——Go 的字段注释写明这是
 * 为了"避免新增迁移"。纯 UI 记忆：**没有任何一个字段驱动后端行为**，只是
 * {@code GetSession} 回给前端、让聊天输入框恢复上次选的 agent / 模型 / KB 范围等。</p>
 *
 * <p>字段序 = Go struct 声明序，omitempty 的字段用 NON_EMPTY。</p>
 *
 * <p><b>读路径必须宽容</b>：Go 的 {@code Scan} 明确忽略 unmarshal 错误
 * （"行产生于本结构体之前"），所以这里加了 {@code ignoreUnknown=true}——历史行里可能有
 * 现在已删掉的键，不能让整行读不出来。</p>
 */
@JsonPropertyOrder({
        "agent_id", "agent_enabled", "model_id", "knowledge_base_ids", "knowledge_ids",
        "tag_ids", "mcp_service_ids", "skill_names", "mentioned_items",
        "web_search_enabled"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionLastRequestState {

    @JsonProperty("agent_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String agentId;

    /** 无 omitempty：恒输出（false 也要出现）。 */
    @JsonProperty("agent_enabled")
    private boolean agentEnabled;

    @JsonProperty("model_id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String modelId;

    @JsonProperty("knowledge_base_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> knowledgeBaseIds;

    @JsonProperty("knowledge_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> knowledgeIds;

    @JsonProperty("tag_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> tagIds;

    @JsonProperty("mcp_service_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> mcpServiceIds;

    @JsonProperty("skill_names")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> skillNames;

    @JsonProperty("mentioned_items")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<MentionedItem> mentionedItems;

    @JsonProperty("web_search_enabled")
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
