package com.ragagent.chatpipeline;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ragagent.agent.tools.SearchTarget;
import com.ragagent.event.Event;
import com.ragagent.event.EventBusInterface;
import com.ragagent.event.EventType;
import com.ragagent.event.AgentToolCallData;
import com.ragagent.event.AgentToolResultData;
import com.ragagent.retrieval.domain.SearchResult;

/**
 * 管线进度事件（对照 Go chat_pipeline/progress.go）。
 *
 * <p>合并检索窗口：SEARCH_PARALLEL/RERANK/MERGE/FILTER_TOP_K（+ web_fetch/data_analysis
 * 条件生效）共用一个 {@code knowledge_search} 工具的 pending/tool_result 事件对；
 * ErrSearchNothing 短路时窗口照关（候选数进 data.candidate_count，命中数清零——
 * "检索到 N 条" 不能许诺回答从未见过引用）。query_understand 有独立窗口。</p>
 *
 * <p>tool_call_id 是 uuid（实录组 progress 以 MASKED-UUID 掩码比对）；duration 是
 * omitempty 键（两侧同删）。事件经 EventBus emit，失败恒忽略（{@code _ =}）。</p>
 */
public final class PipelineProgress {

    private static final String RETRIEVAL_PROGRESS_TOOL = "knowledge_search";
    private static final String QUERY_UNDERSTAND_PROGRESS_TOOL = "query_understand";

    private static final String RETRIEVAL_SOURCE_KNOWLEDGE = "knowledge";
    private static final String RETRIEVAL_SOURCE_WEB = "web";
    private static final String RETRIEVAL_SOURCE_MIXED = "mixed";

    /** 时钟接缝（测试注入固定时钟；生产 = 系统时钟）。 */
    static Clock clock = Clock.systemUTC();

    private PipelineProgress() {}

    /** 阶段进行中的进度工具调用（对照 StageProgress）。 */
    public static final class StageProgress {
        final String toolCallId;
        final String toolName;

