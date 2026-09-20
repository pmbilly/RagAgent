package com.ragagent.chatpipeline;

/**
 * 用户查询的意图分类（对照 Go {@code types.QueryIntent}，internal/types/chat_manage.go:83-109）。
 *
 * <p>Go 是 string 枚举；零值（空串）按"需要检索"处理（安全默认）。</p>
 */
public final class QueryIntent {

    private QueryIntent() {}

    public static final String KB_SEARCH = "kb_search";
    public static final String WEB_SEARCH = "web_search";
    public static final String GREETING = "greeting";
    public static final String CHITCHAT = "chitchat";
    public static final String FOLLOW_UP = "follow_up";
    public static final String IMAGE_ONLY = "image_only";
    public static final String DOC_ONLY = "doc_only";
    public static final String SUMMARIZE = "summarize";
    public static final String CLARIFICATION = "clarification";

    private static final String INTENT_EMPTY = "";

    /**
     * 对照 {@code QueryIntent.NeedsKBRetrieval}：意图是否需要知识库检索。
     * 零值（空串）视为需要检索；注意 web_search 不在其中——
     * 上层 {@link ChatManage#needsRetrieval()} 还会结合 WebSearchEnabled 判断。
     */
    public static boolean needsKbRetrieval(String intent) {
        switch (intent == null ? INTENT_EMPTY : intent) {
            case KB_SEARCH:
            case CLARIFICATION:
            case SUMMARIZE:
            case INTENT_EMPTY:
                return true;
            default:
                return false;
        }
    }
}
