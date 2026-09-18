package com.ragagent.datasource.connector.notion;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Notion 的文件对象（image / file / pdf / video / audio 块在用）
 * （对照 Go {@code notionFile}，types.go L211-245）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 *
 * <h2>{@code file_upload} 的三态与 {@link #url()} / {@link #fileUploadId()}</h2>
 * <p>Go 的 {@code GetURL} 只看 {@code File} 与 {@code External}（**不看**
 * {@code file_upload}），因为 file_upload 型需要先拿临时下载地址
 * （{@code client.GetFileUploadURL()} 那条路——本项目里对应
 * {@code resolveFileUploads} 的重新取块）。所以 file_upload 型的 URL 是
 * 空串，markdown 里会渲染成 {@code ![]()}，这是**刻意的**、不是漏判。</p>
 *
 * <h2>与 Go 的一处刻意简化（已记录为已知差异）</h2>
 * <p>Go 的 {@code File.ExpiryTime} 是 {@code time.Time}：若 Notion 返回一个
 * 解析不了的时间串，<b>整个 {@code notionFile} 的反序列化会失败</b>，
 * 连已经解出来的 {@code url} 都会丢（Go 填字段是按文档序、遇错即返回）。
 * Java 侧把 {@code expiry_time} 建模成字符串——该字段**全程没有任何读取点**，
 * 于是"解析失败连带丢 URL"这条路径在 Java 侧不存在。这是差异，但方向是
 * "Java 更宽容"，且只在 Notion 发出非法时间时才可见。</p>
 */
public final class NotionFile {

    /** {@code "file"} | {@code "external"} | {@code "file_upload"}。 */
    @JsonProperty("type")
    public String type;

    @JsonProperty("file")
    public HostedFile file;

    @JsonProperty("external")
    public ExternalFile external;

    @JsonProperty("file_upload")
    public FileUploadRef fileUpload;

    @JsonProperty("caption")
    public List<NotionRichText> caption;

    @JsonProperty("name")
    public String name;

    public String type() {
        return type == null ? "" : type;
    }

    /** 对照 Go {@code (*notionFile).GetURL}：托管文件优先，其次外链，file_upload 型回空串。 */
    public String url() {
        if (file != null && file.url != null) {
            return file.url;
        }
        if (external != null && external.url != null) {
            return external.url;
        }
        return "";
    }

    /** 对照 Go {@code (*notionFile).GetFileUploadID}。 */
    public String fileUploadId() {
        if (fileUpload != null && fileUpload.id != null) {
            return fileUpload.id;
        }
        return "";
    }

    /** 对照 Go 的匿名结构体 {@code *struct{URL string; ExpiryTime time.Time}}。 */
    public static final class HostedFile {
        @JsonProperty("url")
        public String url;

        /** 见类注释：Go 是 {@code time.Time}，Java 侧刻意用字符串（该字段无读取点）。 */
        @JsonProperty("expiry_time")
        public String expiryTime;
    }

    /** 对照 Go 的匿名结构体 {@code *struct{URL string `json:"url"`}}。 */
    public static final class ExternalFile {
        @JsonProperty("url")
        public String url;
    }

    /** 对照 Go 的匿名结构体 {@code *struct{ID string `json:"id"`}}。 */
    public static final class FileUploadRef {
        @JsonProperty("id")
        public String id;
    }
}
