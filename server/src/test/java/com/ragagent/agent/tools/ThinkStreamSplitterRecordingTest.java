package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * ThinkStreamSplitter 的 Go 实录（13 组逐 chunk 语料，探针原样驱动 Go
 * {@code ThinkStreamSplitter.Feed/Flush}）。标签跨 chunk 切分/未闭合
 * flush 归类/字面小于号保留，按每次 Feed 的 (think, answer) 双输出逐字比对。
 */
class ThinkStreamSplitterRecordingTest {

    private static final String[] CASES = {
            "R_THINKSTREAM_PLAIN",
            "R_THINKSTREAM_SINGLE",
            "R_THINKSTREAM_OPEN_SPLIT",
            "R_THINKSTREAM_CLOSE_SPLIT",
            "R_THINKSTREAM_MULTI_CHUNK",
            "R_THINKSTREAM_PREFIX",
            "R_THINKSTREAM_TWO_BLOCKS",
            "R_THINKSTREAM_UNTERMINATED",
            "R_THINKSTREAM_LESS_THAN",
            "R_THINKSTREAM_EMPTY_CHUNKS",
            "R_THINKSTREAM_TAG_SPANNING_FLUSH",
            "R_THINKSTREAM_CLOSE_PARTIAL_THEN_MORE",
            "R_THINKSTREAM_THINK_THEN_THINK",
    };

    @Test
    void feedsMatchGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode feeds = r.get("feeds");
            ThinkStreamSplitter sp = new ThinkStreamSplitter();
            StringBuilder thinkAll = new StringBuilder();
            StringBuilder answerAll = new StringBuilder();
            for (int i = 0; i < feeds.size(); i++) {
                JsonNode f = feeds.get(i);
                ThinkStreamSplitter.FeedResult got;
                if ("<flush>".equals(f.get("In").asText())) {
                    got = sp.flush();
                } else {
                    got = sp.feed(f.get("In").asText());
                }
                assertThat(got.think())
                        .as("%s feed[%d].think", r.get("id").asText(), i)
                        .isEqualTo(f.get("Think").asText());
                assertThat(got.answer())
                        .as("%s feed[%d].answer", r.get("id").asText(), i)
                        .isEqualTo(f.get("Answer").asText());
                thinkAll.append(got.think());
                answerAll.append(got.answer());
            }
            assertThat(thinkAll.toString()).as("%s thinkAll", r.get("id").asText())
                    .isEqualTo(r.get("think").asText());
            assertThat(answerAll.toString()).as("%s answerAll", r.get("id").asText())
                    .isEqualTo(r.get("answer").asText());
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
