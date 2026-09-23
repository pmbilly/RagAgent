package com.ragagent.agent.tools;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.agent.domain.ToolResult;

/**
 * search_conversations 工具（对照 Go {@code search_conversations.go}，逐字移植）。
 *
 * <p>搜索用户自己的历史会话。Go 注入 {@code interfaces.MessageService} + 构造期捕获的
 * ownerID/currentSessionID（不给模型留改参数的口子）；Java 侧用函数式接口
 * {@link ConversationSearch} 表达，装配期接 session 模块真实实现。</p>
 */
public class SearchConversationsTool extends BaseTool {

    /** 历史消息搜索回调（对照 interfaces.MessageService.SearchMessages 的位置）。 */
    @FunctionalInterface
    public interface ConversationSearch {
        /** 失败时抛 RuntimeException（工具折叠为 success=false+error）。 */
        List<ExchangeView> search(String query, int limit, String ownerId);
    }

    /** 一条历史问答视图（对照 types.MessageSearchResultItem 被用字段）。 */
    public record ExchangeView(String sessionId, String sessionTitle, LocalDate createdAt,
                               String queryContent, String answerContent) {
    }

    /** 对照 searchConversationsMaxResults。 */
    static final int MAX_RESULTS = 8;
    /** 对照 searchConversationsSnippetRunes。 */
    static final int SNIPPET_RUNES = 400;

    /** 键序对照 Go GenerateSchema 输出（字母序：properties < required < type）。 */
    private static final String SCHEMA_JSON = """
            {
              "properties": {
                "limit": {
                  "description": "Maximum number of past exchanges to return (default 5, max 8)",
                  "type": "integer"
                },
                "query": {
                  "description": "What to look for in past conversations, in the user's own words",
                  "type": "string"
                }
              },
              "required": ["query"],
              "type": "object"
            }""";

    private static final String DESCRIPTION = """
            Search this user's own past conversations with the assistant.

            ## When to Use

            Use this tool when the user refers to something that was discussed before but is
            not in the current conversation:
            - "上次你给我的那个配置" / "we talked about this last month"
            - "我之前问过的那个报错" — the error and its answer are in an older session
            - The user assumes shared context that this session does not contain

            Do not use when:
            - The answer is in documents (use knowledge_search — that is the knowledge base)
            - The information is in the current conversation already
            - The user is asking a general question with no reference to the past

            ## What It Returns

            Matching exchanges from the user's own previous sessions, each with the session
            title, the date, the user's question and the assistant's answer.

            ## Notes

            - Only this user's own conversations are searched, never a colleague's.
            - Past answers may be outdated. Prefer current documents when they disagree,
              and say so rather than repeating a stale answer as fact.""";

    private final ConversationSearch conversationSearch;
    private final String ownerId;
    private final String currentSessionId;

    public SearchConversationsTool(ConversationSearch conversationSearch, String ownerId, String currentSessionId) {
        super(ToolDefinitions.TOOL_SEARCH_CONVERSATIONS, DESCRIPTION, SCHEMA_JSON);
        this.conversationSearch = conversationSearch;
        this.ownerId = ownerId;
        this.currentSessionId = currentSessionId;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        JsonNode args = request.args();
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return failure("query is required");
        }
        if (conversationSearch == null) {
            return failure("conversation history search is not available");
        }

        int limit = args.path("limit").asInt(0);
        if (limit <= 0) {
            limit = 5;
        }
        if (limit > MAX_RESULTS) {
            limit = MAX_RESULTS;
        }

        final int effectiveLimit = limit;
        List<ExchangeView> items;
        try {
            // 超取 limit+2：丢当前会话后不至于空结果（对照 Go 注释）
            items = conversationSearch.search(query, effectiveLimit + 2, ownerId);
        } catch (RuntimeException e) {
            return failure("Conversation search failed: " + e.getMessage());
        }

        StringBuilder b = new StringBuilder();
        b.append("<past_conversations>\n");
        int found = 0;
        for (ExchangeView item : items) {
            if (item == null || found >= effectiveLimit) {
                continue;
            }
            if (item.sessionId() != null && item.sessionId().equals(currentSessionId)) {
                continue;
            }
            found++;
            b.append("<exchange session=\"").append(SearchMemoryTool.xmlEscape(item.sessionTitle())).append("\" date=\"")
                    .append(item.createdAt() != null
                            ? item.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE)
                            : "0001-01-01")
                    .append("\">\n");
            String question = snippet(item.queryContent(), SNIPPET_RUNES);
            if (!question.isEmpty()) {
                b.append("<user>").append(SearchMemoryTool.xmlEscape(question)).append("</user>\n");
            }
            String answer = snippet(item.answerContent(), SNIPPET_RUNES);
            if (!answer.isEmpty()) {
                b.append("<assistant>").append(SearchMemoryTool.xmlEscape(answer)).append("</assistant>\n");
            }
            b.append("</exchange>\n");
        }
        b.append("</past_conversations>");

        if (found == 0) {
            ToolResult r = new ToolResult();
            r.setSuccess(true);
            r.setOutput("<past_conversations />\n"
                    + "Nothing in this user's past conversations matches. "
                    + "Do not assume it was discussed before.");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("matches", 0);
            r.setData(data);
            return r;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("matches", found);

        ToolResult r = new ToolResult();
        r.setSuccess(true);
        r.setOutput(b.toString());
        r.setData(data);
        return r;
    }

    private static ToolResult failure(String message) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(message);
        return result;
    }

    /** 对照 snippet：TrimSpace 后按 rune 截断加 "…"（U+2026）。 */
    static String snippet(String text, int maxRunes) {
        if (text == null) {
            return "";
        }
        text = text.trim();
        if (text.isEmpty()) {
            return "";
        }
        int[] runes = text.codePoints().toArray();
        if (runes.length <= maxRunes) {
            return text;
        }
        return new String(runes, 0, maxRunes) + "…";
    }
}
