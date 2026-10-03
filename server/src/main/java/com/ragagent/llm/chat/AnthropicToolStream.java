package com.ragagent.llm.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ragagent.llm.domain.FunctionCall;
import com.ragagent.llm.domain.ToolCall;

/**
 * 流式 tool_use 的累积器。
 *
 * <p>累积纪律（三条都不能少）：</p>
 * <ol>
 *   <li>{@code content_block_start} 记下 ID/Name 与初始 input（空则 {@code {}}）；</li>
 *   <li>{@code input_json_delta.partial_json} <b>分片累加</b>（一次工具调用的 JSON 会跨多个
 *       事件到达）；</li>
 *   <li>{@code content_block_stop} 才置 closed——<b>没收尾的调用一律丢弃</b>。被截断的流里
 *       下一个 tool_use 常常从 {@code {}} 开始，执行那个空对象比不执行更糟：模型从没要求跑一个
 *       残缺调用。</li>
 * </ol>
 *
 * <p>用 {@link TreeMap} 而不是 HashMap：工具调用必须按 content block 下标有序输出。</p>
 *
 * <p>非线程安全：一个流一个实例。</p>
 */
public final class AnthropicToolStream {

    /** 单个工具调用的累积状态。 */
    private static final class ToolInput {
        private final String initial;
        private final ToolCall call;
        private final StringBuilder json = new StringBuilder();
        private boolean closed;

        private ToolInput(String initial, ToolCall call) {
            this.initial = initial;
            this.call = call;
        }
    }

    /** key = content block 下标。 */
    private final TreeMap<Integer, ToolInput> tools = new TreeMap<>();

    /** 消费一个流事件，累积工具调用状态。 */
    public void consume(AnthropicStreamEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType() == null ? "" : event.getType()) {
            case AnthropicStreamEvent.TYPE_CONTENT_BLOCK_START -> {
                AnthropicContentBlock block = event.getContentBlock();
                if (block == null || !AnthropicContentBlock.TYPE_TOOL_USE.equals(block.getType())) {
                    return;
                }
                String initial = block.getInput() == null ? "" : block.getInput().toString();
                if (initial.isEmpty()) {
                    initial = "{}";
                }
                ToolCall call = new ToolCall();
                call.setId(block.getId());
                call.setType("function");
                call.setFunction(new FunctionCall(block.getName(), ""));
                tools.put(event.getIndex(), new ToolInput(initial, call));
            }
            case AnthropicStreamEvent.TYPE_CONTENT_BLOCK_DELTA -> {
                ToolInput tool = tools.get(event.getIndex());
                AnthropicStreamEvent.Delta delta = event.getDelta();
                if (tool != null && delta != null
                        && AnthropicStreamEvent.DELTA_INPUT_JSON.equals(delta.getType())
                        && delta.getPartialJson() != null) {
                    tool.json.append(delta.getPartialJson());
                }
            }
            case AnthropicStreamEvent.TYPE_CONTENT_BLOCK_STOP -> {
                ToolInput tool = tools.get(event.getIndex());
                if (tool != null) {
                    tool.closed = true;
                }
            }
            default -> {
                // message_start / message_delta / message_stop 与工具累积无关
            }
        }
    }

    /**
     * 按下标升序输出<b>已收尾</b>的调用；
     * arguments 优先用累加出来的分片，没有分片时退回 start 事件里的初始 input。
     */
    public List<ToolCall> calls() {
        List<ToolCall> calls = new ArrayList<>(tools.size());
        for (Map.Entry<Integer, ToolInput> entry : tools.entrySet()) {
            ToolInput tool = entry.getValue();
            if (!tool.closed) {
                continue;
            }
            ToolCall call = new ToolCall();
            call.setId(tool.call.getId());
            call.setType(tool.call.getType());
            call.setFunction(new FunctionCall(
                    tool.call.getFunction().getName(),
                    tool.json.length() > 0 ? tool.json.toString() : tool.initial));
            calls.add(call);
        }
        return calls;
    }

    /**
     * finish_reason 归一：
     * max_tokens → "length"；空值或有未闭合调用 → "incomplete"；其余原样。
     */
    public String finishReason(String reason) {
        String value = reason == null ? "" : reason;
        if ("max_tokens".equals(value)) {
            return "length";
        }
        if (value.isEmpty()) {
            return "incomplete";
        }
        for (ToolInput tool : tools.values()) {
            if (!tool.closed) {
                return "incomplete";
            }
        }
        return value;
    }
}
