package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.support.ContractJson;
import com.ragagent.common.llm.ToolResult;
import com.ragagent.common.error.AppError;
import com.ragagent.common.error.BizException;

/**
 * ToolRegistry 的 Go 实录（17 条，探针用 Go mockTool/outcomeTool 复刻注册/
 * 发现/执行管线）。覆盖：first-wins 拒绝重名、ListTools/defs 按名排序且
 * 字节稳定、deferred 不进 model 投影、GetTool 错误文案、退役工具替代文案、
 * (nil,err)/(nil,nil) 结果归一化、小上限截断、校验失败信息与三个工具的
 * hint 拼接、cast-then-validate 管线。
 *
 * <p>已知通道差异（备案）：Go 的 {@code (result, err)} 双通道在 Java 折叠为
 * 单返回——"工具返回 success=true 的 result 同时报 err"的形态不可表达；
 * Java 工具直接用 {@code success=false + error} 表达失败（registry 原样透传，
 * 见 {@code denied} case）。 {@code boom} case 因此按"工具已把错误写进
 * result"的等价形态录制断言。</p>
 */
class ToolRegistryRecordingTest {

    /** 对照 mockTool：返回 Success=true 的最小工具。 */
    private static class MockTool implements AgentTool {
        private final String name;
        private final String description;
        private final JsonNode parameters;
        private final ToolResult outcome;

        MockTool(String name, String description, String parameters) {
            this(name, description, parameters, null);
        }

        MockTool(String name, String description, String parameters, ToolResult outcome) {
            this.name = name;
            this.description = description;
            this.parameters = RecordingSupport.readTree(parameters);
            this.outcome = outcome;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public JsonNode getParameters() {
            return parameters;
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            if (outcome != null) {
                return outcome;
            }
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            return r;
        }
    }

    /** 对照 outcomeTool：返回 null result 的工具（Go 的 (nil, nil) 形态）。 */
    private static final class NullTool extends MockTool {
        NullTool(String name, String parameters) {
            super(name, "", parameters);
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return null;
        }
    }

    private static ToolRegistry buildRegistry() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerTool(new MockTool("search", "original", "{\"type\":\"object\",\"title\":\"search\"}"));
        reg.registerTool(new MockTool("search", "impostor", "{\"type\":\"object\"}"));
        reg.registerTool(new MockTool("alpha", "desc-alpha", "{\"type\":\"object\",\"title\":\"alpha\"}"));
        reg.registerTool(new MockTool("zeta", "desc-zeta", "{\"type\":\"object\",\"title\":\"zeta\"}"));
        reg.registerDeferredTool(new MockTool("hidden", "desc-hidden", "{\"type\":\"object\",\"title\":\"hidden\"}"));
        ToolResult denied = new ToolResult();
        denied.setSuccess(false);
        denied.setError("permission denied");
        reg.registerTool(new MockTool("denied", "", "{\"type\":\"object\"}", denied));
        ToolResult boom = new ToolResult();
        boom.setSuccess(false);
        boom.setError("transport failed");
        reg.registerTool(new MockTool("boom", "", "{\"type\":\"object\"}", boom));
        reg.registerTool(new NullTool("nilres", "{\"type\":\"object\"}"));
        ToolResult big = new ToolResult();
        big.setSuccess(true);
        big.setOutput("x".repeat(100));
        reg.registerTool(new MockTool("bigout", "", "{\"type\":\"object\"}", big));
        return reg;
    }

