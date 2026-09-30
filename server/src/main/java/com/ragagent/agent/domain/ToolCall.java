package com.ragagent.agent.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.llm.ToolResult;

/**
 * agent 领域里的一次工具调用。
 *
 * <h2>⚠️ 与 {@link com.ragagent.llm.domain.ToolCall} 不是同一个类型</h2>
 * <p>本仓有<b>两个</b>同名类型，靠包区分：</p>
 * <ul>
 *   <li>{@link com.ragagent.llm.domain.ToolCall}：
 *       **OpenAI 协议形状**（{@code id}/{@code type}/{@code function}），
 *       是发给 provider 的请求体。</li>
 *   <li>本类：**agent 领域形状**
 *       （{@code name}/{@code args}/{@code result}/{@code reflection}/{@code duration}），
 *       落 {@code messages.agent_steps} jsonb。</li>
 * </ul>
 * <p>两者 JSON 完全不同，别互相顶替。</p>
 *
 * <h2>零值取舍（逐字段）</h2>
 * <ul>
 *   <li>{@code target} / {@code reflection} / {@code provider_metadata} 空时省略；</li>
 *   <li>{@code id} / {@code name} / {@code args} / {@code result} / {@code duration}
 *       <b>恒输出</b>。注意 {@code result} 未初始化时输出
 *       {@code "result":null}（实测确认，见 {@code AgentStepsJsonTest}）。</li>
 * </ul>
 */
public class ToolCall {

    /** 解析后的实际目标。null 时省略。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ToolCallTarget target;

    /** 来自 LLM 的 function call ID。 */
    private String id = "";

    /** 工具名。 */
    private String name = "";

    /** 工具参数。恒输出：null → {@code null}；键序递归恒排序（与既有 jsonb 记录逐字节一致）。 */

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> args;

    /** 执行结果（内含 Output）。**恒输出**：null → {@code null}。 */
    private ToolResult result;

    /** agent 对该结果的反思（启用时才有）。空时省略。 */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String reflection;

    /** 执行耗时（毫秒）。{@code 0} 恒输出。 */
    private long duration;

    /**
     * 厂商特有的工具调用状态，供回放。空时省略。
     * **值原样内联**（不是字符串化），故 Java 用 {@link JsonNode}。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, JsonNode> providerMetadata;

    public ToolCallTarget getTarget() { return target; }
    public void setTarget(ToolCallTarget v) { target = v; }

    public String getId() { return id; }
    public void setId(String v) { id = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }

    public Map<String, Object> getArgs() { return args; }
    public void setArgs(Map<String, Object> v) { args = v; }

    public ToolResult getResult() { return result; }
    public void setResult(ToolResult v) { result = v; }

    public String getReflection() { return reflection; }

    /** 字符串零值约定为 ""：传入 null 归一为 ""（两者在输出里都省略，语义一致）。 */
    public void setReflection(String v) { reflection = v == null ? "" : v; }

    public long getDuration() { return duration; }
    public void setDuration(long v) { duration = v; }

    public Map<String, JsonNode> getProviderMetadata() { return providerMetadata; }
    public void setProviderMetadata(Map<String, JsonNode> v) { providerMetadata = v; }

    /**
     * 有 target 时用 target 名做展示与追踪。
     *
     * <p>{@code @JsonIgnore}——这是派生值，不是持久化字段；不标会被 Jackson
     * 当属性写进 jsonb，回读抛 {@code UnrecognizedPropertyException}。</p>
     */
    @JsonIgnore
    public String getExecutionName() {
        return target != null ? target.getName() : name;
    }

    /** 有 target 时用 target 的参数，**不改回放数据**。 */
    @JsonIgnore
    public Map<String, Object> getExecutionArgs() {
        return target != null ? target.getArgs() : args;
    }
}
