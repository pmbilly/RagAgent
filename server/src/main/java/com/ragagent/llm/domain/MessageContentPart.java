package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 多内容消息的一部分（对照 Go chat.MessageContentPart）。
 * 字段序 = Go 声明序：type 恒输出，text/image_url omitempty。
 */
@JsonPropertyOrder({"type", "text", "image_url"})
public class MessageContentPart {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";

    /** "text" 或 "image_url" */
    @JsonProperty("type")
    private String type = "";
    @JsonProperty("text")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String text;
    @JsonProperty("image_url")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private ImageUrl imageUrl;

    public MessageContentPart() {
    }

    public static MessageContentPart text(String text) {
        MessageContentPart p = new MessageContentPart();
        p.type = TYPE_TEXT;
        p.text = text;
        return p;
    }

    public static MessageContentPart image(String url, String detail) {
        MessageContentPart p = new MessageContentPart();
        p.type = TYPE_IMAGE_URL;
        p.imageUrl = new ImageUrl(url, detail);
        return p;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v == null ? "" : v; }
    public String getText() { return text; }
    public void setText(String v) { text = v; }
    public ImageUrl getImageUrl() { return imageUrl; }
    public void setImageUrl(ImageUrl v) { imageUrl = v; }
}
