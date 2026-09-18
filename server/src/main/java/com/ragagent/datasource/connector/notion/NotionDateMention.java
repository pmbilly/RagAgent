package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * mention 里的日期信息（对照 Go {@code notionDateMention}，types.go L200-203）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 */
public final class NotionDateMention {

    @JsonProperty("start")
    public String start;

    @JsonProperty("end")
    public String end;

    public String start() {
        return start == null ? "" : start;
    }

    /** 对照 Go 的 {@code omitempty}：空串在语义上等于"没有结束时间"。 */
    public String end() {
        return end == null ? "" : end;
    }
}
