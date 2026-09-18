package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Notion 的一个富文本片段（对照 Go {@code notionRichText}，types.go L156-164）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON，从不落 jsonb、
 * 从不作 HTTP 响应体。</p>
 *
 * <h2>空值归一化</h2>
 * <p>Notion 对可空字段会**显式发 {@code null}**（例如无链接的 {@code href}），
 * 于是 Jackson 会把字段置成 {@code null}——而 Go 那边 {@code Href string} 解出来是
 * {@code ""}。所有取值一律走带 {@code ()} 的方法（它们不是 bean getter，
 * Jackson 不会把它们当成属性），归一化只发生在一个地方。</p>
 */
public final class NotionRichText {

    /** {@code "text"} | {@code "mention"} | {@code "equation"}。 */
    @JsonProperty("type")
    public String type;

    @JsonProperty("plain_text")
    public String plainText;

    @JsonProperty("href")
    public String href;

    @JsonProperty("annotations")
    public NotionAnnotations annotations;

    @JsonProperty("text")
    public NotionTextContent text;

    @JsonProperty("mention")
    public NotionMention mention;

    @JsonProperty("equation")
    public NotionEquation equation;

    public String type() {
        return type == null ? "" : type;
    }

    public String plainText() {
        return plainText == null ? "" : plainText;
    }

    public String href() {
        return href == null ? "" : href;
    }

    /** 对照 Go 的值类型 {@code Annotations notionAnnotations}：缺字段即零值。 */
    public NotionAnnotations annotations() {
        return annotations == null ? NotionAnnotations.EMPTY : annotations;
    }
}
