package com.ragagent.datasource.connector.notion;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Notion 的文件对象（image / file / pdf / video / audio 块在用）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 *
 * <h2>{@code file_upload} 的三态与 {@link #url()} / {@link #fileUploadId()}</h2>
 * <p>{@link #url()} 只看 {@code file} 与 {@code external}（**不看**
 * {@code file_upload}），因为 file_upload 型需要先拿临时下载地址
 * （对应 {@code resolveFileUploads} 的重新取块）。所以 file_upload 型的 URL 是
 * 空串，markdown 里会渲染成 {@code ![]()}，这是**刻意的**、不是漏判。</p>
 *
 * <h2>{@code expiry_time} 刻意用字符串</h2>
 * <p>该字段**全程没有任何读取点**；建模成字符串还让"非法时间串导致整个对象
 * 反序列化失败、连 url 都丢"这条路径不存在。</p>
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

    /** 托管文件优先，其次外链，file_upload 型回空串。 */
    public String url() {
        if (file != null && file.url != null) {
            return file.url;
        }
        if (external != null && external.url != null) {
            return external.url;
        }
        return "";
    }

    /** file_upload 型的临时下载标识。 */
    public String fileUploadId() {
        if (fileUpload != null && fileUpload.id != null) {
            return fileUpload.id;
        }
        return "";
    }

    /** 托管文件（带过期时间的签名地址）。 */
    public static final class HostedFile {
        @JsonProperty("url")
        public String url;

        /** 见类注释：刻意用字符串（该字段无读取点）。 */
        @JsonProperty("expiry_time")
        public String expiryTime;
    }

    /** 外链文件。 */
    public static final class ExternalFile {
        @JsonProperty("url")
        public String url;
    }

    /** file_upload 引用。 */
    public static final class FileUploadRef {
        @JsonProperty("id")
        public String id;
    }
}
