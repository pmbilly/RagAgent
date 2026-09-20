package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JsonRepair 的 Go 实录语料（31 条，§9.1 方法：/tmp/toolrec 探针原样调用 Go
 * {@code RepairJSON/RepairJSONDetail}）。含 Go 测试文件全部用例 + 刻意探测的
 * 边界（单引号/未加引号的键不修——Go 就没有这俩修复，Java 侧注释声称的行为
 * 由本语料钉死）。
 */
class JsonRepairRecordingTest {

    private static final String[] CASES = {
            "R_JSONREPAIR_VALID",
            "R_JSONREPAIR_TRAILING_COMMA_OBJ",
            "R_JSONREPAIR_TRAILING_COMMA_ARR",
            "R_JSONREPAIR_MISSING_BRACE",
            "R_JSONREPAIR_MISSING_BRACKET_BRACE",
            "R_JSONREPAIR_EMPTY",
            "R_JSONREPAIR_TRUNCATED_STRING",
            "R_JSONREPAIR_NESTED_MISSING",
            "R_JSONREPAIR_COMMA_IN_STRING",
            "R_JSONREPAIR_VALID_COMPLEX",
            "R_JSONREPAIR_MULTI_TRAILING",
            "R_JSONREPAIR_ESCAPE_REGEX_PLUS",
            "R_JSONREPAIR_ESCAPE_REGEX_SHORTHAND",
            "R_JSONREPAIR_STRAY_FRAGMENT_EMPTY",
            "R_JSONREPAIR_STRAY_FRAGMENT_OBJ",
            "R_JSONREPAIR_DUPLICATED_PAYLOAD",
            "R_JSONREPAIR_BRACE_IN_STRING",
            "R_JSONREPAIR_VALID_ESCAPES",
            "R_JSONREPAIR_SINGLE_QUOTES",
            "R_JSONREPAIR_UNQUOTED_KEYS",
            "R_JSONREPAIR_APOSTROPHE_IN_STRING",
            "R_JSONREPAIR_EMPTY_OBJ",
            "R_JSONREPAIR_NESTED_OK",
            "R_JSONREPAIR_TRAILING_COMMA_NESTED",
            "R_JSONREPAIR_LEADING_WS",
            "R_JSONREPAIR_ONLY_WS",
            "R_JSONREPAIR_UNTERMINATED_AFTER_ESCAPE",
            "R_JSONREPAIR_SCALAR_NUMBER",
            "R_JSONREPAIR_SCALAR_STRING",
            "R_JSONREPAIR_ARRAY_TOP",
            "R_JSONREPAIR_EXTRA_AFTER_SCALAR",
    };

    @Test
    void corpusMatchesGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(constant(name));
            String in = r.get("in").asText();
            String wantOut = r.get("out").asText();
            String wantDetail = r.get("detail").asText();
            boolean wantChanged = r.get("changed").asBoolean();

            assertThat(JsonRepair.repairJson(in))
                    .as("repairJson %s (%s)", name, r.get("id").asText())
                    .isEqualTo(wantOut);
            JsonRepair.RepairResult detail = JsonRepair.repairJsonDetail(in);
            assertThat(detail.repaired())
                    .as("repairJsonDetail.repaired %s", name)
                    .isEqualTo(wantDetail);
            assertThat(detail.truncated())
                    .as("repairJsonDetail.truncated %s", name)
                    .isEqualTo(wantChanged);
        }
    }

    private static String constant(String name) {
        try {
            var f = GoRecording45A.class.getField(name);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
