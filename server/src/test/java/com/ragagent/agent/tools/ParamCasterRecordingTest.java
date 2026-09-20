package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ParamCaster 的 Go 实录判定表（29 条，探针原样调用 Go {@code CastParams}）。
 * 期望值是 Go 输出的原始字节——发生转型的 case 按 Go 的 map 重编码（键序 +
 * HTML 转义 + float64 语义），不转型的 case 原样返回 args 字节；
 * Java 侧统一用 {@link GoJsonCodec#write(JsonNode)} 编码后逐字节比对。
 */
class ParamCasterRecordingTest {

    private static final String[] CASES = {
            "R_CAST_STR_TO_BOOL",
            "R_CAST_STR_TO_BOOL_FALSE",
            "R_CAST_BOOL_YES",
            "R_CAST_BOOL_NO",
            "R_CAST_BOOL_NUM1",
            "R_CAST_BOOL_NUM0",
            "R_CAST_BOOL_UPPER",
            "R_CAST_STR_TO_INT",
            "R_CAST_STR_TO_INT_NEG",
            "R_CAST_STR_TO_INT_BAD",
            "R_CAST_FLOAT_WHOLE_TO_INT",
            "R_CAST_FLOAT_FRAC_STAYS",
            "R_CAST_STR_TO_FLOAT",
            "R_CAST_STR_TO_FLOAT_EXP",
            "R_CAST_STR_TO_FLOAT_BAD",
            "R_CAST_NO_CHANGE",
            "R_CAST_NIL_SCHEMA",
            "R_CAST_EMPTY_SCHEMA",
            "R_CAST_UNKNOWN_KEY_KEPT",
            "R_CAST_STR_TO_STR_ARRAY",
            "R_CAST_STR_JSON_ARRAY",
            "R_CAST_STR_JSON_OBJ_NOT_ARR",
            "R_CAST_BOOL_TO_STR",
            "R_CAST_NUM_TO_STR",
            "R_CAST_WHOLE_FLOAT_TO_STR",
            "R_CAST_INT_TO_STR",
            "R_CAST_ARRAY_TO_ARRAY",
            "R_CAST_BOOL_STAYS_BOOL",
            "R_CAST_MAP_KEY_SORT_AFTER_CAST",
    };

    @Test
    void decisionTableMatchesGoRecording() throws Exception {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            String args = r.get("args").asText();
            String schemaJson = r.get("schema").asText();
            String want = r.get("out").asText();

            JsonNode argsNode = RecordingSupport.readTree(args);
            JsonNode schemaNode = schemaJson.isEmpty() ? null : RecordingSupport.readTree(schemaJson);
            JsonNode got = ParamCaster.castParams(argsNode, schemaNode);
            assertThat(GoJsonCodec.write(got))
                    .as("castParams %s", r.get("id").asText())
                    .isEqualTo(want);
        }
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
