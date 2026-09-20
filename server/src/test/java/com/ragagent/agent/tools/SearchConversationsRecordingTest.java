package com.ragagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * SearchConversationsTool 的 Go 实录回放（7 条）。output 逐字比对；limit/owner 断言
 * stub 收到的值（对照 Go 超取 limit+2 与构造期 ownerID）。
 */
class SearchConversationsRecordingTest {

    private static final String[] CASES = {
            "R_SEARCH_CONVERSATIONS_HITS",
            "R_SEARCH_CONVERSATIONS_LIMIT_CLAMP",
            "R_SEARCH_CONVERSATIONS_EMPTY",
            "R_SEARCH_CONVERSATIONS_SERVICE_ERROR",
            "R_SEARCH_CONVERSATIONS_ONLY_CURRENT",
            "R_SEARCH_CONVERSATIONS_BLANK_QUERY",
    };

    @Test
    void executionsMatchGoRecording() {
        for (String name : CASES) {
            JsonNode r = RecordingSupport.rec(field(name));
            JsonNode args = RecordingSupport.readTree(r.get("args").asText());
            CapturingStub stub = new CapturingStub(stubFor(r.get("id").asText()));
            var tool = new SearchConversationsTool(stub, "owner-1", "sess-current");
            ToolResult result = tool.execute(ToolRequest.of(args));

            assertThat(result.isSuccess())
                    .as("search_conversations %s success", r.get("id").asText())
                    .isEqualTo(r.get("success").asBoolean());
            assertThat(result.getOutput())
                    .as("search_conversations %s output", r.get("id").asText())
                    .isEqualTo(r.get("output").asText());
            if (r.hasNonNull("error") && !r.get("error").asText().isEmpty()) {
                assertThat(result.getError())
                        .as("search_conversations %s error", r.get("id").asText())
                        .isEqualTo(r.get("error").asText());
            }
            JsonNode wantData = r.get("data");
            if (wantData == null || wantData.isNull()) {
                assertThat(result.getData()).as("search_conversations %s data", r.get("id").asText()).isNull();
            } else {
                assertThat(RecordingSupport.canonicalJson(RecordingSupport.PLAIN.valueToTree(result.getData())))
                        .as("search_conversations %s data", r.get("id").asText())
                        .isEqualTo(RecordingSupport.canonicalJson(wantData));
            }
            if (r.has("limit")) {
                assertThat(stub.gotLimit)
                        .as("search_conversations %s limit", r.get("id").asText())
                        .isEqualTo(r.get("limit").asInt());
            }
            if (r.hasNonNull("owner")) {
                assertThat(stub.gotOwner)
                        .as("search_conversations %s owner", r.get("id").asText())
                        .isEqualTo(r.get("owner").asText());
            }
        }
    }

    private static SearchConversationsTool.ExchangeView x(
            String sess, String title, String q, String a, LocalDate created) {
        return new SearchConversationsTool.ExchangeView(sess, title, created, q, a);
    }

    /** 与 /tmp/toolrec45b 探针种子逐一对齐。 */
    private static List<SearchConversationsTool.ExchangeView> stubFor(String id) {
        return switch (id) {
            case "hits" -> new ArrayList<>(Arrays.asList(
                    x("sess-a", "数据库迁移讨论", "怎么把 MySQL 迁到 PG？", "用 pgloader ……", LocalDate.of(2026, 2, 10)),
                    null,
                    x("sess-current", "当前会话", "不该出现", "不该出现", LocalDate.of(2026, 3, 1)),
                    x("sess-b", "引号 & <转义>", "  ", "带 <tag> 和 \"引号\" 的答案", LocalDate.of(2025, 11, 30)),
                    x("sess-c", "长文截断", "问".repeat(450), "答".repeat(500), LocalDate.of(2025, 1, 1)),
                    x("sess-d", "超取回填", "第五个", "第五个答案", LocalDate.of(2024, 6, 15))));
            case "limit_clamp" -> new ArrayList<>(List.of(
                    x("s1", "一", "q1", "a1", LocalDate.of(2026, 1, 1)),
                    x("s2", "二", "q2", "a2", LocalDate.of(2026, 1, 2)),
                    x("s3", "三", "q3", "a3", LocalDate.of(2026, 1, 3)),
                    x("s4", "四", "q4", "a4", LocalDate.of(2026, 1, 4)),
                    x("s5", "五", "q5", "a5", LocalDate.of(2026, 1, 5))));
            case "only_current" -> new ArrayList<>(List.of(
                    x("sess-current", "当前", "q", "a", LocalDate.of(2026, 1, 1))));
            default -> new ArrayList<>();
        };
    }

    private static final class CapturingStub implements SearchConversationsTool.ConversationSearch {
        final List<SearchConversationsTool.ExchangeView> items;
        int gotLimit;
        String gotOwner;

        CapturingStub(List<SearchConversationsTool.ExchangeView> items) {
            this.items = items;
        }

        @Override
        public List<SearchConversationsTool.ExchangeView> search(String query, int limit, String ownerId) {
            this.gotLimit = limit;
            this.gotOwner = ownerId;
            if ("service_error".equals(query) || "boom".equals(query)) {
                throw new RuntimeException("db down");
            }
            return items;
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
