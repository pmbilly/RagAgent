package com.ragagent.agent.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ragagent.common.web.GoMapSerializer;

/**
 * agent 领域里的一次工具调用（对照 Go {@code types.ToolCall}，
 * internal/types/agent.go:363-374）。
 *
 * <h2>⚠️ 与 {@link com.ragagent.llm.domain.ToolCall} 不是同一个类型</h2>
 * <p>Go 里有<b>两个</b>同名类型，Java 侧保留同名、靠包区分：</p>
 * <ul>
 *   <li>{@code chat.ToolCall} → {@link com.ragagent.llm.domain.ToolCall}：
 *       **OpenAI 协议形状**（{@code id}/{@code type}/{@code function}），
 *       是发给 provider 的请求体。</li>
 *   <li>{@code types.ToolCall} → 本类：**agent 领域形状**
 *       （{@code name}/{@code args}/{@code result}/{@code reflection}/{@code duration}），
 *       落 {@code messages.agent_steps} jsonb。</li>
 * </ul>
 * <p>两者 JSON 完全不同，别互相顶替。</p>
 *
 * <h2>零值取舍（逐字段对照 json tag）</h2>
 * <ul>
 *   <li>{@code target} / {@code reflection} / {@code provider_metadata} 带 omitempty → 空时省略；</li>
 *   <li>{@code id} / {@code name} / {@code args} / {@code result} / {@code duration}
 *       <b>无</b> omitempty → 恒输出。注意 {@code result} 是<b>指针且无 omitempty</b>：
 *       nil 输出 {@code "result":null}（实测确认，见 {@code AgentStepsJsonTest}）。</li>
 * </ul>
 */
@JsonPropertyOrder({"target", "id", "name", "args", "result", "reflection", "duration", "provider_metadata"})
public class ToolCall {

    /** 解析后的实际目标。omitempty（Go 是指针）。 */
    @JsonProperty("target")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ToolCallTarget target;

    /** 来自 LLM 的 function call ID。 */
    @JsonProperty("id")
    private String id = "";

    /** 工具名。 */
    @JsonProperty("name")
    private String name = "";

    /** 工具参数。无 omitempty：nil → {@code null}；键序递归对齐 Go（map 恒排序）。 */
    @JsonProperty("args")
    @JsonSerialize(using = GoMapSerializer.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Map<String, Object> args;

    /** 执行结果（内含 Output）。**无 omitempty**：nil → {@code null}。 */
    @JsonProperty("result")
    private ToolResult result;

    /** agent 对该结果的反思（启用时才有）。omitempty。 */
    @JsonProperty("reflection")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String reflection;

    /** 执行耗时（毫秒）。无 omitempty：{@code 0} 恒输出。 */
    @JsonProperty("duration")
    private long duration;

    /**
     * 厂商特有的工具调用状态，供回放。omitempty。
     * Go 是 {@code ToolCallMetadata = map[string]json.RawMessage}——**值原样内联**
     * （不是字符串），故 Java 用 {@link JsonNode}。
     */
    @JsonProperty("provider_metadata")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonSerialize(using = GoMapSerializer.class)
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

    /** Go 的 string 零值是 ""，传入 null 归一为 ""（omitempty 下两者都不输出，语义一致）。 */
    public void setReflection(String v) { reflection = v == null ? "" : v; }

    public long getDuration() { return duration; }
    public void setDuration(long v) { duration = v; }

    public Map<String, JsonNode> getProviderMetadata() { return providerMetadata; }
    public void setProviderMetadata(Map<String, JsonNode> v) { providerMetadata = v; }

    /**
     * 对照 Go {@code ToolCall.ExecutionName}：有 target 时用 target 名做展示与追踪。
     *
     * <p>{@code @JsonIgnore}——Go 里是方法，不是字段。名字也不是 {@code getXxx}/{@code isXxx}
     * 形态，但显式标注可以防住将来有人把它改成 getter。</p>
     */
    @JsonIgnore
    public String getExecutionName() {
        return target != null ? target.getName() : name;
    }

    /** 对照 Go {@code ToolCall.ExecutionArgs}：有 target 时用 target 的参数，**不改回放数据**。 */
    @JsonIgnore
    public Map<String, Object> getExecutionArgs() {
        return target != null ? target.getArgs() : args;
    }
}
