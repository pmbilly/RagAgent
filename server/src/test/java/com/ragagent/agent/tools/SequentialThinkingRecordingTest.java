package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SequentialThinkingTool 的 Go 实录状态机（case1..case9 + case11 共 10 步，
 * 全部打在同一个工具实例上——thought_history_length 与 branches 是跨调用
 * 累积状态）。case10（裸 bad json）不录：Go 在 json.Unmarshal 层失败，
 * Java 的 args 在上游已解析成 JsonNode（分层差异，与 TodoWriteRecordingTest
 * 同款备案）。
 *
 * <p>校验失败（空 thought/number&lt;1/total&lt;1）不进历史——Go 先 validate
 * 后 append，case7/8/9 失败故 case11 的 history_length 接续 case6。</p>
 */
class SequentialThinkingRecordingTest {

    private static final String[] CASES = {
            "R_SEQTHINK_CASE1",
            "R_SEQTHINK_CASE2",
            "R_SEQTHINK_CASE3",
            "R_SEQTHINK_CASE4",
            "R_SEQTHINK_CASE5",
            "R_SEQTHINK_CASE6",
            "R_SEQTHINK_CASE7",
            "R_SEQTHINK_CASE8",
            "R_SEQTHINK_CASE9",
            "R_SEQTHINK_CASE11",
    };

    @Test
    void stateMachineMatchesGoRecording() {
        SequentialThinkingTool tool = new SequentialThinkingTool();
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode args = RecordingSupport.readTree(r.get("args").asText());
            var result = tool.execute(ToolRequest.of(args));

            assertThat(result.isSuccess())
                    .as("seqthink %s success", r.get("id").asText())
                    .isEqualTo(r.get("success").asBoolean());
            if (r.has("output")) {
                assertThat(result.getOutput())
                        .as("seqthink %s output", r.get("id").asText())
                        .isEqualTo(r.get("output").asText());
            }
            if (r.hasNonNull("goErr")) {
                // Go 的错误通道折叠进 result.Error（Java 签名说明见 ToolRegistry 类注释）
                assertThat(result.getError())
                        .as("seqthink %s error", r.get("id").asText())
                        .isEqualTo("Validation failed: " + validationMessage(r));
            } else {
                assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                        .as("seqthink %s data", r.get("id").asText())
                        .isEqualTo(RecordingSupport.canonicalJson(RecordingSupport.readTree(r.get("data").asText())));
            }
        }
    }

    /** case7/8/9 的 Go 校验错误文案（validate 逐字）。 */
    private static String validationMessage(JsonNode r) {
        String args = r.get("args").asText();
        if (args.contains("\"thought\":\"\"")) {
            return "invalid thought: must be a non-empty string";
        }
        if (args.contains("\"thought_number\":0")) {
            return "invalid thoughtNumber: must be >= 1";
        }
        return "invalid totalThoughts: must be >= 1";
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
