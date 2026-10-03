package com.ragagent.session.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 某轮对话中 skill 脚本产出的文件。
 *
 * <p>与 {@link MessageAttachment} 的区别：附件是**用户上传**的，产物是沙箱
 * **替模型生成**、由 WeKnora 落到文件服务里的——沙箱被回收后用户仍能下载。</p>
 *
 * <p>七个键全部恒输出（§1.6）；键名＝Java 字段名。</p>
 *
 * <p>关于 {@code url}：**序列化上它是输出的**（契约里有 {@code url} 键）；
 * "不要直接暴露给客户端"是意图层面的告诫（调用方不该拿它当可下载链接），不是序列化行为。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageArtifact {

    /** 存储 URL（{@code provider://path}）。 */
    private String url = "";

    /** 沙箱内的原始文件名。 */
    private String fileName = "";

    /** 扩展名，如 {@code .pptx} / {@code .pdf}。 */
    private String fileType = "";

    private long fileSize;

    /** 沙箱内的绝对路径——ArtifactCollector 用它跨多轮去重。 */
    private String sourcePath = "";

    /** 沙箱侧的修改时间（同样用于去重比对）。 */
    private OffsetDateTime modTime;

    /** WeKnora 落盘该 blob 的时刻。 */
    private OffsetDateTime createdAt;

    public MessageArtifact() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String v) {
        this.url = v == null ? "" : v;
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

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String v) {
        this.sourcePath = v == null ? "" : v;
    }

    public OffsetDateTime getModTime() {
        return modTime;
    }

    public void setModTime(OffsetDateTime v) {
        this.modTime = v;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime v) {
        this.createdAt = v;
    }
}
