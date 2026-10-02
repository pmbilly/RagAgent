package com.ragagent.agent.tools.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.tools.ToolRequest;
import com.ragagent.agent.tools.GoRecording45B;
import com.ragagent.agent.tools.RecordingSupport;

/**
 * SearchMemoryTool 的 Go 实录回放（6 条：hits/limit_clamp/limit_zero/disabled/empty/blank_query）。
 * output 文本逐字比对，data 键序无关规范化比对；limit 断言 stub 收到的值。
 */
class SearchMemoryRecordingTest {

    private static final String[] CASES = {
            "R_SEARCH_MEMORY_HITS",
            "R_SEARCH_MEMORY_LIMIT_CLAMP",
            "R_SEARCH_MEMORY_LIMIT_ZERO",
            "R_SEARCH_MEMORY_DISABLED",
            "R_SEARCH_MEMORY_EMPTY",
            "R_SEARCH_MEMORY_BLANK_QUERY",
    };

    @Test
    void executionsMatchGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode args = RecordingSupport.readTree(r.get("args").asText());
            CapturingStub stub = new CapturingStub(stubFor(r.get("id").asText()));
            var result = new SearchMemoryTool(stub).execute(ToolRequest.of(args));

            assertThat(result.isSuccess())
                    .as("search_memory %s success", r.get("id").asText())
                    .isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput())
                    .as("search_memory %s output", r.get("id").asText())
                    .isEqualTo(r.get("output").asText());
            if (r.hasNonNull("error") && !r.get("error").asText().isEmpty()) {
                assertThat(result.getError())
                        .as("search_memory %s error", r.get("id").asText())
                        .isEqualTo(r.get("error").asText());
            }
            JsonNode wantData = r.get("data");
            if (wantData == null || wantData.isNull()) {
                assertThat(result.getData()).as("search_memory %s data", r.get("id").asText()).isNull();
            } else {
                assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                        .as("search_memory %s data", r.get("id").asText())
                        .isEqualTo(RecordingSupport.canonicalJson(wantData));
            }
            if (r.has("limit")) {
                assertThat(stub.gotLimit)
                        .as("search_memory %s limit", r.get("id").asText())
                        .isEqualTo(r.get("limit").asInt());
            }
        }
    }

    /** 与 /tmp/toolrec45b 探针里 zzStubMemory 的种子逐一对齐。 */
    private static SearchMemoryTool.MemorySearchResultView stubFor(String id) {
        return switch (id) {
            case "hits" -> new SearchMemoryTool.MemorySearchResultView(true, java.util.Arrays.asList(
                    new SearchMemoryTool.MemoryItemView("fact", "生产数据库", "生产数据库已经迁到 PostgreSQL",
                            LocalDate.of(2026, 3, 1)),
                    new SearchMemoryTool.MemoryItemView("preference", "  ",
                            "喜欢 <暗色> 模式 & \"引号\"\n第二行\t缩进", LocalDate.of(2025, 12, 7)),
                    null,
                    new SearchMemoryTool.MemoryItemView("fact", "空内容", "  \n\t ", null),
                    new SearchMemoryTool.MemoryItemView("interest", "截断",
                            "长".repeat(400), LocalDate.of(2024, 1, 2))));
            case "limit_clamp", "limit_zero" ->
                    new SearchMemoryTool.MemorySearchResultView(true, List.of());
            case "disabled" -> new SearchMemoryTool.MemorySearchResultView(false, null);
            case "empty" -> new SearchMemoryTool.MemorySearchResultView(true, List.of());
            default -> new SearchMemoryTool.MemorySearchResultView(true, List.of());
        };
    }

    private static final class CapturingStub implements SearchMemoryTool.MemorySearch {
        final SearchMemoryTool.MemorySearchResultView result;
        int gotLimit;

        CapturingStub(SearchMemoryTool.MemorySearchResultView result) {
            this.result = result;
        }

        @Override
        public SearchMemoryTool.MemorySearchResultView search(String query, int limit) {
            this.gotLimit = limit;
            return result;
        }
    }

    private static String field(String name) {
        try {
            return (String) GoRecording45B.class.getField(name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
