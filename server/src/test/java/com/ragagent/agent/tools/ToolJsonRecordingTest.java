package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.ToolJson;

/**
 * ToolJson 的 Go 实录语料（9 条：把 JSON 解析成树再重编码）。
 *
 * <p><b>2026-10-03（B42）</b>：Go 版已下线——HTML 转义形态不再构成断言目标（对比经
 * {@link com.ragagent.support.ContractJson#deep} 语义归一）；**键序**改由本类的
 * {@code keysAreSortedAlphabetically} 单独钉住（它是 LLM 载荷的确定性前提）。</p>
 */
class ToolJsonRecordingTest {

    private static final String[] CASES = {
            "R_CODEC_SORT_HTML",
            "R_CODEC_NESTED_SORT",
            "R_CODEC_UNICODE",
            "R_CODEC_ESCAPES",
            "R_CODEC_FLOATS",
            "R_CODEC_EMPTY_OBJ",
            "R_CODEC_EMPTY_ARR",
            "R_CODEC_NULL_VAL",
            "R_CODEC_BOOL_FALSE",
    };

    @Test
    void reencodeMatchesGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode tree = RecordingSupport.readTree(r.get("in").asText());
            assertThat(com.ragagent.support.ContractJson.deep(ToolJson.write(tree)))
                    .as("codec %s", r.get("id").asText())
                    .isEqualTo(com.ragagent.support.ContractJson.deep(r.get("out").asText()));
        }
    }

    /** 键序铁律：所有层级 map 键按字母序（LLM 载荷的确定性前提，B42 起单独钉住）。 */
    @Test
    void keysAreSortedAlphabetically() {
        JsonNode tree = RecordingSupport.readTree("{\"b\":1,\"a\":{\"d\":2,\"c\":[3]}}");
        assertThat(ToolJson.write(tree)).isEqualTo("{\"a\":{\"c\":[3],\"d\":2},\"b\":1}");
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45A.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
