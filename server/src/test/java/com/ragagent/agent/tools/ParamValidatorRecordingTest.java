package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ParamValidator 的 Go 实录判定表（15 条 ValidateParams + 3 条
 * FormatValidationErrors，探针原样调用 Go 函数）。错误文案逐字比对
 * （"required parameter 'query' is missing"、"must be >= 1"、
 * "must be one of ..." 等——registry 校验失败信息是发给模型的契约）。
 */
class ParamValidatorRecordingTest {

    private static final String[] CASES = {
            "R_VALIDATE_VALID",
            "R_VALIDATE_MISSING_REQUIRED",
            "R_VALIDATE_NULL_REQUIRED",
            "R_VALIDATE_WRONG_TYPE",
            "R_VALIDATE_ENUM_VIOLATION",
            "R_VALIDATE_MINIMUM_VIOLATION",
            "R_VALIDATE_MAXIMUM_VIOLATION",
            "R_VALIDATE_MINLENGTH_VIOLATION",
            "R_VALIDATE_NUMBER_BOUNDS",
            "R_VALIDATE_MULTIPLE_ERRORS",
            "R_VALIDATE_EXTRA_ALLOWED",
            "R_VALIDATE_BOOL_TYPE",
            "R_VALIDATE_ARRAY_MINITEMS",
            "R_VALIDATE_ARRAY_ITEM_TYPE",
            "R_VALIDATE_EMPTY_ARGS",
    };

    @Test
    void decisionTableMatchesGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode argsNode = r.get("args").asText().isEmpty()
                    ? null
                    : RecordingSupport.readTree(r.get("args").asText());
            String schema = schemaFor(r.get("id").asText());
            JsonNode wantErrs = RecordingSupport.readTree(r.get("errs").asText());

            List<ParamValidator.ValidationError> got = ParamValidator.validateParams(
                    argsNode, RecordingSupport.readTree(schema));
            assertThat(got.size())
                    .as("validateParams %s count", r.get("id").asText())
                    .isEqualTo(wantErrs.size());
            for (int i = 0; i < wantErrs.size(); i++) {
                JsonNode want = wantErrs.get(i);
                assertThat(got.get(i).param())
                        .as("validateParams %s [%d].param", r.get("id").asText(), i)
                        .isEqualTo(want.get("Param").asText());
                assertThat(RecordingSupport.normalizeNumberText(got.get(i).message()))
                        .as("validateParams %s [%d].message", r.get("id").asText(), i)
                        .isEqualTo(RecordingSupport.normalizeNumberText(want.get("Message").asText()));
            }
        }
    }

    @Test
    void formatValidationErrorsMatchesGoRecording() {
        JsonNode nil = RecordingSupport.rec(field("R_FMTVAL_NIL"));
        assertThat(ParamValidator.formatValidationErrors(List.of())).isEqualTo(nil.get("out").asText());

        JsonNode single = RecordingSupport.rec(field("R_FMTVAL_SINGLE"));
        assertThat(ParamValidator.formatValidationErrors(List.of(
                new ParamValidator.ValidationError("q", "required parameter 'q' is missing"))))
                .isEqualTo(single.get("out").asText());

        JsonNode multi = RecordingSupport.rec(field("R_FMTVAL_MULTI"));
        assertThat(ParamValidator.formatValidationErrors(List.of(
                new ParamValidator.ValidationError("a", "error a"),
                new ParamValidator.ValidationError("b", "error b"))))
                .isEqualTo(multi.get("out").asText());
    }

    /** 与探针里的 valSchema 逐字一致（含 tags minItems 扩展）。 */
    private static String schemaFor(String id) {
        return """
                {
                \t"type": "object",
                \t"properties": {
                \t\t"query": {"type": "string", "minLength": 1},
                \t\t"limit": {"type": "integer", "minimum": 1, "maximum": 100},
                \t\t"mode":  {"type": "string", "enum": ["fast", "deep"]},
                \t\t"score": {"type": "number", "minimum": 0, "maximum": 1},
                \t\t"enabled": {"type": "boolean"},
                \t\t"tags": {"type": "array", "items": {"type": "string"}, "minItems": 2}
                \t},
                \t"required": ["query"]
                }""";
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
