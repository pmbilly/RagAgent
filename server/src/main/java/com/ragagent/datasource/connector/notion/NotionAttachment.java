package com.ragagent.datasource.connector.notion;

/**
 * 待下载的附件。
 *
 * <p>它是块树转 Markdown 的第二个返回值，字段没有 json tag
 * ——这个类型从不进/json 出任何网络边界。内部值对象。</p>
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
