package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.common.web.GoJsonCodec;

/**
 * GoJsonCodec 的 Go 实录语料（9 条：把 JSON 解析成树再按 Go json.Marshal
 * 语义重编码，探针原样执行 {@code json.Unmarshal + json.Marshal}）。
 * 钉死四条铁律：map 键字节序排序 / HTML 转义恒开 / Go 浮点形态 / 紧凑输出。
 */
class GoJsonCodecRecordingTest {

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
    void reencodeMatchesGoMarshalBytes() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode tree = RecordingSupport.readTree(r.get("in").asText());
            assertThat(GoJsonCodec.write(tree))
                    .as("codec %s", r.get("id").asText())
                    .isEqualTo(r.get("out").asText());
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