    private static String goJson(Object v) {
        try {
            return RecordingSupport.GO_MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void registrationAndDiscoveryMatchGoRecording() {
        ToolRegistry reg = buildRegistry();

        JsonNode listTools = RecordingSupport.rec(field("R_REGISTRY_LIST_TOOLS"));
        List<String> wantNames = new ArrayList<>();
        RecordingSupport.readTree(listTools.get("out").asText()).forEach(n -> wantNames.add(n.asText()));
        assertThat(reg.listTools()).containsExactlyElementsOf(wantNames);

        JsonNode defs = RecordingSupport.rec(field("R_REGISTRY_DEFS"));
        assertThat(goJson(reg.getFunctionDefinitions())).isEqualTo(defs.get("out").asText());

        JsonNode modelDefs = RecordingSupport.rec(field("R_REGISTRY_MODEL_DEFS"));
        assertThat(goJson(reg.getModelFunctionDefinitions())).isEqualTo(modelDefs.get("out").asText());

        JsonNode getMissing = RecordingSupport.rec(field("R_REGISTRY_GET_MISSING"));
        assertThatThrownBy(() -> reg.getTool("missing_tool"))
                .isInstanceOf(ToolRegistry.ToolNotFoundException.class)
                .hasMessage(getMissing.get("out").asText());
    }

    @Test
    void executePipelineMatchesGoRecording() {
        ToolRegistry reg = buildRegistry();

        assertResult(reg, "denied", "R_REGISTRY_DENIED");
        assertResult(reg, "nilres", "R_REGISTRY_NILRES");
        assertResult(reg, "not_a_tool", "R_REGISTRY_NOT_FOUND");
        assertResult(reg, ToolDefinitions.LEGACY_TOOL_EXECUTE_SKILL_SCRIPT,
                "R_REGISTRY_LEGACY_EXECUTE_SKILL_SCRIPT");
        assertResult(reg, ToolDefinitions.LEGACY_TOOL_READ_SKILL, "R_REGISTRY_LEGACY_READ_SKILL");
        assertResult(reg, ToolDefinitions.LEGACY_TOOL_READ_SANDBOX_FILE,
                "R_REGISTRY_LEGACY_READ_SANDBOX_FILE");

        reg.setMaxToolOutputSize(50);
        assertResult(reg, "bigout", "R_REGISTRY_TRUNCATE50");
    }

    @Test
    void validationPathMatchesGoRecording() {
        ToolRegistry reg = new ToolRegistry();
        String typedSchema = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1},"
                + "\"limit\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"query\"]}";
        reg.registerTool(new MockTool("typed", "d", typedSchema));

        assertResult(reg, "typed", "R_REGISTRY_VALIDATION_FAILED", RecordingSupport.readTree("{\"limit\":0}"));
        assertResult(reg, "typed", "R_REGISTRY_CAST_THEN_VALIDATE",
                RecordingSupport.readTree("{\"query\":\"hello\",\"limit\":\"5\"}"));
    }

    @Test
    void hintAppendingMatchesGoRecording() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerTool(new MockTool(ToolDefinitions.TOOL_CALL_MCP_TOOL, "d",
                "{\"type\":\"object\",\"properties\":{\"arguments\":{\"type\":\"object\"}},\"required\":[\"arguments\"]}"));
        reg.registerTool(new MockTool(ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, "d",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\",\"content\"]}"));
        reg.registerTool(new MockTool(ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, "d",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"edits\":{\"type\":\"array\"}},"
                        + "\"required\":[\"path\",\"edits\"]}"));

        assertResult(reg, ToolDefinitions.TOOL_CALL_MCP_TOOL, "R_REGISTRY_HINT_MCP",
                RecordingSupport.readTree("{}"));
        assertResult(reg, ToolDefinitions.TOOL_WRITE_SANDBOX_FILE, "R_REGISTRY_HINT_WRITE",
                RecordingSupport.readTree("{\"path\":\"/a\"}"));
        assertResult(reg, ToolDefinitions.TOOL_EDIT_SANDBOX_FILE, "R_REGISTRY_HINT_EDIT",
                RecordingSupport.readTree("{\"path\":\"/a\"}"));
    }

    private static void assertResult(ToolRegistry reg, String name, String constant) {
        assertResult(reg, name, constant, RecordingSupport.readTree("{}"));
    }

    private static void assertResult(ToolRegistry reg, String name, String constant, JsonNode args) {
        JsonNode r = RecordingSupport.rec(field(constant));
        ToolResult got = reg.executeTool(name, args);
        // B40 基线重建：两侧经 ContractJson.deep 归一（转义形态不再构成断言目标）。
        assertThat(ContractJson.deep(RecordingSupport.normalizeNumberText(goJson(got))))
                .as("executeTool %s (%s)", name, r.get("id").asText())
                .isEqualTo(ContractJson.deep(RecordingSupport.normalizeNumberText(r.get("result").asText())));
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("工具抛异常 → 文案照 Go 的 err.Error()：AppError 带前缀、裸异常用 message（W5γ5.11）")
    void thrownToolErrorTextFollowsGoErrError() {
        ToolRegistry reg = new ToolRegistry();
        reg.registerTool(new ThrowingTool("kb-broken", new BizException(new AppError(
                2200, "vector store bound to the knowledge base is not available", null, 400))));
        reg.registerTool(new ThrowingTool("plain-boom", new IllegalStateException("transport failed")));

        ToolResult broken = reg.executeTool("kb-broken", RecordingSupport.readTree("{}"));
        assertThat(broken.isSuccess()).isFalse();
        assertThat(broken.getError()).isEqualTo("error code: 2200, error message: "
                + "vector store bound to the knowledge base is not available");

        ToolResult plain = reg.executeTool("plain-boom", RecordingSupport.readTree("{}"));
        assertThat(plain.isSuccess()).isFalse();
        assertThat(plain.getError()).isEqualTo("transport failed");

        // wireText 还要能穿过包装层（Go 的 err.Error() 在 fmt.Errorf 包裹后仍带内层原文）
        assertThat(BizException.wireText(new RuntimeException(
                new BizException(new AppError(2201, "vector store is not registered", null, 400)))))
                .isEqualTo("error code: 2201, error message: vector store is not registered");
        assertThat(BizException.wireText(new RuntimeException("boom"))).isEqualTo("boom");
        assertThat(BizException.wireText(new RuntimeException())).isNotBlank();
    }

    /** 抛异常的工具（Go 侧等价于 return nil, err）。 */
    private static class ThrowingTool implements AgentTool {
        private final String name;
        private final RuntimeException error;

        ThrowingTool(String name, RuntimeException error) {
            this.name = name;
            this.error = error;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public JsonNode getParameters() {
            return RecordingSupport.readTree("{\"type\":\"object\"}");
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            throw error;
        }
    }
}
