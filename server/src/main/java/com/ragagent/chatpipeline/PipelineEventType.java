package com.ragagent.chatpipeline;

/**
 * RAG 管线阶段事件类型。
 *
 * <p>命名避开 com.ragagent.event 已占用的 {@code EventType}，加 Pipeline 前缀；
 * 值为小写下划线形式的字符串字面量。</p>
 */
public final class PipelineEventType {

    private PipelineEventType() {}

    public static final String LOAD_HISTORY = "load_history";
    public static final String MEMORY_RECALL = "memory_recall";
    public static final String QUERY_UNDERSTAND = "query_understand";
    public static final String CHUNK_SEARCH = "chunk_search";
    public static final String CHUNK_SEARCH_PARALLEL = "chunk_search_parallel";
    public static final String ENTITY_SEARCH = "entity_search";
    public static final String CHUNK_RERANK = "chunk_rerank";
    public static final String WEB_FETCH = "web_fetch";
    public static final String CHUNK_MERGE = "chunk_merge";
    public static final String DATA_ANALYSIS = "data_analysis";
    public static final String INTO_CHAT_MESSAGE = "into_chat_message";
    public static final String CHAT_COMPLETION = "chat_completion";
    public static final String CHAT_COMPLETION_STREAM = "chat_completion_stream";
    public static final String FILTER_TOP_K = "filter_top_k";
}
