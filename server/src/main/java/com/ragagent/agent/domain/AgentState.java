package com.ragagent.agent.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.common.retrieval.SearchResult;

/**
 * agent 一次执行的运行时状态。
 *
 * <h2>JSON 契约（返回值会被序列化落库/进事件）</h2>
 * <ul>
 *   <li>{@code pending_steer_messages} <b>绝不输出</b>
 *       （{@code @JsonIgnore}；steer 行 id 只走引擎内部）；</li>
 *   <li>{@code current_round} / {@code is_complete} / {@code final_answer} /
 *       {@code round_steps} / {@code knowledge_refs} / {@code turn_usage}
 *       <b>恒输出</b>，零值也输出（{@code 0}/{@code false}/{@code ""}/
 *       {@code null}/{@code null}/{@code 零值 usage}）；</li>
 *   <li>{@code knowledge_refs}：未初始化时输出 {@code null}；引擎初始化为空列表后
 *       输出 {@code []}（实录钉住）。</li>
 *   <li>{@code turn_usage} 的 cache_status 走 TokenUsage 自己的输出规则。</li>
 * </ul>
 *
 * <p>与本包 {@link AgentStep} 的关系：{@link #roundSteps} 的元素就是 AgentStep
 * （jsonb 列 {@code messages.agent_steps} 的元素同型）。</p>
 */
public class AgentState {

    /** 已消费、尚未落进某个 AgentStep 的 steer 行 id（不进 JSON 输出）。 */
    @JsonIgnore
    private List<String> pendingSteerMessages;

    /** 当前轮次序号。 */
    private int currentRound;

    /** 本轮已产生的全部步骤。null → 输出 {@code null}；引擎初始化空列表后 → {@code []}。 */
    private List<AgentStep> roundSteps;

    /** 引擎是否已收束（自然停 / 重试耗尽 / 达到轮次上限 / 卡死检测）。 */
    private boolean complete;

    /** 最终答案。 */
    private String finalAnswer = "";

    /** 收集的知识引用（本引擎不填充；handler 侧在 complete 事件里回填）。 */
    private List<SearchResult> knowledgeRefs;

    /** 本轮累计的 LLM 用量（恒输出，零值全 0）。 */
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
