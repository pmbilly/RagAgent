package com.ragagent.stream;

/**
 * 某会话当前正在生成的 assistant 消息（对照 Go 的 liveRunMarker / liveRunPayload）。
 *
 * @param assistantMessageId 正在生成的消息 ID；无 live run 时为 {@code ""}
 * @param requestId          发起该轮次的请求 ID；无 live run 时为 {@code ""}
 */
public record LiveRun(String assistantMessageId, String requestId) {

    /** 无 live run。Go 侧以两个空字符串表达同一语义。 */
    public static final LiveRun NONE = new LiveRun("", "");

    public boolean isPresent() {
        return assistantMessageId != null && !assistantMessageId.isEmpty();
    }
}
