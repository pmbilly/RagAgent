package com.ragagent.datasource.connector.notion;

/**
 * 待下载的附件（对照 Go {@code attachment}，types.go L258-262）。
 *
 * <p>它是 {@code BlocksToMarkdown} 的第二个返回值，字段在 Go 里**没有 json tag**
 * ——这个类型从不进/json 出任何网络边界。Java 侧同理：内部值对象。</p>
 */
public final class NotionAttachment {

    /** Notion 的 S3 签名地址（1 小时过期）。 */
    public String url;

    public String fileName;

    /** {@code "image"} | {@code "file"} | {@code "pdf"} | {@code "video"} | {@code "audio"}。 */
    public String type;

    public NotionAttachment() {
    }

    public NotionAttachment(String url, String fileName, String type) {
        this.url = url;
        this.fileName = fileName;
        this.type = type;
    }
}
