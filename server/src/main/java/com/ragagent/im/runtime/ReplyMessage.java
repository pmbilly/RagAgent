package com.ragagent.im.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WeKnora 发回 IM 平台的回复（对照 Go {@code im.ReplyMessage}，adapter.go L114-123）。
 */
public final class ReplyMessage {

    /** 文本内容（Markdown）。 */
    public final String content;
    /** 是否流式分片。 */
    public final boolean isStreaming;
    /** 流式回复的最后一片。 */
    public final boolean isFinal;
    /** 平台私有字段。 */
    public Map<String, String> extra = new LinkedHashMap<>();

    public ReplyMessage(String content) {
        this(content, false, false);
    }

    public ReplyMessage(String content, boolean isStreaming, boolean isFinal) {
        this.content = content == null ? "" : content;
        this.isStreaming = isStreaming;
        this.isFinal = isFinal;
    }
}
