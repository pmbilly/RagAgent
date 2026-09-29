package com.ragagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@code agent_steps}（Go {@code types.AgentSteps}）的逐字节契约测试。
 *
 * <h2>期望值的来源</h2>
 * <p>把 {@code internal/types/agent.go} 的 {@code AgentStep} / {@code ToolCall} /
 * {@code ToolCallTarget} / {@code ToolResult} 原样抄进一个独立 Go 程序，
 * 喂同样的输入打印 {@code json.Marshal} 结果，再抄进下面的常量。</p>
 *
 * <p><b>录制时用 {@code time.Local}（本机 CST +0800）而不是 {@code time.UTC}</b>——
 * Java 侧 {@code GoTimeSerializer} 按 §9 的既定规则把时间归一化到 JVM 默认时区，
 * 用 UTC 录的话两边会差一个时区偏移，那是**录制方法**的问题不是实现的问题。</p>
 *
 * <p>这份语料同时钉住三件容易看走眼的事：</p>
 * <ol>
 *   <li>{@code result} <b>没有</b> omitempty（Go 是指针）→ nil 也要输出 {@code "result":null}；</li>
 *   <li>{@code tool_calls} <b>没有</b> omitempty → nil 输出 {@code "toolCalls":null}；</li>
 *   <li>{@code timestamp} 是 Go 的**值类型** → 零值输出 {@code "0001-01-01T00:00:00Z"}。</li>
 * </ol>
 */
class AgentStepsJsonTest {

    /** 键名 = Java 字段名（camelCase，无逐字段注解）：裸 mapper（本类型的两个时间方法自带序列化器，不需要 JavaTimeModule）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本机时区，见类注释。 */
    private static OffsetDateTime localTime(int hour) {
        return java.time.ZonedDateTime.of(2026, 9, 18, hour, 0, 0, 0, ZoneId.systemDefault())
                .toOffsetDateTime();
    }

    private static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    // ── 场景 1：满字段 ──────────────────────────────────────────────────────

    @Test
    void fullStepWireFormat() throws Exception {
        ToolCallTarget target = new ToolCallTarget();
        target.setName("svc.tool");
        target.setServiceName("svc");
        target.setToolName("tool");
        Map<String, Object> targetArgs = new LinkedHashMap<>();
        targetArgs.put("b", 1);
        targetArgs.put("a", 2);
        target.setArgs(targetArgs);

        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setOutput("out");
        result.setData(Map.of("k", "v"));
        result.setImages(List.of("i"));

        ToolCall call = new ToolCall();
        call.setTarget(target);
        call.setId("call-1");
        call.setName("search");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zeta", "z");
        args.put("alpha", "a");
        call.setArgs(args);
        call.setResult(result);
        call.setReflection("ref");
        call.setDuration(42);
        call.setProviderMetadata(Map.of("gemini", MAPPER.readTree("{\"x\":1}")));

        AgentStep step = new AgentStep();
        step.setIteration(0);
        step.setThought("thinking");
        step.setUserMessagesBefore(List.of("m1"));
        step.setIntermediateAnswer(true);
        step.setReasoningContent("rc");
        step.setToolCalls(List.of(call));
        step.setTimestamp(localTime(10));

        assertThat(write(List.of(step))).isEqualTo(
                "[{\"iteration\":0,\"thought\":\"thinking\",\"userMessagesBefore\":[\"m1\"],"
                        + "\"intermediateAnswer\":true,\"reasoningContent\":\"rc\",\"toolCalls\":[{"
                        + "\"target\":{\"name\":\"svc.tool\",\"args\":{\"a\":2,\"b\":1},"
                        + "\"serviceName\":\"svc\",\"toolName\":\"tool\"},"
                        + "\"id\":\"call-1\",\"name\":\"search\",\"args\":{\"alpha\":\"a\",\"zeta\":\"z\"},"
                        + "\"result\":{\"success\":true,\"output\":\"out\",\"data\":{\"k\":\"v\"},"
                        + "\"images\":[\"i\"]},"
                        + "\"reflection\":\"ref\",\"duration\":42,"
                        + "\"providerMetadata\":{\"gemini\":{\"x\":1}}}],"
                        + "\"timestamp\":\"2026-09-18T10:00:00+08:00\"}]");
    }

    // ── 场景 2/3：零值取舍 ──────────────────────────────────────────────────

    /** 只设 timestamp：其余零值字段该省的省、该占位的占位。 */
    @Test
    void minimalStepWireFormat() throws Exception {
        AgentStep step = new AgentStep();
        step.setTimestamp(localTime(10));

        assertThat(write(List.of(step))).isEqualTo(
                "[{\"iteration\":0,\"thought\":\"\",\"toolCalls\":null,"
                        + "\"timestamp\":\"2026-09-18T10:00:00+08:00\"}]");
    }

    /** 全零实例：Go 的零值 {@code time.Time} 输出 year-1 字面量，**不是** {@code null}。 */
    @Test
    void zeroStepWireFormat() throws Exception {
        assertThat(write(List.of(new AgentStep()))).isEqualTo(
                "[{\"iteration\":0,\"thought\":\"\",\"toolCalls\":null,"
                        + "\"timestamp\":\"0001-01-01T00:00:00Z\"}]");
    }

    /** nil 切片 → Go 的 {@code json.Marshal} 输出 {@code null}。 */
    @Test
    void nilStepsSerializeAsNull() throws Exception {
        assertThat(write((List<AgentStep>) null)).isEqualTo("null");
    }

