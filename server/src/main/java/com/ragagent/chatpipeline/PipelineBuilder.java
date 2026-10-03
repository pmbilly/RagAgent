package com.ragagent.chatpipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 管线装配器与预设管线。
 */
public final class PipelineBuilder {

    private List<String> stages = new ArrayList<>();

    public static PipelineBuilder builder() {
        return new PipelineBuilder();
    }

    /** 无条件追加。 */
    public PipelineBuilder add(String... stages) {
        for (String s : stages) {
            this.stages.add(s);
        }
        return this;
    }

    /** 条件追加。 */
    public PipelineBuilder addIf(boolean cond, String... stages) {
        if (cond) {
            return add(stages);
        }
        return this;
    }

    /** 输出最终事件列表（builder 不复用）。 */
    public List<String> build() {
        return new ArrayList<>(stages);
    }

    /** 预设管线。 */
    public static Map<String, List<String>> presets() {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("chat", List.of(PipelineEventType.CHAT_COMPLETION));
        p.put("chat_stream", List.of(PipelineEventType.CHAT_COMPLETION_STREAM));
        p.put("chat_history_stream",
                List.of(PipelineEventType.LOAD_HISTORY, PipelineEventType.CHAT_COMPLETION_STREAM));
        p.put("rag", List.of(PipelineEventType.CHUNK_SEARCH, PipelineEventType.CHUNK_RERANK,
                PipelineEventType.CHUNK_MERGE, PipelineEventType.INTO_CHAT_MESSAGE,
                PipelineEventType.CHAT_COMPLETION));
        p.put("rag_stream", List.of(
                PipelineEventType.LOAD_HISTORY,
                PipelineEventType.QUERY_UNDERSTAND,
                PipelineEventType.CHUNK_SEARCH_PARALLEL,
                PipelineEventType.CHUNK_RERANK,
                PipelineEventType.CHUNK_MERGE,
                PipelineEventType.FILTER_TOP_K,
                PipelineEventType.DATA_ANALYSIS,
                PipelineEventType.INTO_CHAT_MESSAGE,
                PipelineEventType.CHAT_COMPLETION_STREAM));
        return p;
    }
}
