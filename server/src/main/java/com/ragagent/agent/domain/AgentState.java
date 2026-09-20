package com.ragagent.agent.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * agent 一次执行的运行时状态（对照 Go {@code types.AgentState}，
 * internal/types/agent.go:449-457）。
 *
 * <h2>JSON 契约（Execute 返回值会被 json.Marshal 落库/进事件）</h2>
 * <ul>
 *   <li>{@code pending_steer_messages} 是 {@code json:"-"}——<b>绝不输出</b>
 *       （{@code @JsonIgnore}；steer 行 id 只走引擎内部）；</li>
 *   <li>{@code current_round} / {@code is_complete} / {@code final_answer} /
 *       {@code round_steps} / {@code knowledge_refs} / {@code turn_usage}
 *       均<b>无 omitempty</b> → 恒输出（零值 {@code 0}/{@code false}/{@code ""}/
 *       {@code null}/{@code null}/{@code 零值 usage}）；</li>
 *   <li>{@code knowledge_refs} 是 {@code []*SearchResult}：Go 的 nil 切片输出
 *       {@code null}；Execute 初始化为空切片后输出 {@code []}（实录钉住）。</li>
 *   <li>{@code turn_usage} 的 cache_status 走 TokenUsage 自己的 omitempty 规则。</li>
 * </ul>
 *
 * <p>与本包 {@link AgentStep} 的关系：RoundSteps 的元素就是 AgentStep
 * （jsonb 列 {@code messages.agent_steps} 的元素同型）。</p>
 */
@JsonPropertyOrder({"current_round", "round_steps", "is_complete", "final_answer",
        "knowledge_refs", "turn_usage"})
public class AgentState {

    /** 已消费、尚未落进某个 AgentStep 的 steer 行 id（json:"-"，不输出）。 */
    @JsonIgnore
    private List<String> pendingSteerMessages;

    /** 当前轮次序号。 */
    @JsonProperty("current_round")
    private int currentRound;

    /** 本轮已产生的全部步骤。Go nil → {@code null}；Execute 初始化空切片 → {@code []}。 */
    @JsonProperty("round_steps")
    private List<AgentStep> roundSteps;

    /** 引擎是否已收束（自然停 / 重试耗尽 / 达到轮次上限 / 卡死检测）。 */
    @JsonProperty("is_complete")
    private boolean complete;

    /** 最终答案。 */
    @JsonProperty("final_answer")
    private String finalAnswer = "";

    /** 收集的知识引用（本波引擎不填充；handler 侧在 complete 事件里回填）。 */
    @JsonProperty("knowledge_refs")
    private List<SearchResult> knowledgeRefs;

    /** 本轮累计的 LLM 用量（无 omitempty：恒输出，零值全 0）。 */
    @JsonProperty("turn_usage")
    private TokenUsage turnUsage = new TokenUsage();

    public List<String> getPendingSteerMessages() { return pendingSteerMessages; }
    public void setPendingSteerMessages(List<String> v) { pendingSteerMessages = v; }

    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int v) { currentRound = v; }

    public List<AgentStep> getRoundSteps() { return roundSteps; }
    public void setRoundSteps(List<AgentStep> v) { roundSteps = v; }

    public boolean isComplete() { return complete; }
    public void setComplete(boolean v) { complete = v; }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String v) { finalAnswer = v == null ? "" : v; }

    public List<SearchResult> getKnowledgeRefs() { return knowledgeRefs; }
    public void setKnowledgeRefs(List<SearchResult> v) { knowledgeRefs = v; }

    public TokenUsage getTurnUsage() { return turnUsage; }
    public void setTurnUsage(TokenUsage v) { turnUsage = v == null ? new TokenUsage() : v; }
}