    /** {@code result} 没有 omitempty：nil 也要输出 {@code "result":null}。 */
    @Test
    void nilResultStillEmitsTheKey() throws Exception {
        ToolCall call = new ToolCall();
        call.setId("c");

        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(call));

        assertThat(write(List.of(step))).isEqualTo(
                "[{\"iteration\":0,\"thought\":\"\",\"toolCalls\":[{"
                        + "\"id\":\"c\",\"name\":\"\",\"args\":null,\"result\":null,\"duration\":0}],"
                        + "\"timestamp\":\"0001-01-01T00:00:00Z\"}]");
    }

    // ── 派生方法不能泄漏成 JSON 键（§9 复发率最高的坑） ───────────────────────

    /**
     * {@code getObservations()} / {@code getExecutionName()} / {@code getExecutionArgs()}
     * 在 Go 里都是**方法**。漏 {@code @JsonIgnore} 就会写进 jsonb，
     * 回读时抛 {@code UnrecognizedPropertyException} 让整列不可用。
     */
    @Test
    void derivedAccessorsNeverLeakIntoJson() throws Exception {
        ToolCall call = new ToolCall();
        call.setId("c");
        call.setName("n");
        call.setArgs(Map.of("a", "1"));
        call.setResult(new ToolResult());

        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(call));

        String out = write(List.of(step));
        assertThat(out).doesNotContain("observations");
        assertThat(out).doesNotContain("execution_name");
        assertThat(out).doesNotContain("execution_args");
    }

    @Test
    void observationsFlattensOutputsAndReflections() {
        ToolResult withOutput = new ToolResult();
        withOutput.setOutput("out");
        ToolCall first = new ToolCall();
        first.setResult(withOutput);
        first.setReflection("ref");

        ToolCall second = new ToolCall();
        second.setResult(new ToolResult()); // 空输出 → 不计入
        second.setReflection("");

        AgentStep step = new AgentStep();
        step.setToolCalls(List.of(first, second));

        assertThat(step.getObservations()).containsExactly("out", "Reflection: ref");
    }

    /** {@code ExecutionName/A rgs} 有 target 时走 target，否则回落模型发出的原始值。 */
    @Test
    void executionNameAndArgsPreferTarget() {
        ToolCall call = new ToolCall();
        call.setName("model-name");
        call.setArgs(Map.of("from", "model"));
        assertThat(call.getExecutionName()).isEqualTo("model-name");
        assertThat(call.getExecutionArgs()).isEqualTo(Map.of("from", "model"));

        ToolCallTarget target = new ToolCallTarget();
        target.setName("resolved");
        target.setArgs(Map.of("from", "target"));
        call.setTarget(target);
        assertThat(call.getExecutionName()).isEqualTo("resolved");
        assertThat(call.getExecutionArgs()).isEqualTo(Map.of("from", "target"));
    }

    // ── 读回（jsonb 路径用的是没有 JavaTimeModule 的裸 mapper） ───────────────

    @Test
    void readsBackThroughTheBareMapper() throws Exception {
        String raw = "[{\"iteration\":2,\"thought\":\"t\",\"toolCalls\":null,"
                + "\"timestamp\":\"2026-09-18T10:00:00+08:00\"}]";
        List<AgentStep> steps = MAPPER.readValue(raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<AgentStep>>() {});
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getIteration()).isEqualTo(2);
        assertThat(steps.get(0).getTimestamp()).isNotNull();
    }

    /**
     * year-1 字面量读回**零值时间**（不是 {@code null}——Go 的值类型没有"缺省"），
     * 于是再写出去还是 year-1——往返幂等。
     */
    @Test
    void zeroTimeLiteralRoundTripsToItself() throws Exception {
        String raw = "[{\"iteration\":0,\"thought\":\"\",\"toolCalls\":null,"
                + "\"timestamp\":\"0001-01-01T00:00:00Z\"}]";
        List<AgentStep> steps = MAPPER.readValue(raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<AgentStep>>() {});
        assertThat(com.ragagent.common.web.GoTimeSerializer.isGoZero(steps.get(0).getTimestamp()))
                .as("Go 的零值时间读回仍是零值，对应 t.IsZero()")
                .isTrue();
        assertThat(write(steps)).isEqualTo(raw);
    }

    /** 未知键必须被容忍（Go 的 json.Unmarshal 默认忽略），否则历史行整条读不出来。 */
    @Test
    void unknownKeysAreTolerated() throws Exception {
        String raw = "[{\"iteration\":0,\"toolCalls\":null,"
                + "\"timestamp\":\"0001-01-01T00:00:00Z\",\"future_field\":123}]";
        List<AgentStep> steps = tolerantMapper().readValue(raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<AgentStep>>() {});
        assertThat(steps).hasSize(1);
    }

    /** 与 {@code AbstractJsonListTypeHandler} 同一套配置。 */
    private static ObjectMapper tolerantMapper() {
        return new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** {@code JsonNode} 值原样内联，不是字符串。 */
    @Test
    void providerMetadataValuesAreInlined() throws Exception {
        JsonNode raw = MAPPER.readTree("{\"x\":1}");
        ToolCall call = new ToolCall();
        call.setProviderMetadata(Map.of("gemini", raw));

        AgentStep step = new AgentStep();
        step.setToolCalls(new ArrayList<>(List.of(call)));

        assertThat(write(List.of(step))).contains("\"providerMetadata\":{\"gemini\":{\"x\":1}}");
    }
}
