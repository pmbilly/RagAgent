package com.ragagent.session.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单条消息级的**非机密**请求状态快照。
 *
 * <p>落 {@code messages.execution_context} jsonb 列。用途是让追问建议之类的派生体验，
 * 在主流程（SSE）结束之后仍能重建出当时的作用域。</p>
 *
 * <p><b>它不是 HTTP 契约</b>：{@code Message.execution_context} 的 tag 是 {@code json:"-"}，
 * 永远不出现在响应里。因此下面两个跨模块类型（{@code QuestionSuggestionConfig}、
 * {@code TagScope}）先用 {@code Map<String,Object>} 原样透传即可——它们只影响这一列能不能
 * 往返，不影响对外契约；类型按需再收紧。</p>
 *
 * <p><b>键名＝Java 字段名</b>：11 个外层键不带 {@code @JsonProperty} / {@code @JsonInclude}
 * 注解。三处**刻意保留**：</p>
 * <ul>
 *   <li>{@code questionSuggestions} 的**内层**键属 agent 域配置
 *       （{@code enabled}/{@code allow_regenerate}/…，见 {@code MessageSuggestionService}），
 *       不在本批范围；</li>
 *   <li>{@code tagScopes} 的内层键同理（跨模块 {@code TagScope}）；</li>
 *   <li>{@code locale} 单词键本来就无映射。</li>
 * </ul>
 *
 * <p><b>读路径必须宽容</b>（{@code ignoreUnknown=true}）：历史行里可能有已删键，
 * 且换锚前写的是下划线键——**旧键会被静默吞成空值**，存量行必须跑迁移。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageExecutionContext {

    private String agentConfigHash;

    /** agent 域的追问建议配置（跨模块），原样透传（内层键保留下划线）。 */
    private Map<String, Object> questionSuggestions;

    private List<String> knowledgeBaseIds;

    private List<String> knowledgeIds;

    private List<String> tagIds;

    /** 跨模块 TagScope，原样透传（内层键保留下划线）。 */
    private List<Map<String, Object>> tagScopes;

    private List<String> mcpServiceIds;

    private List<String> skillNames;

    private boolean webSearchEnabled;

    private String locale;

    /** 追问建议的归因，挂在点击后的下一条用户消息上。 */
    private SuggestionAttribution suggestionAttribution;

    /**
     * 原始 chat 请求的 W3C traceparent。追问建议常发生在**后续的** HTTP 调用里
     * （或 SSE handler 已经结束根 span 之后），没有它的话 LLM 包装器会另起一个
     * 孤儿 {@code chat.completion} 追踪，而不是嵌在 agent 轮次之下。
     */
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
