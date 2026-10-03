package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 富文本的样式信息。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。
 * 缺字段即"全关"的默认形态，故 {@link #EMPTY} 是那个零值。</p>
 */
public final class NotionAnnotations {

    /** 缺省形态：所有样式关闭。 */
    public static final NotionAnnotations EMPTY = new NotionAnnotations();

    @JsonProperty("bold")
    public boolean bold;

    @JsonProperty("italic")
    public boolean italic;

    @JsonProperty("strikethrough")
    public boolean strikethrough;

    @JsonProperty("underline")
    public boolean underline;

    @JsonProperty("code")
    public boolean code;

    @JsonProperty("color")
    public String color;
}
