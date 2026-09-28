package com.ragagent.llm.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragagent.llm.domain.TokenUsage;
import com.ragagent.llm.domain.ToolCall;

/**
 * OpenAI 兼容流式处理状态（对照 Go internal/models/chat/openai_stream.go:309-386 的
 * {@code streamState} + {@code newStreamState} + {@code buildOrderedToolCalls} +
 * {@code setToolCallProviderMetadata}）。
 *
 * <p>字段名一一对应；Go 里内嵌的 thinkingEmitter 在 Java 侧是 {@link #thinking} 字段
 * （组合而非继承）。诊断用的 fire-once 标志（firstToolCallSeen / firstContentSeen /
 * firstReasoningSeen / noToolCallStopLogged / streamStartedAt）也在，用于同款日志。</p>
 *
 * <p>包内字段直接可见（对照 Go 同包 struct 字段），仅供 {@link RemoteApiChat}
 * 的单线程流循环使用——**非线程安全**。</p>
 */
final class OpenAiStreamState {

    /** tool-call index → 组装中的工具调用（对照 toolCallMap）。 */
    final Map<Integer, ToolCall> toolCallMap = new LinkedHashMap<>();
    /** 上一次见到的函数名，用于判断"名字是否已稳定"（对照 lastFunctionName）。 */
    final Map<Integer, String> lastFunctionName = new LinkedHashMap<>();
    /** 该 index 的 tool_call 标记是否已发出（对照 nameNotified）。 */
    final Map<Integer, Boolean> nameNotified = new LinkedHashMap<>();
    /** thinking 工具的 thought 字段增量抽取器（对照 fieldExtractors）。 */
    final Map<Integer, JsonFieldExtractor> fieldExtractors = new LinkedHashMap<>();

    /** 从最后一个携带 usage 的分片里捕获的用量（include_usage 开启时才有）。 */
    TokenUsage usage;
    /** 最近一次观察到的 finish_reason，供流结束兜底使用（对照 lastFinishReason）。 */
    String lastFinishReason = "";

    /** 思考内容交接器（对照 Go 内嵌的 thinkingEmitter）。 */
    final ThinkingEmitter thinking = new ThinkingEmitter();

    // 诊断标志（fire-once），对照 Go streamState 的同名字段
    boolean firstToolCallSeen;
    boolean noToolCallStopLogged;
    boolean firstContentSeen;
    boolean firstReasoningSeen;
    final Instant streamStartedAt = Instant.now();

    /** 对照 Go elapsedMs：距流开始经过的毫秒数，用于诊断日志的时间轴。 */
    long elapsedMs() {
        return Duration.between(streamStartedAt, Instant.now()).toMillis();
    }

    /**
     * 对照 Go buildOrderedToolCalls：按 index 从 0 起顺序取，取不到就跳过。
     *
     * <p><b>保真要点</b>：Go 的上界是 {@code len(s.toolCallMap)}（map 元素个数），而不是
     * 最大 index——索引不连续时（如只有 0 和 5）高位的会被丢掉。这里照抄同一上界，
     * 线上行为一致。</p>
     */
    List<ToolCall> buildOrderedToolCalls() {
        if (toolCallMap.isEmpty()) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>(toolCallMap.size());
        for (int i = 0; i < toolCallMap.size(); i++) {
            ToolCall tc = toolCallMap.get(i);
            if (tc != null) {
                result.add(tc);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 对照 Go setToolCallProviderMetadata：把厂商特有状态挂到对应 index 上；
     * 该 index 还没有条目时**先建一个空壳**（Type 固定 "function"、名字与参数为空），
     * 这样后续的 tool_calls 增量会往同一条目里填，元数据不丢。
     */
    void setToolCallProviderMetadata(int index, Map<String, JsonNode> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        ToolCall entry = toolCallMap.get(index);
        if (entry == null) {
            entry = new ToolCall();
            entry.setType("function");
            entry.getFunction().setName("");
            entry.getFunction().setArguments("");
            toolCallMap.put(index, entry);
        }
        entry.setProviderMetadata(metadata);
    }
}
