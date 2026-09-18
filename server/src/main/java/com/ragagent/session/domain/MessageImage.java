package com.ragagent.session.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 挂在消息上的图片（对照 Go {@code types.MessageImage}，internal/types/message.go L93-96）。
 *
 * <p>{@code url} 无 omitempty（恒输出）；{@code caption} 有 omitempty。</p>
 *
 * <p><b>落库时的空值语义</b>：Go 的 {@code MessageImages.Value()} 把 nil 切片写成
 * {@code []}（不是 SQL NULL），所以 Java 实体上的这个列表字段**默认是空列表**，
 * 由类型处理器写成 {@code []}。</p>
 */
@JsonPropertyOrder({"url", "caption"})
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageImage {

    @JsonProperty("url")
    private String url = "";

    @JsonProperty("caption")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String caption;

    public MessageImage() {
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String v) {
        this.url = v == null ? "" : v;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String v) {
        this.caption = v;
    }
}
