package com.ragagent.session.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 单条消息级的**非机密**请求状态快照（对照 Go {@code types.MessageExecutionContext}，
 * internal/types/message.go L382-401）。
 *
 * <p>落 {@code messages.execution_context} jsonb 列。用途是让追问建议之类的派生体验，
 * 在主流程（SSE）结束之后仍能重建出当时的作用域。</p>
 *
 * <p><b>它不是 HTTP 契约</b>：{@code Message.execution_context} 的 tag 是 {@code json:"-"}，
 * 永远不出现在响应里。因此下面两个跨模块类型（{@code QuestionSuggestionConfig}、
 * {@code TagScope}）先用 {@code Map<String,Object>} 原样透传即可——它们只影响这一列能不能
 * 往返，不影响对外契约。对应模块翻译时再收紧类型。</p>
 */
@JsonPropertyOrder({
        "agent_config_hash", "question_suggestions", "knowledge_base_ids", "knowledge_ids",
        "tag_ids", "tag_scopes", "mcp_service_ids", "skill_names",
        "web_search_enabled", "locale", "suggestion_attribution", "langfuse_traceparent"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageExecutionContext {

    @JsonProperty("agent_config_hash")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String agentConfigHash;

    /** 对照 Go 的 {@code *QuestionSuggestionConfig}——跨模块，先原样透传。 */
    @JsonProperty("question_suggestions")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> questionSuggestions;

    @JsonProperty("knowledge_base_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> knowledgeBaseIds;

    @JsonProperty("knowledge_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> knowledgeIds;

    @JsonProperty("tag_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> tagIds;

    /** 对照 Go 的 {@code []TagScope}——跨模块，先原样透传。 */
    @JsonProperty("tag_scopes")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> tagScopes;

    @JsonProperty("mcp_service_ids")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> mcpServiceIds;

    @JsonProperty("skill_names")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> skillNames;

    /** 无 omitempty：恒输出（false 也要出现）。 */
    @JsonProperty("web_search_enabled")
    private boolean webSearchEnabled;

    @JsonProperty("locale")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String locale;

    /** 追问建议的归因，挂在点击后的下一条用户消息上。 */
    @JsonProperty("suggestion_attribution")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SuggestionAttribution suggestionAttribution;

    /**
     * 原始 chat 请求的 W3C traceparent。追问建议常发生在**后续的** HTTP 调用里
     * （或 SSE handler 已经结束根 span 之后），没有它的话 LLM 包装器会另起一个
     * 孤儿 {@code chat.completion} 追踪，而不是嵌在 agent 轮次之下。
     */
    @JsonProperty("langfuse_traceparent")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String langfuseTraceparent;

    public MessageExecutionContext() {
    }

    public String getAgentConfigHash() {
        return agentConfigHash;
    }

    public void setAgentConfigHash(String v) {
        this.agentConfigHash = v;
    }

    public Map<String, Object> getQuestionSuggestions() {
        return questionSuggestions;
    }

    public void setQuestionSuggestions(Map<String, Object> v) {
        this.questionSuggestions = v;
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

    public List<Map<String, Object>> getTagScopes() {
        return tagScopes;
    }

    public void setTagScopes(List<Map<String, Object>> v) {
        this.tagScopes = v;
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

    public boolean isWebSearchEnabled() {
        return webSearchEnabled;
    }

    public void setWebSearchEnabled(boolean v) {
        this.webSearchEnabled = v;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String v) {
        this.locale = v;
    }

    public SuggestionAttribution getSuggestionAttribution() {
        return suggestionAttribution;
    }

    public void setSuggestionAttribution(SuggestionAttribution v) {
        this.suggestionAttribution = v;
    }

    public String getLangfuseTraceparent() {
        return langfuseTraceparent;
    }

    public void setLangfuseTraceparent(String v) {
        this.langfuseTraceparent = v;
    }
}
