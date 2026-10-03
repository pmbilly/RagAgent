package com.ragagent.retrieval.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 图片富化信息。
 *
 * <p>检索/富化链路的共享域类型，落 {@code retrieval.domain}。JSON 键为
 * snake_case，六个键恒输出（不做空值省略）。会落 jsonb（image_info 列经各 TypeHandler
 * 透传），故注解只管序列化形状。</p>
 */
@JsonPropertyOrder({"url", "original_url", "start_pos", "end_pos", "caption", "ocr_text"})
public class ImageInfo {

    @JsonProperty("url")
    private String url = "";

    @JsonProperty("original_url")
    private String originalUrl = "";

    @JsonProperty("start_pos")
    private int startPos;

    @JsonProperty("end_pos")
    private int endPos;

    @JsonProperty("caption")
    private String caption = "";

    @JsonProperty("ocr_text")
    private String ocrText = "";

    public String getUrl() { return url == null ? "" : url; }
    public void setUrl(String v) { url = v == null ? "" : v; }
    public String getOriginalUrl() { return originalUrl == null ? "" : originalUrl; }
    public void setOriginalUrl(String v) { originalUrl = v == null ? "" : v; }
    public int getStartPos() { return startPos; }
    public void setStartPos(int v) { startPos = v; }
    public int getEndPos() { return endPos; }
    public void setEndPos(int v) { endPos = v; }
    public String getCaption() { return caption == null ? "" : caption; }
    public void setCaption(String v) { caption = v == null ? "" : v; }
    public String getOcrText() { return ocrText == null ? "" : ocrText; }
    public void setOcrText(String v) { ocrText = v == null ? "" : v; }
}
