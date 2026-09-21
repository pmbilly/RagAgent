package com.ragagent.im.runtime;

/**
 * 一条 IM 流文本缓冲的换行状态（对照 Go internal/im/stream_section.go 全文，
 * 波 5 W5γ1 逐行翻译）。
 */
public final class StreamSection {

    private boolean lastCharNewline;
    private final StringBuilder text = new StringBuilder();

    public void write(String str) {
        if (str == null || str.isEmpty()) {
            return;
        }
        text.append(str);
        lastCharNewline = str.charAt(str.length() - 1) == '\n';
    }

    public void ensureNewlineBefore() {
        if (!lastCharNewline) {
            text.append('\n');
            lastCharNewline = true;
        }
    }

    /** 累积文本（对照 Go 的 text.String()）。 */
    public String text() {
        return text.toString();
    }
}
