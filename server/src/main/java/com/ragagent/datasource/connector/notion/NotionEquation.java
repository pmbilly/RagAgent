package com.ragagent.datasource.connector.notion;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code "equation"} 型富文本的内容（对照 Go {@code notionEquation}，types.go L206-208）。
 *
 * <p><b>内部 API 形状，不是契约</b>：只进出于 Notion API 的 JSON。</p>
 */
public final class NotionEquation {

    @JsonProperty("expression")
    public String expression;

    public String expression() {
        return expression == null ? "" : expression;
    }
}
