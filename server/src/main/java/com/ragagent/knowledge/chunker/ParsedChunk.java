package com.ragagent.knowledge.chunker;

/**
 * 切分输出块。
 * <p>start / end 为原文的 rune（Unicode code point）偏移，恒有
 * {@code end - start == runeLen(content)}；该不变式被文档重建代码依赖
 * 面包屑）是单独追踪的上下文串，仅在 embedding 时前置，不属于 content。</p>
 */
public class ParsedChunk {

    private String content = "";
    private String contextHeader = "";
    private int seq;
    /** rune 偏移。 */
    private int start;
    /** rune 偏移，end-exclusive。 */
    private int end;
    /** parent-child 分块时指向父块下标；-1 表示无父块。 */
    private int parentIndex;

    public ParsedChunk() {
    }

    public ParsedChunk(String content, String contextHeader, int seq, int start, int end) {
        this.content = content;
        this.contextHeader = contextHeader;
        this.seq = seq;
        this.start = start;
        this.end = end;
        this.parentIndex = -1;
    }

    /**
     * 送入 embedding 模型的文本：contextHeader（若有）+ "\n\n" + trimSpace(content)。
     * chunker.Chunk.EmbeddingContent（splitter.go:43）。
     */
    public String embeddingContent() {
        String body = content.strip();
        if (contextHeader.isEmpty()) {
            return body;
        }
        return contextHeader + "\n\n" + body;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContextHeader() {
        return contextHeader;
    }

    public void setContextHeader(String contextHeader) {
        this.contextHeader = contextHeader;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int end) {
        this.end = end;
    }

    public int getParentIndex() {
        return parentIndex;
    }

    public void setParentIndex(int parentIndex) {
        this.parentIndex = parentIndex;
    }

    // ---- record 风格访问器别名（DB 实体列名 start_at/end_at 对应命名） ----

    public String content() {
        return content;
    }

    public String contextHeader() {
        return contextHeader;
    }

    public int seq() {
        return seq;
    }

    /** rune 偏移（别名 {@link #getStart()}）。 */
    public int startAt() {
        return start;
    }

    /** rune 偏移，end-exclusive（别名 {@link #getEnd()}）。 */
    public int endAt() {
        return end;
    }

    public int parentIndex() {
        return parentIndex;
    }

    @Override
    public String toString() {
        return "ParsedChunk{seq=" + seq + ", start=" + start + ", end=" + end
                + ", contextHeader=" + contextHeader + ", content=" + content + "}";
    }
}
