package com.ragagent.agent.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.ragagent.common.web.GoTimeSerializer;

/**
 * ReAct 循环的一轮。
 *
 * <p>它落 {@code messages.agent_steps} jsonb，也直接出现在消息响应体里，
 * 所以字段序与零值取舍都是线上契约。</p>
 *
 * <h2>零值取舍（逐字段，实测于 {@code AgentStepsJsonTest}）</h2>
 * <ul>
 *   <li>{@code iteration} / {@code thought} <b>恒输出</b>（零值 {@code 0} / {@code ""}）；</li>
 *   <li>{@code user_messages_before} / {@code intermediate_answer} / {@code reasoning_content}
 *       空时省略；</li>
 *   <li>{@code tool_calls} <b>恒输出</b>：null 输出 {@code "tool_calls":null}；</li>
 *   <li>{@code timestamp} 零值输出 {@code "0001-01-01T00:00:00Z"}
 *       （不是 {@code null}），由 {@link GoTimeSerializer} 序列化。</li>
 * </ul>
 *
 * <p>实测的完整形状（零值时）：</p>
 * <pre>
 *   [{"iteration":0,"thought":"","tool_calls":null,"timestamp":"0001-01-01T00:00:00Z"}]
 * </pre>
 */
public class AgentStep {

    /** 轮次序号（0 起）。 */
    private int iteration;

    /** 本轮 Think 阶段的推理/思考文本。 */
    private String thought = "";

    /**
     * 本轮模型响应**之前**已消费的 steer 行，按投递顺序。
     * 与时间戳不同，这在回放时不歧义。空时省略。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> userMessagesBefore;

    /**
     * 一个普通回答之后紧接着一次循环结束的 steer —— 用它保住那个回答。
     * 规范的最终答案仍然存在 {@code Message.content} 里。false 时省略。
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean intermediateAnswer;

    /**
     * 本轮模型发出的 OpenAI 协议 {@code reasoning_content}。
     * 存在 AgentStep 上是为了跨轮回放能把它放回 assistant 消息——MiMo / DeepSeek V3.2+
     * 的思考模式要求如此，不认这个字段的厂商会忽略它。空时省略。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String reasoningContent;

    /** 本轮 Act 阶段调用的工具。**恒输出**：null → {@code null}。 */
    private List<ToolCall> toolCalls;

    /**
     * 本步发生的时间。零值输出 {@code 0001-01-01T00:00:00Z}，见类注释。
     *
     * <p>序列化与反序列化都自带（jsonb 读路径用的是没有 {@code JavaTimeModule} 的裸 mapper，
     * 只挂一半会在读回时炸）。</p>
     */


    private OffsetDateTime timestamp = GoTimeSerializer.GO_ZERO_DATE_TIME;

    public int getIteration() { return iteration; }
    public void setIteration(int v) { iteration = v; }

    public String getThought() { return thought; }
    public void setThought(String v) { thought = v == null ? "" : v; }

    public List<String> getUserMessagesBefore() { return userMessagesBefore; }
    public void setUserMessagesBefore(List<String> v) { userMessagesBefore = v; }

    public boolean isIntermediateAnswer() { return intermediateAnswer; }
    public void setIntermediateAnswer(boolean v) { intermediateAnswer = v; }

    public String getReasoningContent() { return reasoningContent; }

    /** 字符串零值约定为 ""：传入 null 归一为 ""。 */
    public void setReasoningContent(String v) { reasoningContent = v == null ? "" : v; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> v) { toolCalls = v; }

    /**
     * ⚠️ 返回的是**值类型语义**：未设置时给的是零值时间（{@code 0001-01-01T00:00:00Z}），
     * <b>不是 {@code null}</b>。判"有没有时间"要用
     * {@link GoTimeSerializer#isGoZero(OffsetDateTime)}。
     */
    public OffsetDateTime getTimestamp() { return timestamp; }

    /** 传入 {@code null} 等价于置回零值时间（不是"清除"）。 */
    public void setTimestamp(OffsetDateTime v) {
        timestamp = v == null ? GoTimeSerializer.GO_ZERO_DATE_TIME : v;
    }

    /**
     * 把本步所有工具调用的观察结果摊平，供向后兼容使用。
     *
     * <p>{@code @JsonIgnore}——不加的话 Jackson 会把这个 getter 当属性写进 jsonb，
     * 回读直接抛 {@code UnrecognizedPropertyException}（历史上复发率最高的坑）。</p>
     */
    @JsonIgnore
    public List<String> getObservations() {
        List<String> observations = new ArrayList<>();
        if (toolCalls == null) {
            return observations;
        }
        for (ToolCall call : toolCalls) {
            if (call.getResult() != null && !call.getResult().getOutput().isEmpty()) {
                observations.add(call.getResult().getOutput());
            }
            if (call.getReflection() != null && !call.getReflection().isEmpty()) {
                observations.add("Reflection: " + call.getReflection());
            }
        }
        return observations;
    }
}