        StageProgress(String toolCallId, String toolName) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
        }
    }

    /** 对照 ShouldEmitQueryUnderstandProgress。 */
    public static boolean shouldEmitQueryUnderstandProgress(ChatManage chatManage) {
        if (chatManage == null) {
            return false;
        }
        return chatManage.isEnableRewrite()
                || (chatManage.getImages() != null && !chatManage.getImages().isEmpty());
    }

    /** 对照 IsConsolidatedRetrievalStage。 */
    public static boolean isConsolidatedRetrievalStage(String stage, ChatManage chatManage) {
        if (chatManage == null) {
            return false;
        }
        switch (stage) {
            case PipelineEventType.CHUNK_SEARCH_PARALLEL:
            case PipelineEventType.CHUNK_RERANK:
            case PipelineEventType.CHUNK_MERGE:
            case PipelineEventType.FILTER_TOP_K:
                return chatManage.needsRetrieval();
            case PipelineEventType.WEB_FETCH:
                return chatManage.isWebSearchEnabled();
            case PipelineEventType.DATA_ANALYSIS:
                return chatManage.isDataAnalysisEnabled() && chatManage.needsRetrieval();
            default:
                return false;
        }
    }

    /** 对照 LastConsolidatedRetrievalStage（无命中返回 ""）。 */
    public static String lastConsolidatedRetrievalStage(List<String> eventList, ChatManage chatManage) {
        String last = "";
        for (String stage : eventList) {
            if (isConsolidatedRetrievalStage(stage, chatManage)) {
                last = stage;
            }
        }
        return last;
    }

    /** 对照 ShouldCloseRetrievalProgress（ErrSearchNothing 引用比较在此生效）。 */
    public static boolean shouldCloseRetrievalProgress(String stage, String lastRetrievalStage,
                                                       PluginError stageErr) {
        return stage.equals(lastRetrievalStage) || stageErr != null;
    }

    /** 对照 BeginRetrievalProgress：发单个 pending knowledge_search tool_call。 */
    public static StageProgress beginRetrievalProgress(ChatManage chatManage) {
        if (chatManage == null || chatManage.getEventBus() == null) {
            return null;
        }
        String toolCallId = UUID.randomUUID().toString();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("search_source", retrievalSearchSource(chatManage));
        if (!chatManage.getRewriteQuery().isEmpty()) {
            args.put("query", chatManage.getRewriteQuery());
        } else if (!chatManage.getQuery().isEmpty()) {
            args.put("query", chatManage.getQuery());
        }
        emit(chatManage.getEventBus(), toolCallEvent(chatManage.getSessionId(), toolCallId,
                RETRIEVAL_PROGRESS_TOOL, args));
        return new StageProgress(toolCallId, RETRIEVAL_PROGRESS_TOOL);
    }

    /** 对照 BeginQueryUnderstandProgress。 */
    public static StageProgress beginQueryUnderstandProgress(ChatManage chatManage) {
        if (chatManage == null || chatManage.getEventBus() == null
                || !shouldEmitQueryUnderstandProgress(chatManage)) {
            return null;
        }
        String toolCallId = UUID.randomUUID().toString();
        Map<String, Object> args = new LinkedHashMap<>();
        if (!chatManage.getQuery().isEmpty()) {
            args.put("query", chatManage.getQuery());
        }
        if (chatManage.getImages() != null && !chatManage.getImages().isEmpty()) {
            args.put("has_images", true);
        }
        emit(chatManage.getEventBus(), toolCallEvent(chatManage.getSessionId(), toolCallId,
                QUERY_UNDERSTAND_PROGRESS_TOOL, args));
        return new StageProgress(toolCallId, QUERY_UNDERSTAND_PROGRESS_TOOL);
    }

    /** 对照 EndQueryUnderstandProgress。 */
    public static void endQueryUnderstandProgress(ChatManage chatManage, StageProgress progress,
                                                  long startMillis, PluginError stageErr) {
        if (progress == null || chatManage == null || chatManage.getEventBus() == null) {
            return;
        }
        boolean success = stageErr == null;
        String output = success ? "已完成问题理解" : "";
        String errMsg = !success && stageErr != null && stageErr.err != null
                ? stageErr.err.getMessage() : "";

        long durationMs = clock.millis() - startMillis;
        AgentToolResultData data = new AgentToolResultData();
        data.setToolCallId(progress.toolCallId);
        data.setToolName(progress.toolName);
        data.setOutput(output);
        data.setError(errMsg);
        data.setSuccess(success);
        data.setDurationMs(durationMs);
        emit(chatManage.getEventBus(), toolResultEvent(chatManage.getSessionId(), data));
    }

    /** 对照 EndRetrievalProgress：命中分档文案 + search_source + ErrSearchNothing 候选语义。 */
    public static void endRetrievalProgress(ChatManage chatManage, StageProgress progress,
                                            long startMillis, PluginError stageErr) {
        if (progress == null || chatManage == null || chatManage.getEventBus() == null) {
            return;
        }

        long[] counts = retrievalResultBreakdown(chatManage);
        int count = (int) counts[0];
        int docCount = (int) counts[1];
        int webCount = (int) counts[2];

        int candidateCount = 0;
        if (stageErr == PluginError.SEARCH_NOTHING) {
            candidateCount = count;
            count = 0;
            docCount = 0;
            webCount = 0;
        }

        String searchSource = retrievalSearchSource(chatManage);
        if (count > 0) {
            if (docCount > 0 && webCount > 0) {
                searchSource = RETRIEVAL_SOURCE_MIXED;
            } else if (webCount > 0) {
                searchSource = RETRIEVAL_SOURCE_WEB;
            } else {
                searchSource = RETRIEVAL_SOURCE_KNOWLEDGE;
            }
        }
        boolean success = stageErr == null || stageErr == PluginError.SEARCH_NOTHING;
        String output = "";
        if (success) {
            if (count > 0) {
                output = String.format("检索到 %d 条相关内容", count);
            } else if (candidateCount > 0) {
                output = String.format("命中 %d 条候选，相关性不足，未用于回答", candidateCount);
            } else {
                output = "未检索到相关内容";
            }
        }

        String errMsg = !success && stageErr != null && stageErr.err != null
                ? stageErr.err.getMessage() : "";

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("count", count);
        structured.put("doc_count", docCount);
        structured.put("web_count", webCount);
        structured.put("search_source", searchSource);
        structured.put("candidate_count", candidateCount);

        long durationMs = clock.millis() - startMillis;
        AgentToolResultData data = new AgentToolResultData();
        data.setToolCallId(progress.toolCallId);
        data.setToolName(progress.toolName);
        data.setOutput(output);
        data.setError(errMsg);
        data.setSuccess(success);
        data.setDurationMs(durationMs);
        data.setData(structured);
        emit(chatManage.getEventBus(), toolResultEvent(chatManage.getSessionId(), data));
    }

    // ----- 内部（progress.go:251-306） -----

    static boolean hasKBRetrievalTargets(ChatManage chatManage) {
        if (chatManage == null) {
            return false;
        }
        return SearchTarget.SearchTargets.hasKnowledgeRetrievalScope(
                new SearchTarget.SearchTargets(chatManage.getSearchTargets()),
                chatManage.getKnowledgeBaseIds(), chatManage.getKnowledgeIds());
    }

    static String retrievalSearchSource(ChatManage chatManage) {
        boolean hasKB = hasKBRetrievalTargets(chatManage);
        boolean hasWeb = chatManage != null && chatManage.isWebSearchEnabled();
        if (hasKB && hasWeb) {
            return RETRIEVAL_SOURCE_MIXED;
        }
        if (hasWeb) {
            return RETRIEVAL_SOURCE_WEB;
        }
        return RETRIEVAL_SOURCE_KNOWLEDGE;
    }

    static List<SearchResult> retrievalResults(ChatManage chatManage) {
        if (chatManage.getMergeResult() != null && !chatManage.getMergeResult().isEmpty()) {
            return chatManage.getMergeResult();
        }
        if (chatManage.getRerankResult() != null && !chatManage.getRerankResult().isEmpty()) {
            return chatManage.getRerankResult();
        }
        return chatManage.getSearchResult();
    }

    static long[] retrievalResultBreakdown(ChatManage chatManage) {
        long total = 0;
        long docCount = 0;
        long webCount = 0;
        List<SearchResult> results = retrievalResults(chatManage);
        if (results != null) {
            for (SearchResult result : results) {
                if (result == null) {
                    continue;
                }
                total++;
                if (isWebSearchResult(result)) {
                    webCount++;
                } else {
                    docCount++;
                }
            }
        }
        return new long[] {total, docCount, webCount};
    }

    static boolean isWebSearchResult(SearchResult result) {
        if ("web_search".equalsIgnoreCase(result.getChunkType())) {
            return true;
        }
        return "web_search".equalsIgnoreCase(result.getKnowledgeSource());
    }

    // ----- 事件构造 -----

    private static Event toolCallEvent(String sessionId, String toolCallId, String toolName,
                                       Map<String, Object> arguments) {
        Event evt = new Event();
        evt.setType(EventType.EVENT_AGENT_TOOL_CALL);
        evt.setSessionId(sessionId);
        AgentToolCallData data = new AgentToolCallData();
        data.setToolCallId(toolCallId);
        data.setToolName(toolName);
        data.setArguments(arguments);
        evt.setData(data);
        return evt;
    }

    private static Event toolResultEvent(String sessionId, AgentToolResultData data) {
        Event evt = new Event();
        evt.setType(EventType.EVENT_AGENT_TOOL_RESULT);
        evt.setSessionId(sessionId);
        evt.setData(data);
        return evt;
    }

    private static void emit(EventBusInterface bus, Event evt) {
        try {
            bus.emit(evt);
        } catch (RuntimeException ignored) {
            // 对照 `_ = bus.Emit(...)`：进度事件失败不影响回答
        }
    }
}
