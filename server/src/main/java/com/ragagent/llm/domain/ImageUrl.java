package com.ragagent.llm.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 图片 URL 结构（对照 Go chat.ImageURL）。
 * 字段序 = Go 声明序：url 恒输出，detail omitempty。
 */
@JsonPropertyOrder({"url", "detail"})
public class ImageUrl {

    /** URL 或 base64 data URI */
    @JsonProperty("url")
    private String url = "";
    /** "auto" / "low" / "high" */
    @JsonProperty("detail")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String detail;

    public ImageUrl() {
    }

    public ImageUrl(String url, String detail) {
        this.url = url == null ? "" : url;
        this.detail = detail;
    }

    public String getUrl() { return url; }
    public void setUrl(String v) { url = v == null ? "" : v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { detail = v; }
}
