package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 消息上的文件附件（对照 Go {@code types.MessageAttachment}，
 * internal/types/message.go L129-147）。
 *
 * <p><b>{@code url} 既不进 JSON 也不进数据库</b>（Go 的 tag 就是 {@code json:"-"}`，
 * 而它的 {@code Value()} 又是 {@code json.Marshal} 整个切片——所以元素上的
 * {@code "-"} 同时作用于响应与落库）。Go 的注释说明了原因：它是内部存储句柄
 * （{@code provider://path}），预览走会话级的附件端点，句柄本身一旦外泄就等于给出一个
 * 可跨会话下载的引用。Java 侧一个 {@link JsonIgnore} 覆盖两条路径（类型处理器也用 Jackson）。</p>
 *
 * <p><b>字段名不带 {@code is} 前缀</b>（{@code truncated} 而非 {@code isTruncated}）——
 * 理由见 {@link Session} 上同名字段的注释：Jackson 会因字段与 getter 的隐式名不一致而多吐键。</p>
 *
 * <p>omitempty 的处置：{@code file_size}/{@code line_count}/{@code token_count}/
 * {@code selected_chunks}/{@code total_chunks} 是数值，Go 的 omitempty 会省掉 0，
 * 用 NON_DEFAULT。</p>
 */
@JsonPropertyOrder({
        "id", "file_name", "file_type", "file_size", "content", "is_truncated",
        "line_count", "content_mode", "token_count", "selected_chunks", "total_chunks"
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageAttachment {

    /** 会话级上传的临时文档 ID。 */
    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String id;

    /** 内部存储句柄（{@code provider://path} / {@code resource://...}）。见类注释。 */
    @JsonIgnore
    private String url;

    @JsonProperty("file_name")
    private String fileName = "";

    /** 扩展名，如 {@code .pdf} / {@code .docx}。 */
    @JsonProperty("file_type")
    private String fileType = "";

    @JsonProperty("file_size")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private long fileSize;

    /** 小文本文件抽出来的正文。 */
    @JsonProperty("content")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String content;

    @JsonProperty("is_truncated")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean truncated;

    @JsonProperty("line_count")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int lineCount;

    /** {@code full} 或 {@code selected_chunks}。 */
    @JsonProperty("content_mode")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String contentMode;

    @JsonProperty("token_count")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int tokenCount;

    @JsonProperty("selected_chunks")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int selectedChunks;

    @JsonProperty("total_chunks")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int totalChunks;

    public MessageAttachment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String v) {
        this.id = v;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String v) {
        this.url = v;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String v) {
        this.fileName = v == null ? "" : v;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String v) {
        this.fileType = v == null ? "" : v;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long v) {
        this.fileSize = v;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String v) {
        this.content = v;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean v) {
        this.truncated = v;
    }

    public int getLineCount() {
        return lineCount;
    }

    public void setLineCount(int v) {
        this.lineCount = v;
    }

    public String getContentMode() {
        return contentMode;
    }

    public void setContentMode(String v) {
        this.contentMode = v;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int v) {
        this.tokenCount = v;
    }

    public int getSelectedChunks() {
        return selectedChunks;
    }

    public void setSelectedChunks(int v) {
        this.selectedChunks = v;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int v) {
        this.totalChunks = v;
    }
}
